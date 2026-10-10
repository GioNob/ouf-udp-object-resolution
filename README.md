# OUF UDP Object Resolution

Initial Java 21 and PostgreSQL 17 implementation of the OUF Data Lake, Urban Data Platform and Urban Object Registry PET v1.3.

The first vertical slice provides frozen HandoffPayload validation, idempotent durable intake, leased materialization jobs, deterministic object resolution and append-only decision evidence. Durable ACK means that the input and recovery metadata are durable; it does not claim that asynchronous materialization has completed.

PET conformance is tracked conservatively in [`docs/PET_TRACEABILITY.md`](docs/PET_TRACEABILITY.md). The machine-readable requirement-to-code-to-test-to-evidence baseline is [`docs/pet-traceability-v1.3.json`](docs/pet-traceability-v1.3.json); a green CI run does not by itself imply full PET acceptance.

## Local verification

Set `OUF_UDP_DB_URL`, `OUF_UDP_DB_USER` and `OUF_UDP_DB_PASSWORD` for an empty PostgreSQL 17 database, then run `mvn clean verify`.


## File-loop consolidation, 2026-10-10

The owner prioritizes complete and consolidated file onboarding → semantic enrichment → Ingestion → UDP → authorized search, followed by clean-host repeatable installation and then native vertical API PULL according to v2.3. [Executed checkpoint](https://github.com/GioNob/ouf-semantic-registry/blob/052bab768fffccebeefdf69c32225fbbd8bf7bcc/docs/FILE_TO_SEARCH_STATUS_20261010.md), [component CI evidence](evidence/file-loop-ci-qualified-20261010.json) and [supported runtime decision](docs/SUPPORTED_RUNTIME_QUALIFICATION_20261010.md).

Current source qualifications repair the MinIO fixture and security gates; they do not grant target readiness or release acceptance. The technical Spring version evolution is explicit in the deployment checkpoint, preserves Java 21 and interface/DB contract bytes, and requires the same acceptance/recovery checks. No consumed target claim is replayed.

Identity scope is enforced in automatic binding/replay and in HUMAN approval/merge; [frozen proposal scope and remaining limits](docs/HUMAN_IDENTITY_SCOPE_20261010.md). Same address is not sufficient evidence even within a single nature. General weighted strategies, class equivalence and full multi-file target acceptance remain separate gates.
