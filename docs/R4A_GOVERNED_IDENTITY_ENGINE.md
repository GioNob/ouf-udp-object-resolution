# R4a: identità degli oggetti canonici

## Confine della fonte

Una fonte può essere un file (CSV compreso) o un verticale che trasmette via API.
Onboarding configura la fonte; Ingestion acquisisce, interpreta e produce gli
oggetti canonici con lineage e riferimenti ai contratti. UDP riceve gli handoff
canonici, non il file originale né la sua estensione. L'esempio dei cinema è
soltanto una fixture, non uno schema di identità di produzione.

## Decisione

Non si presume che esista un identificatore stabile. Coordinate e indirizzo
possono descrivere punti diversi dello stesso oggetto e costituiscono indizi.
Una policy di una classe e una fonte dichiara i possibili campi canonici
mappati, con versione semantica e comparatore per ciascuno. Ogni singolo
oggetto può esporne un sottoinsieme diverso. Per decidere MATCH, tutti i
campi dell'oggetto con meno proprietà devono esistere anche nell'altro e
avere valori semanticamente compatibili e uguali. Questo vale in entrambe le
direzioni: sia l'oggetto in ingresso sia quello già in UDP possono essere il
più ricco. Se entrambi espongono campi propri che mancano nell'altro, o se un
campo condiviso differisce, il caso resta incerto. L'insieme confrontato non
può essere vuoto e un MATCH richiede un unico candidato che soddisfi la
regola. Nessuna proprietà è assunta come identificatore stabile.
Un candidato è invece certamente distinto se i due oggetti espongono gli
stessi campi e **tutti** i relativi valori confrontabili sono diversi: non
resta selezionabile come lo stesso oggetto.
Se tutti i candidati sono distinti, `allowAutoNew` può autorizzare la
creazione. Campi senza corrispondenza, versioni semantiche incompatibili o
valori non confrontabili non bastano per concludere che due oggetti siano
distinti; rimangono incerti. Una differenza isolata tra campi per il resto
uguali, come una diversa georeferenziazione, rimane incerta.

Senza candidati, oppure con soli candidati certamente distinti, nel perimetro
completo e verificato si può creare un oggetto solo quando la policy della
fonte abilita `allowAutoNew`. Candidati plausibili senza un'unica uguaglianza
completa richiedono `REVIEW_REQUIRED`. Un insieme troppo grande o una copertura non attestata
blocca la decisione automatica. La normalizzazione `TEXT_V1` applica NFKC,
spazi normalizzati e minuscole indipendenti dalla locale; `CONCEPT` confronta
l'identificatore esatto e `DECIMAL_V1` il valore numerico canonico. Cambiare
comparatore richiede una nuova versione.

## Ricerca dei candidati e costo

La ricerca deve partire da **ogni** proprietà canonica esposta dall'oggetto
in ingresso, con valore normalizzato, comparatore e versione semantica. Un
indice inverso per `(tenant, classe, proprietà, versione semantica,
comparatore, valore)` restituisce gli ID degli oggetti attivi che condividono
almeno un valore. Si prende l'unione deduplicata di questi ID e si caricano
solo i relativi oggetti completi per il confronto. Una sola proprietà come
seme non basta: può cambiare posizione o indirizzo mentre un'altra proprietà
resta uguale. Il grafo UDP fornisce gli oggetti e le relazioni, ma esplorare
archi senza un indice dei valori non rende questa ricerca selettiva.

Una corrispondenza completa su uno dei due insiemi di campi implica almeno
un valore comune, quindi quel candidato appartiene necessariamente all'unione.
Se non si trovano ID e la copertura è attestata per i valori e per le forme dei
campi di tutti gli oggetti nel perimetro, gli oggetti confrontabili sono
distinti per la regola corrente. I valori mancanti, non confrontabili,
con versione semantica diversa o non indicizzati richiedono una verifica di
copertura e, se possono incidere sull'identità, revisione umana. Quando
l'unione supera `maxCandidates`, non si tronca per dichiarare un nuovo oggetto:
si apre un caso troppo ampio. Il costo della ricerca ordinaria dipende dalle
ricerche indicizzate e dai candidati trovati, non da `m × n` oggetti.

