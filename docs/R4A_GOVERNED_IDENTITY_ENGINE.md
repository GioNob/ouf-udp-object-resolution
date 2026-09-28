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
Una policy di una classe e una fonte dichiara la proiezione completa delle
proprietà canoniche mappate, con versione semantica e comparatore per ciascuna.
La sola regola sufficiente comprende **tutte** queste proprietà: una coincidenza
parziale non è una corrispondenza automatica. Con quattro campi, tutti e quattro
devono essere presenti, semanticamente compatibili e uguali ai corrispondenti
campi di un oggetto attivo. Una sola corrispondenza completa identifica lo stesso
oggetto; due corrispondenze complete richiedono revisione. Campi mancanti,
versioni semantiche diverse o valori discordanti restano evidenza per l'umano;
nessuna differenza esclude da sola un candidato.

Senza candidati nel perimetro completo e verificato si può creare un oggetto
solo quando la policy della fonte abilita `allowAutoNew`. Con almeno un
candidato ma senza un'unica uguaglianza completa, il risultato è
`REVIEW_REQUIRED`. Un insieme troppo grande o una copertura non attestata
blocca la decisione automatica. La normalizzazione `TEXT_V1` applica NFKC,
spazi normalizzati e minuscole indipendenti dalla locale; `CONCEPT` confronta
l'identificatore esatto e `DECIMAL_V1` il valore numerico canonico. Cambiare
comparatore richiede una nuova versione.

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
canonici (comprese proprietà multivalore e geometrie), versionare la policy,
validare la copertura nel preflight e definire la provenienza del tenant per
*ogni* issue prima di esporre una lista multi-tenant. Il controllo attuale
confronta i valori scalari mappati; non equivale ancora a un confronto completo
di ogni possibile struttura canonica. Le regole di autorità dei valori e la
riapertura degli issue con prove mutate rimangono gate di integrazione.
