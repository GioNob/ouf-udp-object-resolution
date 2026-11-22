# Compatibilità UDP e ritorno al predecessore

Dal task-51c la coppia D-080 parte da `e18f703263c5e3727f7e623cd59cbfcde3ed3d7d` (schema 39). Il candidato usa lo stesso schema. La prova A/B/A conserva il database popolato, registra GET/POST/GET e verifica la storia Flyway al ritorno. Il checksum Flyway fissato per V39 vale `-1862540699`.

La prima qualifica V38 usava il ponte V37 `7809a0fdf6bd70cf02e15a4630f8fe9cd59695be` con Flyway attivo, validazione e `*:future`. Il task-41 usava il predecessore V38 `ebc5a3221a9071b158a233441abf4db99270fee3`.

## Dipendenze verificate sul ponte

| Oggetto | Scritture e letture a `7809a0fd` | Contratto V38 |
|---|---|---|
| `lake_object` | `LakeCatalogRepository.stage` propone zero con `ON CONFLICT(tenant_id,tier,content_hash,source_id,type_code,copy_no)`. In caso di conflitto seleziona la copia più recente con `ORDER BY copy_no DESC LIMIT 1 FOR UPDATE`. `get`, gli handoff e i manifest identificano la copia per UUID. Il servizio riprende gli stati `STAGED` e `DURABLE` usando il locator del catalogo. | Cade il vincolo a cinque colonne. Restano l'arbitro a sei colonne e l'immutabilità di `copy_no`. Anche dopo il ritorno un upload può ritrovare una copia successiva verificata. Se l'ultima RAW richiede a sua volta recupero, il ponte restituisce il proprio HTTP 409. |
| `urban_object` | `ObjectResolutionService.resolve` inserisce con `ON CONFLICT DO NOTHING` senza nominare un arbitro. Legge una lista di candidati per tenant, tipo e stato `ACTIVE`. Il controllo globale `reservedKey` usa `SELECT EXISTS`, che restituisce un booleano anche con più occorrenze. | Cade il vincolo globale. L'indice parziale V37 impone l'unicità della chiave canonica tra gli oggetti attivi dello stesso tenant e tipo. Una chiave di un oggetto ritirato o di un altro tenant è riutilizzabile. Durante una rivalutazione senza candidati il ponte può ancora chiedere review per una chiave presente fuori ambito. |

## Recupero RAW

Un nuovo invio con la stessa identità di contenuto prende il lock consultivo transazionale condiviso con il ponte. Sotto lock, `stage` controlla media type, dimensione, classe di retention ed etichetta di accesso dell'ultima copia. Per una RAW `COMPACTED`, `FAILED` o `QUARANTINED` alloca il numero successivo, un UUID e un locator fisico distinto. La verifica dei byte avviene dopo lo stage. Gli invii successivi riusano la copia verificata. I riferimenti `lake://` e i manifest preesistenti continuano a identificare le copie originali.

## Prova CI su PostgreSQL 17 e MinIO

1. Il ponte materializza A su V37. A processo fermo, la fixture danneggia i byte del RAW sorgente e inserisce una chiave canonica ritirata nel database di prova.
2. Nel primo passaggio dal ponte il candidato migra a V38. Nella coppia corrente entrambi avviano V39. La riconciliazione registra la quarantena della copia zero. Il candidato materializza B e di nuovo A. Due upload concorrenti di A devono ottenere la stessa copia uno. Un altro handoff deve materializzare un nuovo oggetto attivo con la chiave ritirata.
3. Il ponte riavviato legge la storia A/B/A, ripete l'upload di A e deve restituire l'UUID della copia uno. Materializza C, scrive e riusa la sua copia zero, e risolve la chiave riutilizzata da un nuovo record sorgente. Il job verifica i retry dopo un 429 con Retry-After per entrambe le immagini.
4. Le asserzioni confrontano UUID, numeri di copia, locator, stato, versione e contatori di lineage per gli handoff A, oltre ai byte danneggiati in MinIO e alla storia Flyway. Le ricevute del job sono in `target/predecessor-return/`.