La migrazione `V23` prepara l'indice inverso dei valori, un indice delle
forme dei campi e una tabella di attestazione per tenant, classe e versione
della policy. La lettura usa una sola snapshot SQL: unisce i candidati che
condividono un valore a quelli con una forma dei campi diversa, cercati nei
due intervalli indicizzati prima e dopo l'impronta della forma in ingresso.
Applica il limite e carica le
proprietà complete solo per quegli ID. Le mutazioni di `urban_object`, della
proiezione corrente, dei valori correnti o dei token invalidano
automaticamente l'attestazione. La materializzazione acquisisce il lock del
perimetro prima di modificare la proiezione. La precedente scansione della
classe è stata rimossa. **Nessun processo di produzione pubblica ancora
attestazioni complete**: senza un'attestazione il motore produce soltanto
`REVIEW_REQUIRED`. `GovernedIdentityIndexBackfill.rebuild` offre una scansione
**una tantum** in preattivazione sotto il lock degli scrittori: ricostruisce
token e forme e attesta soltanto oggetti con proprietà correnti complete e
valori scalari confrontabili per i campi dichiarati nella policy. Un oggetto
senza revisione, una proprietà non rappresentata nella revisione o un valore
non confrontabile per una proprietà dichiarata fa fallire la transazione. Il test invoca il
backfill come fixture; nessun flusso di produzione lo invoca ancora.

Restano da collegare il backfill al preflight governato e da validare le
policy pubblicate. Occorre anche mantenere i token
atomicamente con le revisioni correnti, con i nuovi oggetti non ancora
materializzati, con merge/split e con le rimozioni. Una mutazione non coperta
deve lasciare la classe non attestata. Solo allora la verifica di copertura
può essere pubblicata senza ripetere una scansione completa a ogni handoff.
Le forme eterogenee sono indicizzate separatamente: un oggetto senza valori
uguali, ma con campi in più o in meno, resta un candidato incerto. Se questi
candidati superano `maxCandidates`, la risposta è `RESOLUTION_TOO_BROAD`;
il limite non viene aggirato con una scansione della classe. Il backfill
registra anche il numero degli oggetti verificati: con **zero** oggetti, la
copertura è valida per qualunque sottoinsieme non vuoto di campi e consente
la prima creazione autorizzata. L'inserimento invalida subito l'attestazione.

La forma attuale del contratto mantiene per compatibilità i nomi `signals` e
`sufficientRules`, ma rifiuta `uniqueWithinScope: true`,
`excludesOnDisagreement: true`, regole su sottoinsiemi e proprietà mappate non
comprese nel confronto. `assertionRef` della regola è una traccia della policy
approvata, non una dichiarazione di unicità di un campo.

## Revisione umana e attivazione

L'adapter persiste decisione, prove, candidati, versione e copertura in
`resolution_issue`. Il chatbot deve poter mostrare **tutti** i casi aperti
accessibili al tenant in una tabella con indizi, candidati e soluzione proposta.
L'utente può modificare le scelte; THS presenta l'intero pacchetto congelato
con versioni degli issue e impronta. Solo l'umano autenticato può approvare
l'intero pacchetto, in una transazione: se un caso, il set dei casi aperti o
una prova è cambiata, la conferma fallisce e si prepara un nuovo pacchetto.
Un caso senza proposta utilizzabile resta nel pacchetto e richiede una scelta
esplicita; la revisione non deve trasformare un'ipotesi in una decisione
silenziosa. L'endpoint attuale decide un issue alla volta: lista, proposta
modificabile e conferma atomica sono ancora da implementare.

Il percorso governed è preparato ma non collegato a `PublishedResolutionLoop`:
la gate di attivazione resta chiusa. Occorre attestare la completezza dei
candidati anche su classi grandi, verificare il confronto di tutti i valori
canonici esposti (comprese proprietà multivalore e geometrie), versionare la policy,
validare la copertura nel preflight e definire la provenienza del tenant per
*ogni* issue prima di esporre una lista multi-tenant. Il controllo attuale
confronta i valori scalari mappati; non equivale ancora a un confronto completo
di ogni possibile struttura canonica. Le regole di autorità dei valori e la
riapertura degli issue con prove mutate rimangono gate di integrazione.
