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
| Human-governed resolution issue lifecycle | IMPLEMENTED BASELINE | V5, append-only human decision, trusted context and optimistic-lock tests |
| Canonical revision materialization and authority engine | IMPLEMENTED BASELINE | V2, `CanonicalMaterializer`, materializer runtime tests |
| Property-level provenance and DataAccessLabel | IMPLEMENTED BASELINE | contribution/value tables and provenance test |
| Repeated observation without material change | IMPLEMENTED | canonical and authority hashes; observation-only test |
| Property authority and conflict without last-write-wins | IMPLEMENTED BASELINE | configured per-property priority and conflict test |
| Relationship input and configured strategy refs | IMPLEMENTED BASELINE | canonical payload input + pinned `relationshipResolutionStrategyRefs` |
| Relationship resolution and `QUARANTINE_RELATION` | IMPLEMENTED BASELINE | V3, `RelationshipMaterializer`, zero/multiple-match tests |
| Stable edge identity, immutable revisions and deduplication | IMPLEMENTED BASELINE | V3 natural edge key, evidence hash and append-only tests |
| PostGIS spatial relationship resolution | IMPLEMENTED BASELINE | V4, `SpatialMaterializer`, `INTERSECTS/WITHIN/CONTAINS/NEAREST/OVERLAP_RATIO` |
| CRS, invalid geometry and normalization evidence | IMPLEMENTED BASELINE | explicit CRS guard, no silent repair, versioned geometry evidence |
| Multiple spatial match handling | IMPLEMENTED | `SPATIAL_MULTIPLE_MATCHES`, candidate evidence, no arbitrary edge |
| Merge dry-run, impact and human execution | IMPLEMENTED BASELINE | V5–V6, immutable impact plan, binding transfer, edge repoint/dedup, contribution reevaluation links |
| Split dry-run, explicit binding allocation and edge review | IMPLEMENTED BASELINE | allocation completeness guard, unresolved binding/relationship issues |
| Historical identity alias and redirect | IMPLEMENTED BASELINE | `object_identity_history`, `redirect_to`, current/historical identity lookup |
| Trusted Human Surface boundary for identity governance | IMPLEMENTED BASELINE | server-side request attributes, actor-header rejection, non-MCP execute operations |
| Property-level DataAccessLabel enforcement | IMPLEMENTED BASELINE | V7, `GovernedServingService`, omission-before-serialization and audit test |
| Current/history/as-of and exact-type search | IMPLEMENTED BASELINE | opaque cursor, page cap, indexed filter and tenant anti-enumeration tests |
| Relationship-level enforcement without degree leakage | IMPLEMENTED BASELINE | label-filtered SQL and no-hidden-cursor test |
| Lineage redaction | IMPLEMENTED BASELINE | source/raw references require separate capabilities |
| Serving API and MCP capability projection | IMPLEMENTED BASELINE | `ServingApi`, `openapi-serving.yaml` |
| PostgreSQL cumulative Query Complexity Budget | IMPLEMENTED BASELINE | V8, atomic conditional upsert, TTL and concurrency test |
| Graph neighbors and recursive traversal guardrails | IMPLEMENTED BASELINE | typed DTO, indexed adjacency/recursive CTE, label filtering and limits |
| Capability misuse/orchestration budget | IMPLEMENTED BASELINE | purpose guard and persisted mismatch counter |
| Spatial query governor | IMPLEMENTED BASELINE | V9, typed nearby/intersects/within/intersection-search, PostGIS bbox prefilter, CRS/radius/area/result gates and cumulative budget tests |
| Lake object lifecycle and two-phase deletion | IMPLEMENTED BASELINE | V10, immutable logical identity, verified durability protocol, deletion guard, lease and lifecycle tests |
| Lake compaction and reconciliation | IMPLEMENTED BASELINE | immutable verified manifest, label/retention boundary, checksum/missing/orphan findings and no destructive adoption |
| S3-compatible production adapter and shadow rebuild | OPEN | storage SPI is defined; provider binding and rebuild/cut-over remain later increments |
| Production recovery, performance, SBOM and deployment evidence | OPEN | acceptance phase |
