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
oggetto può esporne un sottoinsieme diverso. L'uguaglianza dei campi
dell'oggetto con meno proprietà è una possibile strategia, non una prova
universale: per un MATCH automatico tutti questi valori devono concordare e
i campi comuni devono includere tutti i segnali di almeno una regola
sufficiente della policy approvata. Vale in entrambe le direzioni. Se una
regola richiede nome e indirizzo, un ingresso che espone soltanto il nome
non basta, anche se coincide. Un'eventuale regola che dichiari sufficiente
il solo nome richiede giustificazione e approvazione esplicite.
Se entrambi espongono campi propri che mancano nell'altro, o se alcuni campi
comuni concordano e altri differiscono, il caso resta incerto. L'insieme
confrontato non può essere vuoto e un MATCH richiede un unico candidato
che soddisfi la regola. Nessuna proprietà è assunta come identificatore stabile.
Un candidato è distinto secondo questa strategia quando esiste almeno un
campo comune confrontabile e **tutti i campi comuni** sono diversi, anche
se uno dei due oggetti ha campi aggiuntivi: `{nome:A}` e
`{nome:B, indirizzo:X}` sono distinti. Il PET non rende questa conclusione
una legge universale per ogni strategia e contesto temporale.
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
comparatore richiede una nuova versione. `JSON_V1` canonizza oggetti e liste
JSON (chiavi ordinate, ordine delle liste conservato, numeri normalizzati).

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
della policy. `V24` aggiunge un catalogo dei **distinti insiemi di campi**.
La lettura usa una sola snapshot SQL: unisce i candidati che condividono un
valore a quelli la cui forma non ha **alcun** campo comune, selezionando
prima le forme nel catalogo e poi gli ID tramite indice. Una forma diversa
che condivide campi, ma nessun valore, è distinta secondo la regola
governata e non richiede il caricamento degli oggetti corrispondenti.
Applica il limite e carica le
proprietà complete solo per quegli ID. Le mutazioni di `urban_object`, della
proiezione corrente, dei valori correnti o dei token invalidano
automaticamente l'attestazione. `V26` serializza l'invalidazione con il lock
di ambito anche per le scritture su oggetti, token e forme. La materializzazione acquisisce il lock del
perimetro prima di modificare la proiezione. La precedente scansione della
classe è stata rimossa. Senza un'attestazione il motore produce soltanto
`REVIEW_REQUIRED`. `GovernedIdentityIndexBackfill.rebuild` offre una scansione
**una tantum** in preattivazione sotto il lock degli scrittori: ricostruisce
token e forme e attesta soltanto oggetti con proprietà correnti complete e
valori confrontabili, inclusi JSON strutturati con `JSON_V1`, per i campi dichiarati nella policy. Un oggetto
senza revisione, una proprietà non rappresentata nella revisione o un valore
non confrontabile per una proprietà dichiarata fa fallire la transazione. Il
preflight HUMAN `POST /api/udp/v1/governance/identity/preflight` lo invoca
esplicitamente per una configurazione congelata con hash verificato. L'attestazione
persiste tenant, fonte, policy, fingerprint e coverage ref; il GET interno
autorizzato per Onboarding la considera valida solo finché la copertura resta completa.

Il preflight deve ancora essere eseguito con una policy concreta approvata
nel lab. `refreshOne` mantiene atomicamente token e forme di un
oggetto materializzato dopo una ricerca completa nella stessa transazione;
il worker lo invoca nel percorso governed dopo la materializzazione. Merge/split, rimozioni e altre mutazioni
fuori da quel percorso richiedono riconciliazione. Una mutazione non coperta
deve lasciare la classe non attestata. Solo allora la verifica di copertura
può essere pubblicata senza ripetere una scansione completa a ogni handoff.
Le forme disgiunte sono indicizzate separatamente: un oggetto senza valori
uguali e senza campi comuni resta un candidato incerto. Se questi candidati
superano `maxCandidates`, la risposta è `RESOLUTION_TOO_BROAD`;
il limite non viene aggirato con una scansione della classe. Il backfill
registra anche il numero degli oggetti verificati: con **zero** oggetti, la
copertura è valida per qualunque sottoinsieme non vuoto di campi e consente
la prima creazione autorizzata. L'inserimento invalida subito l'attestazione.

