# Osservazioni delle sorgenti API (V40, `ouf.delta-position.v1`)

Task-61i. Contratto vincolante: `ouf.delta-position.v1` (revisione 2, §2, §7, §9, §10; revisione 3, §14). Le sorgenti file non cambiano: senza `apiObservation` intake, claim, materializzazione e serving restano quelli della V39.

## Intake (RC3, estensione compatibile)
- `apiObservation` è un campo tipizzato della radice di `handoff-payload-v1.json` (`additionalProperties: true` già prima: il predecessore lo ignora senza errori).
- Con `apiObservation`: `sourceRevision` obbligatorio (decimale, 1…2⁶³−1), `observedAt` obbligatorio, `operation` solo `UPSERT` o `TOMBSTONE`, `TOMBSTONE` senza `canonicalPayload`, `changeRepresentation.mode` = `FULL_SNAPSHOT`, `eventSequence` = `generation`. `PRESENCE` è un `UPSERT`; `SNAPSHOT_DELETE` e `ABSENCE` sono `TOMBSTONE`; per `ABSENCE` `observedAt` = `snapshotAt`.
- `sourceObjectId` esatto: vuoto, oltre 256 code point o con caratteri di controllo → `422 UDP_CONTRACT_INVALID`.
- Il tenant dell'installazione (`ouf.udp.lake.tenant-id`) è obbligatorio; un `tenantId` diverso nell'handoff → `UDP_API_TENANT_MISMATCH`.
- Stesso `handoffId` e stesso JSON → stessa ricevuta, come oggi.

## Regole R1–R14 (`ApiObservation.decide`, `ApiSourceObjects`)
Il worker pubblicato (`PublishedResolutionLoop`) valuta l'osservazione **prima** della risoluzione d'identità e della geometria, nella stessa transazione che scrive esito, `api_source_object`, contributi, binding e proiezioni:
1. lock consultivo per (tenant, sorgente): serializza gli esiti della sorgente, così `position` cresce nell'ordine di commit;
2. blocco `EVENT_CONFLICT` della sorgente (`api_source_block`) → quarantena `UDP_EVENT_CONFLICT`;
3. lock dello scope d'identità e del binding (`GovernedIdentityScopeLock`), poi `select … for update` dello stato per oggetto sorgente; un'epoca diversa → quarantena `UDP_SOURCE_EPOCH_CHANGED`;
4. confronto con gli esiti già registrati per la stessa chiave d'osservazione e la stessa resa (R2/R4, anche per revisioni minori) e con la stessa revisione in un'altra resa (R3);
5. decisione e applicazione.

| Regola | Esito | Effetto in UDP |
|---|---|---|
| R1 | `APPLIED` | Risoluzione d'identità ordinaria (D-059) |
| R2 | `DUPLICATE` | Nessuno |
| R3 | `DUPLICATE_REPUBLISHED` | Nessuno: lo storico non si rimappa (D-093 p.8) |
| R4 | `CONFLICT` | Prova (`conflict_with`), blocco della sorgente, quarantena |
| R5 | `SUPERSEDED` | Nessuno; resta in lineage |
| R6 | `APPLIED` | Nuova revisione sullo stesso UUID |
| R7 | `RETIRED` | Contributi della sorgente allo storico (`api_withdrawn_support`); proprietà, geometrie, archi spaziali e relazioni ricalcolati dai sostegni superstiti; senza sostegni l'oggetto è `RETIRED` |
| R8 | `REACTIVATED` | Stesso UUID, binding riattivato |
| R9 | `SEEN` | Solo l'ultima generazione vista |
| R10 | `RETIRED` | Come R7, con `retiredBy=ABSENCE` e `absenceSnapshotAt` |
| R11 | `REACTIVATED` | Presenza in uno snapshot successivo |
| R12 | `REACTIVATED` o `SUPERSEDED` | `changedAt` > `absenceSnapshotAt`, confronto stretto alla precisione della fonte |
| R13 | `DUPLICATE` | `lastRevision` avanza |
| R14 | `RETIRED` | Solo lo stato, nessun UUID; un `UPSERT` successivo segue R1 |

**Proprietà tolta dal mapping (D-093 p.7):** a ogni revisione `APPLIED` o `REACTIVATED` i contributi precedenti dello stesso oggetto sorgente passano allo storico. Una proprietà non più mappata resta corrente solo se un altro sostegno corrente la sostiene; la regola usata per ricalcolarla è quella della pubblicazione che l'aveva mappata (`api_property_profile`). L'intervallo bitemporale della proprietà si chiude.

**Serving:** un oggetto `RETIRED` da sorgenti API resta risolvibile per UUID con il suo stato, senza proprietà né geometria correnti; è fuori da ricerca, spazio e relazioni correnti. La storia mostra ogni revisione, con `status` per le revisioni API.

## Esiti per handoff (§14)
- `GET /api/internal/v1/handoffs/{handoffId}`: `outcome` solo quando definitivo.
- `GET /api/internal/v1/handoffs/outcomes?sourceId=&ingestionRunId=&after=&limit=`: pagina `ouf.udp-handoff-outcomes.v1`, `limit` 1–200, ordinata per `position`.
- Autorizzazione: `SERVICE` del tenant con `udp.candidate.write` sullo scope sorgente e run, e principal = `ouf.udp.ingestion-workload`.

## D-080: rilascio ponte V39 ↔ V40
- **Additivo:** solo tabelle, una vista, colonne con default e un trigger. Nessuna colonna esistente cambia.
- **Quando i worker vecchi smettono di reclamare i job nuovi:** subito, dal commit della V40. Il trigger `fence_api_dispatch` porta ogni job con `apiObservation` fuori dal predicato di claim della V39: `READY` diventa `PAUSED` senza `next_integrity_check_at`, un `RUNNING` ha `lease_until = infinity`; la scadenza vera sta in `api_dispatch_at`, che legge solo `claimPublished` della V40. Vale anche per gli handoff ammessi da un'immagine V39 dopo un ritorno: restano parcheggiati e la V40 li adotta al ritorno (`api_tenant_id` del tenant dell'installazione).
- **Cosa ignora il predecessore:** `apiObservation` e le tabelle `api_*`. Su un database V40 non processa mai un job API; i job file restano suoi.
- **Storia esistente:** gli handoff già processati con `apiObservation` prima della V40 restano con semantica file (`api_semantics=false`).
- **Prova:** `scripts/ci/predecessor-return.sh` (V39 → V40 → V39 → V40 sullo stesso database, job pendente, job ammesso dalla V39, ripresa) e `ApiObservationRuntimeTest#d080*` con il predicato di claim letterale della V39.

## Limiti dichiarati (fuori da questo task)
- La decisione HUMAN che sblocca `EVENT_CONFLICT` e quella su `SOURCE_EPOCH_CHANGED` (archivio dello stato della vecchia epoca) non hanno ancora un'operazione governata: i job restano in quarantena.
- Il ritiro governato degli oggetti che la fonte non invierà più dopo un cambio di mapping è un'operazione della pubblicazione (V4).
