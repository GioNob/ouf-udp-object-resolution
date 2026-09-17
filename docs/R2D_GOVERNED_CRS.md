# R2d — CRS comunale e trasformazioni governate

Autorità: PET UDP v1.3 §29.1; PET Onboarding v1.6 ONB-A11; PET Ingestion v1.3 ING-A09 (copertura parziale). Baseline package v1.7 e requisiti GIS concordati nella roadmap OUF. R2d fornisce le fondazioni; GeoPackage → feature/oggetti/relazioni è R2e.

## Risultato per l'operatore

La configurazione della fonte dichiara CRS sorgente, ordine delle coordinate e CRS comunale. La scheda di approvazione Onboarding espone `spatialDecision` e `spatialDecisionFindings`: scelta CONVERT/REJECT, operazioni sorgente→canonico e canonico→serving, accuratezza dichiarata o ignota, area, punti di controllo e risorse. La conferma usa il percorso HUMAN_USER già esistente e vincola l'intera configurazione tramite hash.

La decisione vive nel bundle pubblicato, quindi vale anche per le acquisizioni successive di una fonte dinamica. CRS sorgente cambiato, configurazione comunale diversa, versione PROJ cambiata, grigliato mancante o checksum diverso bloccano il lavoro. Una nuova scelta richiede un nuovo profilo approvato. Non è introdotto un prompt ripetuto per ogni feature.

Il ciclo automatico verifica la geometria prima di creare l'identità: rigetto o errore apre `spatial_resolution_issue`, mette il job in QUARANTINED con `UDP_SPATIAL_REVIEW_REQUIRED` e non crea oggetti vuoti. REJECT riguarda la discrepanza sorgente/comunale: il bundle può registrare una regola di rigetto, ma i record incompatibili non vengono materializzati. Non ripara automaticamente le geometrie.

La lettura owner dell'oggetto restituisce, sotto lo stesso controllo `urban.geometry.read` e DataAccessLabel:

- `geometry` + `geometryCrs`: rappresentazione di esposizione EPSG:4326, XY longitudine/latitudine;
- `canonicalGeometry`: `{crs, axisOrder: "XY", geoJson}`, nel CRS comunale;
- `geometryProvenance`: CRS sorgente, motore, operazioni approvate, accuratezza, risorse, contratto e lineage.

In assenza del permesso o del label, tutti questi campi sono omessi. L'originale CRS-tagged rimane nella revisione append-only `source_geometry_json`; i riferimenti al handoff/raw sono nel lineage. Le UI cartografiche e la presentazione guidata restano R4a; proiezione operativa e remediation completa restano R3.

## Configurazione e contratto

`OUF_UDP_CANONICAL_SRID=6708` configura il deployment comunale di Trieste. Il default 4326 mantiene il comportamento dei deployment preesistenti. Un altro Comune può impostare un altro EPSG noto. Nel modello attuale un deployment UDP ha il tenant configurato: non si introduce qui un registro CRS multi-tenant dinamico.

Il profilo approvato sta in `extractionProfile.runtime.udp.spatial`:

```json
{
  "policyRef": "crs-policy://municipality/source/version",
  "geometry": {
    "sourceField": "urn:geometry",
    "expectedSourceCrs": "EPSG:6708",
    "canonicalSrid": 6708,
    "normalizationVersion": "municipal-crs/1",
    "accessLabel": "RESTRICTED",
    "crsPolicy": {
      "sourceAxisOrder": "YX",
      "mismatchAction": "REJECT",
      "sourceOperation": null,
      "servingOperation": "REPLACE_WITH_AN_APPROVED_OPERATION_OBJECT"
    }
  },
  "relationships": []
}
```

Questo frammento illustra la struttura, **non è una configurazione eseguibile**: l'operazione di serving va sostituita con un oggetto verificato per il territorio. `sourceField` deve essere una proprietà canonica mappata e il suo label deve coincidere con la policy pubblicata. Le relazioni del profilo pubblicato sono per ora vuote: il loro binding semantico/autorizzativo è R2e.

`XY` significa coordinate x/easting/longitude, y/northing/latitude; `YX` significa input con ordine inverso. EPSG:6708 ha assi formali Nord–Est: per una sorgente che serializza realmente N,E usare YX. Il CRS da solo non determina come il produttore ha serializzato le coordinate. Dopo la normalizzazione, la geometria canonica usa sempre XY/E,N e lo dichiara. Il motore non indovina gli assi.

Ogni `CrsOperation` contiene:

| Campo | Significato |
|---|---|
| operationId, sourceSrid, targetSrid | Identità e direzione dell'operazione approvata |
| pipeline | Pipeline PROJ esplicita, applicata in direzione forward |
| projVersion | Versione esatta PROJ, primo token di `postgis_proj_version()` |
| accuracyMeters | Accuratezza dichiarata in metri; null significa ignota |
| accuracyStatement | Spiegazione dell'accuratezza, delle assunzioni e dei limiti |
| sourceBounds | xmin,ymin,xmax,ymax, nell'ordine XY e nelle unità del CRS sorgente |
| controlPoints | 2–20 punti distinti: sourceX/Y, targetX/Y, tolerance nelle **unità del CRS destinazione** |
| requiredGrids | Mappa nome-file → SHA256 esadecimale; `{}` se non necessari |
| resourceVersion | SHA256 prefissato del manifest esatto, obbligatorio se servono grigliati |

