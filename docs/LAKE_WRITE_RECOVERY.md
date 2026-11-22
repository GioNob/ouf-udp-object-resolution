# Recupero delle scritture del lake (sprint-03, task-49)

`POST /api/internal/v1/lake/objects` cerca l'ultima copia per la stessa identità
di contenuto (`tenant`, `tier`, hash SHA-256, fonte e tipo). Un invio deve
rispettare media type, dimensione, classe di retention ed etichetta di accesso
della copia esistente.

Per `RAW` vale il contratto V38. Se l'ultima copia è `COMPACTED`, `FAILED` o
`QUARANTINED`, il nuovo invio crea la copia successiva con `copy_no + 1` e un
locator che include il nuovo UUID. Restano disponibili le evidenze della copia
precedente. Uno `STAGED` riprende sul proprio locator e un `VERIFIED` restituisce
il riferimento esistente.

## NORMALIZED e CURATED

Un nuovo invio identico e autorizzato recupera `FAILED` e `STAGED` nello stesso
UUID, numero di copia e locator. Il restage `FAILED` → `STAGED` verifica la
versione attesa e registra un evento `LAKE_OBJECT_STATE_CHANGED` con attore
`SYSTEM` e motivo `UDP_LAKE_IDENTICAL_CONTENT_RETRY`. L'invio ripete il `put`
con gli stessi byte e verifica la durabilità prima di rispondere `201`. Un
oggetto `DURABLE` completa la verifica in lettura. Una verifica interrotta
conserva `DURABLE`.

| HTTP | Campo JSON `code` | Condizione |
| --- | --- | --- |
| `503` | `UDP_LAKE_WRITE_CONTENDED` | Un'altra richiesta ha acquisito il tentativo o la versione è cambiata durante la scrittura. Il chiamante può ripetere lo stesso invio nel proprio budget. Se l'oggetto risulta già `VERIFIED`, si restituisce il suo riferimento. |
| `409` | `UDP_LAKE_OBJECT_QUARANTINED` | L'ultima copia è in quarantena o la verifica appena conclusa ha rilevato una prova mancante o incompatibile. |
| `409` | `UDP_LAKE_OBJECT_NOT_WRITABLE` | L'oggetto è in uno stato che blocca la scrittura, ad esempio `COMPACTED`, `COLD`, `DELETION_PENDING` o `DELETED`. |
| `409` | `UDP_LAKE_COPY_POLICY_CONFLICT` | L'invio propone metadati incompatibili con la copia esistente. |

Il tentativo ha una lease di cinque minuti, come il purge del lake. La lease
è associata alla versione e alla generazione del record. Le transazioni del
catalogo terminano prima dell'I/O sullo storage. Dopo un crash, si può
riprendere subito uno `STAGED` senza lease, oppure riprovare dopo la scadenza
di una lease già acquisita. Una lease scaduta impedisce la pubblicazione nel
catalogo. Un `put` già partito può terminare tardi sullo storage con gli stessi
byte e metadati. UDP esegue al massimo un tentativo applicativo per richiesta.
I chiamanti governano il budget di retry e ripresentano l'invio attraverso
l'autorizzazione prevista dall'endpoint.

## Quarantena

Un operatore HUMAN autorizzato con `lake.object.delete` può richiedere la
cancellazione tramite `POST /api/udp/v1/governance/lake/objects/{id}/deletion`
con `expectedVersion` e `reason`. Il guard richiede lo stesso tenant,
retention scaduta e assenza di legal hold, riferimenti di lineage bloccanti,
obblighi di replay e riferimenti export attivi. Il purge è differito e conserva
il record `DELETED`. Per `NORMALIZED` e `CURATED` il successivo invio della
stessa identità riceve `409 UDP_LAKE_OBJECT_NOT_WRITABLE`. Resta aperta la
definizione di un percorso HUMAN per ripristinare i byte e rendere di nuovo
utilizzabile la stessa identità.

## Ritorno a V38

V39 estende il trigger del catalogo con `FAILED` → `STAGED` per `NORMALIZED`
e `CURATED` a locator invariato. Le migrazioni V1–V38 restano identiche. Il job
`predecessor-return` avvia D-080 `ebc5a322` a schema 38, avvia il candidato a
schema 39, poi torna a `ebc5a322` sullo stesso database a schema 39 con
validazione Flyway `*:future`. Le asserzioni coprono la sostituzione RAW in
quarantena, la conservazione di byte e lineage e letture e scritture dopo il
ritorno. `LakeLifecycleRuntimeTest` copre separatamente il `409` per
`NORMALIZED` e `CURATED`.
