# OUF UDP Object Resolution

Initial Java 21 and PostgreSQL 17 implementation of the OUF Data Lake, Urban Data Platform and Urban Object Registry PET v1.3.

The first vertical slice provides frozen HandoffPayload validation, idempotent durable intake, leased materialization jobs, deterministic object resolution and append-only decision evidence. Durable ACK means that the input and recovery metadata are durable; it does not claim that asynchronous materialization has completed.

PET conformance is tracked conservatively in [`docs/PET_TRACEABILITY.md`](docs/PET_TRACEABILITY.md). The machine-readable requirement-to-code-to-test-to-evidence baseline is [`docs/pet-traceability-v1.3.json`](docs/pet-traceability-v1.3.json); a green CI run does not by itself imply full PET acceptance.

The R4a canonical identity lookup and its activation constraints are documented
in [`docs/R4A_GOVERNED_IDENTITY_ENGINE.md`](docs/R4A_GOVERNED_IDENTITY_ENGINE.md).
Flyway V27–V34 extends the deployed V22–V26 history without changing its
checksums; V28 adds the inverted lookup tables. The governed worker refreshes
one object's index in the same transaction as materialization. A HUMAN with
`urban.identity.preflight` can rebuild and attest a frozen Onboarding policy via
`POST /api/udp/v1/governance/identity/preflight`; the response is valid only
while the exact indexed coverage remains complete. Publication remains gated
by Onboarding's current attestation check and the lab IAM/Gateway rollout.

## Local verification

Set `OUF_UDP_DB_URL`, `OUF_UDP_DB_USER` and `OUF_UDP_DB_PASSWORD` for an empty PostgreSQL 17 database, then run `mvn clean verify`.


## Consolidated live baseline — 2026-10-10

This main reconciles the advanced V34 / governed-identity / IAM / recovery source lineage with the October 10 runtime and identity-scope corrections. All 34 retained Flyway migrations are unchanged. The qualified source is bc336208ce41876f2854c3a4d8623fb6698bdcdd; its five CI workflows passed, with 208 tests (zero failures/errors, two pre-existing skips), including sixteen new scope regression tests. [Qualification and limits](docs/LIVE_BASELINE_QUALIFICATION_20261010.md). The source-continuity workflow now rejects removal or alteration of retained migrations and mandatory advanced features.

The older documents and evidence under file-loop-ci-qualified-20261010 describe the preceding main qualification, not this advanced candidate or the VPS. Governed identity remains policy-controlled sufficiency/coverage with bounded REVIEW; the legacy weighted path is not promoted to the default. File consolidation and authorized search precede native vertical API PULL v2.3. No target import, service restart, consumed-claim replay or release acceptance follows from this source reconciliation.
