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
| Canonical revision materialization and authority engine | OPEN | next increment |
| Relationship and spatial resolution including `QUARANTINE_RELATION` | OPEN | requires Ingestion unresolved-relationship input contract alignment |
| Property-level DataAccessLabel enforcement | OPEN | later serving/security increment |
| Serving, graph/spatial query governor and MCP capability projection | OPEN | later increments |
| Lake object lifecycle, retention, compaction and reconciliation | OPEN | later increments |
| Production recovery, performance, SBOM and deployment evidence | OPEN | acceptance phase |
