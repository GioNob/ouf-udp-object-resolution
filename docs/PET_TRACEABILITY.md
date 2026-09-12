# UDP PET traceability

Status is evidence-based. `IMPLEMENTED` requires executable code and a test; `PARTIAL` and `OPEN` remain explicit.

| Requirement | Status | Evidence |
|---|---|---|
| UDP-E2E-01 and UDP-A01 durable ACK after persistent intake | IMPLEMENTED | `HandoffIntakeService`, V1, `UdpRuntimeTest` |
| UDP-E2E-02 idempotent duplicate handoff | IMPLEMENTED | unique `handoff_id`, hash conflict detection, runtime test |
| HandoffPayload rc3 validation | IMPLEMENTED | frozen contract copy, `FrozenContractValidator`, negative test |
| Leased asynchronous materialization claim with `SKIP LOCKED` | IMPLEMENTED | `ResolutionRepository` |
| UDP-E2E-05 deterministic MATCH NEW REVIEW decision | IMPLEMENTED BASELINE | `ObjectResolutionService`, append-only decision and ambiguity tests |
| ResolutionDecision required evidence and configured strategy references | IMPLEMENTED BASELINE | V1 and `ObjectResolutionService` |
| Human-governed resolution issue lifecycle | PARTIAL | ambiguity is persisted; THS authorization and resolution commands remain open |
| Canonical revision materialization and authority engine | IMPLEMENTED BASELINE | V2, `CanonicalMaterializer`, materializer runtime tests |
| Property-level provenance and DataAccessLabel | IMPLEMENTED BASELINE | contribution/value tables and provenance test |
| Repeated observation without material change | IMPLEMENTED | canonical and authority hashes; observation-only test |
| Property authority and conflict without last-write-wins | IMPLEMENTED BASELINE | configured per-property priority and conflict test |
| Relationship input and configured strategy refs | IMPLEMENTED BASELINE | canonical payload input + pinned `relationshipResolutionStrategyRefs` |
| Relationship resolution and `QUARANTINE_RELATION` | IMPLEMENTED BASELINE | V3, `RelationshipMaterializer`, zero/multiple-match tests |
| Stable edge identity, immutable revisions and deduplication | IMPLEMENTED BASELINE | V3 natural edge key, evidence hash and append-only tests |
| Spatial relationship resolution | OPEN | PostGIS predicates, CRS normalization and geometry evidence remain a later tranche |
| Property-level DataAccessLabel enforcement | OPEN | later serving/security increment |
| Serving, graph/spatial query governor and MCP capability projection | OPEN | later increments |
| Lake object lifecycle, retention, compaction and reconciliation | OPEN | later increments |
| Production recovery, performance, SBOM and deployment evidence | OPEN | acceptance phase |
