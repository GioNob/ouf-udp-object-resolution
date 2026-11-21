# UDP V37 e ritorno al predecessore

Il predecessore supportato è `c0cf1702b2eb971718996a959414caee004af494` su V36. Il ritorno riavvia la stessa immagine con Flyway e la validazione attivi e con `*:future`. Lo schema, la storia Flyway, gli oggetti nel lake e le scritture del candidato restano alla V37.

| Area | Contratto del ponte |
|---|---|
| Lake | La V36 usa le cinque colonne tenant, tier, hash, sorgente e tipo sia per `ON CONFLICT` sia per `find().single()`. La V37 conserva il vincolo e aggiunge il numero di copia immutabile, con default zero, e l'arbitro a sei colonne. Il ponte inserisce solo zero. Il locator di copia zero mantiene il formato V36 anche se il processo si ferma subito dopo lo stage. |
| Riutilizzo RAW | Un nuovo invio dei byte verificati ritrova lo stesso `lake://`. Per una copia `COMPACTED`, `FAILED` o `QUARANTINED` compatibile con la policy, un nuovo upload riceve HTTP 409 con `code=UDP_LAKE_COPY_RECOVERY_PENDING_CONTRACT`. Restano disponibili il riferimento originale, le evidenze e il percorso di verifica del manifest per gli handoff. |
| Revisioni | Il ciclo A→B→A richiede due occorrenze della stessa coppia di hash. La V36 confronta gli hash con il solo stato corrente, alloca il numero successivo per oggetto e ricostruisce le revisioni per ID. Restano la PK, l'unicità per oggetto e numero di revisione e tutti i riferimenti alle revisioni. |
| Chiavi canoniche | L'indice attivo per tenant si aggiunge all'unicità globale V1. Una chiave riservata da un oggetto ritirato o di un altro tenant può portare a una review senza candidati esposti. |
| Review | La vista delle issue prive di tenant è riservata. Le letture V36 continuano sulle tabelle originarie. Il flag di rivalutazione ha default falso. Quando viene attivato, il trigger archivia i candidati e le evidenze precedenti, svuota le scelte e ritira la prova di completezza. Anche la V36 vede così una review aperta senza una scelta obsoleta approvabile. Se la V36 riporta il job in quarantena, al successivo avvio del ponte un HUMAN può richiedere nuovamente la rivalutazione con la versione corrente. |
| Intake | La riparazione porta a `PROCESSED` solo un intake fallito per cui coincidono job riuscito, timestamp di elaborazione, osservazione con revisione ed evento di completamento. Aggiunge un evento di riconciliazione e conserva gli ID originari. |

## Prova di ritorno

Il job `predecessor-return` di `module-ci.yml` costruisce le immagini dai due alberi Git e verifica le etichette OCI. Su PostgreSQL 17/PostGIS e MinIO dedicati, la V36 crea la revisione A. Il candidato produce B e una seconda A. Dopo il riavvio, il predecessore legge via API la storia 3/2/1 e i valori A/B/A, riusa il RAW del candidato e materializza C sullo stesso oggetto. Entrambe le versioni ripetono upload e richieste di intake, anche concorrenti, e riprendono un job dopo un 503 della pubblicazione. Solo issuer, grant e pubblicazioni sono fixture locali al runner. Le scritture transitano per le API autenticate e il worker del modulo.

Un job riuscito conserva gli ID delle immagini con i rispettivi SHA, la storia e i checksum Flyway prima e dopo il ritorno, gli eventi di pausa e ripresa e l'esito. Il parametro `predecessor` del dispatch accetta uno SHA completo. Per una nuova coppia si aggiornano il default, le versioni attese e i casi che esercitano i nuovi stati dei dati.

## V38 e D-080

Al task-19 restano la rimozione dell'arbitro lake a cinque colonne, le copie fisiche successive e il passaggio all'unicità canonica parziale come unico vincolo sulla chiave. La V38 deve provare il ritorno all'immagine del ponte con copie successive e chiavi riutilizzate già presenti, verificando i riferimenti e il lineage.

Per D-080 ogni task con migrazioni dichiara il predecessore in esercizio, le sue dipendenze SQL e gli stati che il candidato può scrivere. Rimozioni e restrizioni attese dal predecessore slittano al rilascio successivo. La prova su dati popolati deve riuscire prima di dichiarare la coppia compatibile.
