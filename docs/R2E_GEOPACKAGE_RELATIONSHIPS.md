# R2e — relazioni fra feature GeoPackage

Baseline: Reality Package v1.7, PET UDP v1.3, PET Onboarding v1.6 e Ingestion v1.3; tutti i sette PET e i documenti L0 sono stati consultati. [Allineamento obbligatorio dello sprint](https://github.com/GioNob/ouf-semantic-registry/blob/main/docs/OUF_SPRINT_PET_ALIGNMENT.md).

## Profilo pubblicato

UDP consuma `extractionProfile.runtime.udp.relationships`, con `policyRef` e una lista di regole. Ogni regola dichiara `sourceField` (proprietà canonica già mappata), `relationIri`, `targetCanonicalType`, `targetPropertyIri`, `resolutionStrategy: CANONICAL_KEY`, `onNoMatch: QUARANTINE_RELATION`, `accessLabel`, `selfLoopAllowed`.

Il resolver verifica il legame con `relationshipMappings`: campo sorgente attraverso il property mapping, relazione, classe target, `resolution.targetKeyProperty`, strategia e `onMultipleMatches: REVIEW_REQUIRED`. Il `mappingId` deve essere incluso in `runtime.execution.relationshipResolutionStrategyRefs`. La label della relazione deve coincidere con la policy di scope `RELATIONSHIP`. Tutto appartiene al bundle storico verificato per hash; non viene cercata la configurazione ACTIVE durante l'esecuzione.

R2e usa una chiave esplicita, normalizzata con trim/case folding dalla strategia corrente. Nessun nearest-neighbour, scelta del primo candidato o creazione implicita dell'armadio. Il match è circoscritto al tenant della telecamera e agli oggetti target ACTIVE; la ricerca restituisce al massimo due candidati, sufficienti a distinguere unicità e ambiguità.

## Persistenza e riconciliazione

La materializzazione canonica e l'ammissione della riconciliazione avvengono nella stessa transazione del job. Ogni task conserva handoff, identità della fonte e profilo originario, protetti da trigger di immutabilità. L'aggiornamento della stessa identità disattiva il task precedente e i suoi supporti correnti; le evidenze e le revisioni rimangono.

Il worker, attivo solo con `ouf.udp.execution.enabled=true`, esamina al massimo 10 task per passaggio, con poll di 5 secondi e rinvio di 30 secondi per task. Il backlog può aumentare la latenza; non è una garanzia temporale di disponibilità. Non effettua chiamate remote dentro la transazione. Sono ammessi al massimo 64 regole e 256 valori complessivi per record; le query di materializzazione hanno timeout di 5 secondi.

Un target assente crea una issue `NO_MATCH`; se arriva successivamente il collegamento viene risolto con la regola originale. Più candidati creano `MULTIPLE_MATCHES`: l'issue resta aperta per revisione, senza risoluzione automatica al successivo tick. Un riferimento cambiato o non più risolvibile ritira il vecchio edge corrente. Il retry della stessa evidenza non aggiunge revisioni duplicate. UI e remediation completa delle issue restano R3/R4a.

## Navigazione autorizzata

`GET /api/udp/v1/objects/{id}/relationships?direction=OUTBOUND` restituisce i collegamenti dalla telecamera. `direction=INBOUND` permette di navigare dall'armadio alle telecamere, usando gli stessi ID delle relazioni. Default OUTBOUND; page size massimo 100. Altre direzioni sono rigettate.

Sono verificati tenant, permesso sull'oggetto di partenza, label/permesso della relazione e permesso sull'altro oggetto. Oggetti target non attivi e relazioni non attive sono omessi. Non vengono esposti conteggi o cursori che rivelino edge nascosti.

Anche la geometria sorgente contenuta nella proprietà mappata richiede `urban.geometry.read` oltre al permesso e alla label della proprietà, in current, history e search.

La proprietà geometrica mappata conserva l'envelope sorgente `{crs, geoJson}`; `canonicalGeometry`, `geometry` e `geometryProvenance` mantengono il contratto R2d. Il CRS comunale è una configurazione del deployment; gli esempi di R2e usano 4326 senza trasformazioni territoriali. Le prove 6708, assi e grigliati restano nella suite R2d.

## Prove

`RelationshipMaterializerRuntimeTest`: target tardivo, immutabilità del profilo, idempotenza, cambio riferimento e ritiro dell'edge ambiguo. `PublishedRelationshipBindingTest`: rifiuto di key, label, mapping e strategia non allineati. `GovernedServingRuntimeTest`: direzioni e omissione delle relazioni riservate. La CI dedicata di Ingestion usa quattro JVM owner, PostgreSQL/PostGIS e MinIO; Gateway e identità sono fixture dichiarate. Evidenze finali nel repository Semantic Registry.