La forma attuale del contratto mantiene per compatibilità i nomi `signals` e
`sufficientRules`. Ogni regola dichiara un sottoinsieme non vuoto dei segnali
mappati, tutti richiesti fra i campi comuni, oltre all'uguaglianza di tutti
i campi dell'oggetto più piccolo. Rifiuta ancora `uniqueWithinScope: true`,
`excludesOnDisagreement: true` e proprietà mappate non comprese nel confronto.
`allowAutoNew` dichiara la creazione autorizzata per una fonte e per la prova
di distinzione prevista da questa strategia. `assertionRef` è una traccia
della giustificazione, non una prova verificata automaticamente; il gate di
pubblicazione resta chiuso fino all'approvazione e all'integrazione runtime.
Un source binding ACTIVE valido conserva la continuità canonica anche se
cambiano proprietà; authority e conflitti dei valori si valutano separatamente.

## Revisione umana e attivazione

L'adapter persiste decisione, prove, candidati, versione e copertura in
`resolution_issue`. La migrazione `V25` attribuisce il tenant ai nuovi casi;
per i casi storici lo ricava solo quando **tutti** i candidati appartengono
allo stesso tenant. Un caso storico ancora senza tenant blocca l'intero
pacchetto, senza esporre dati di un altro tenant: richiede un backfill
governato prima dell'uso della nuova API.

`GET /api/udp/v1/governance/resolution/issues/package` prepara l'elenco
integrale dei casi aperti del tenant con versioni, evidenze, candidati e
un suggerimento quando vi è un solo candidato selezionabile. Il chatbot può
mostrare la tabella e l'utente può modificare le scelte. La conferma HUMAN
via `POST /api/udp/v1/governance/resolution/issues/package/confirm` esige
l'impronta dello snapshot e una decisione esplicita per ogni issue, ricontrolla
l'insieme e le versioni sotto lock e committa tutte le decisioni nella stessa
transazione. Una scelta errata o una prova mutata annulla l'intero pacchetto;
le conferme sono registrate append-only. Nuove issue arrivate **dopo** la
lettura sotto lock appartengono al successivo pacchetto; non esiste un lock
globale sulle ingestion del tenant.

Il deployment UDP governa le richieste HUMAN e SERVICE tramite bearer IAM
validato localmente (issuer, audience, scadenza, tenant, actor type, subject,
scope e service principal). La bridge lega il `TrustedPrincipal` al request
server-side consumato dall'Authorization SDK; header di identità forniti dal
client non diventano principal. Nel profilo lab impostare
`OUF_UDP_IAM_ENABLED=true`, `OUF_UDP_IAM_ISSUER` all'issuer IAM effettivo e
`OUF_UDP_IAM_AUDIENCE` all'audience Gateway. Senza questi valori il nuovo
container non è pronto per preflight/attestazione; l'immagine staged precedente
al filtro IAM non è distribuibile per lo smoke autenticato. Verificare 401
senza bearer e il percorso positivo con token HUMAN/SERVICE distinti prima
dell'attivazione di una fonte.

La THS Onboarding prepara una pagina trusted condizionata alla configurazione
del Gateway; il percorso browser→Gateway→UDP e i claim IAM richiedono ancora
collaudo reale. Il chatbot/MCP espone una proiezione autorizzata e minimizzata
della tabella. Per una issue governed con copertura completa, l'utente può
scegliere `APPROVE` su un candidato oppure `CREATE_NEW` senza target. La seconda
scelta viene registrata nel pacchetto atomico, ma l'oggetto viene creato solo
nella transazione del worker, insieme alla materializzazione e all'aggiornamento
dell'indice. La copertura deve essere ancora quella della revisione e restare
completa sia alla conferma sia all'esecuzione. Se una nuova copertura completa
sostituisce quella confermata, UDP riapre il caso con versione ed evidenze
aggiornate, preservando append-only le precedenti decisioni umane: l'utente
deve confermare un nuovo pacchetto. Se la copertura è incompleta, il worker
non crea alcun oggetto e richiede prima un nuovo preflight e retry del job.
`DISMISS` chiude il caso senza creare l'oggetto.

Il percorso governed è collegato a `PublishedResolutionLoop`; Onboarding
interroga la copertura attuale attraverso Gateway prima di attivarlo. Senza
route, credenziale SERVICE e attestazione completa la gate resta chiusa.
Occorre attestare la completezza dei
candidati anche su classi grandi, verificare il confronto di tutti i valori
canonici esposti (comprese proprietà multivalore e geometrie), versionare la policy,
validare la copertura nel preflight e riconciliare le issue storiche che
non hanno ancora un tenant attestato. Il controllo attuale confronta anche i
valori JSON strutturati mappati, ma richiede verifica per ogni tipo canonico
e policy concreta. Le regole di autorità dei valori e la
riapertura degli issue con prove mutate rimangono gate di integrazione.