Il motore usa `ST_TransformPipeline`, non una rietichettatura SRID o una selezione automatica di operazione. L'area limita l'intera geometria. Non sono permessi grigliati opzionali `@`, fallback `null`, URL, `+init`, path esterni o operazioni non ammesse. Input limitato a 1 MiB di JSON geometrico, 20.000 vertici e geometrie 2D valide e non vuote. Non si dichiara supporto a quote, CRS dinamici/epoche o geocodifica.

La conversione tra datum va scelta e verificata da chi configura la fonte; i punti di controllo verificano l'esecuzione, non certificano da soli l'accuratezza geodetica. Le fixture di Trieste distinguono la proiezione GRS80 verso 6708 dall'assunzione di equivalenza RDN2008/WGS84 usata solo nel test di esposizione, esplicitamente di accuratezza ignota. Non usare tale assunzione per rilievi o per attestare precisione metrica.

## Grigliati effettivi e deployment

`deploy/postgis/Dockerfile` fornisce il wrapper del database. Prima dell'avvio verifica i byte dei grigliati e il manifest, poi imposta `ouf.proj_manifest_sha256`. Il runtime confronta questa attestazione del database, il manifest locale e la versione fissata nel profilo. `PROJ_NETWORK=OFF`; nessun download automatico. Il database necessita PostGIS >=3.4; la CI usa 17/3.5.

Montare **lo stesso pacchetto immutabile**, in sola lettura, a `/opt/ouf/proj` nel database. Montare lo stesso manifest in sola lettura nell'applicazione e impostare `OUF_UDP_GRID_MANIFEST` al suo percorso. Il chart applicativo accetta `runtime.gridManifestConfigMap`, nome di un ConfigMap immutabile contenente la chiave `manifest.json`: lo monta in sola lettura e configura il percorso automaticamente. Il CRS comunale va nel ConfigMap runtime come `OUF_UDP_CANONICAL_SRID`. Fissare anche il digest dell'immagine di database nel deployment reale. Non sovrascrivere file dentro un pacchetto già usato: creare una nuova versione e riavviare il database con il nuovo pacchetto. Gli amministratori del database e dell'infrastruttura fanno parte del confine trusted; l'attestazione non protegge da un amministratore che altera deliberatamente il server.

Il manifest JSON ha `schemaVersion: 1`, `grids: {"nome.gsb": "<64 hex>"}` e `metadata` per ciascun file con `version`, `licenseRef`, `provenanceRef`, `areaOfUse`. La presenza di una stringa licenseRef documenta una risorsa: non concede diritti d'uso e non sostituisce la verifica delle condizioni da parte del Comune. Il validatore rifiuta file assenti, byte difformi, symlink, nomi fuori formato e metadata mancanti. L'avvio fallisce prima che PostgreSQL accetti connessioni.

**Nessun grigliato IGM/locale è incluso o attestato.** La CI genera un NTv2 sintetico con uno spostamento noto esclusivamente per provare esecuzione, checksum, pinning e rigetto. Prima dell'uso a Trieste occorrono grigliati ottenuti legittimamente e punti di controllo territoriali indipendenti per la coppia CRS effettiva.

## Migrazione e verifica

V20 aggiunge `canonical_geometry` senza riscrivere revisioni storiche. La colonna `geometry` e gli indici geografia rimangono la rappresentazione 4326 di serving, quindi le query esistenti mantengono il loro contratto. Le revisioni precedenti possono avere canonical_geometry assente solo con canonical_srid=4326. Nuove revisioni conservano originale e trasformazione; modificare un profilo produce un hash diverso. Le relazioni spaziali esistenti sono limitate al tenant dell'oggetto sorgente.

La CI `r2d-governed-crs.yml` esegue PostGIS con il pacchetto montato in sola lettura, verifica il fallimento di avvio senza il file obbligatorio, e richiede che il test di griglia non sia skipped. Le suite verificano CRS comunale diverso, EPSG:6708 e assi, rifiuto/mancata decisione, area, limiti geometrici, versione PROJ, controllo errato, griglia mancante, griglia reale sintetica, pinning, idempotenza, originale, omissione autorizzata e ciclo automatico prima della creazione dell'oggetto. Nel test del ciclo automatico il resolver dei profili e il gate delle referenze sono fixture; database, job, trasformazione e materializzatori sono reali. L'acquisizione GeoPackage fra owner sarà verificata in R2e.

Fonti tecniche: [PostGIS ST_TransformPipeline](https://postgis.net/docs/ST_TransformPipeline.html), [PROJ pipeline](https://proj.org/en/stable/operations/pipeline.html), [PROJ resource files](https://proj.org/en/stable/resource_files.html).
