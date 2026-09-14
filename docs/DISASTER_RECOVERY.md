# Disaster recovery baseline

## Historical REPRODUCE

`REPRODUCE` is an intentional replay of a durable historical handoff. It pins
the exact `sourceSchemaRef`, `bundleRef`, `semanticPublicationSetRef`,
`adapterProfileRef` and any mapping, authority or relationship strategy
references present in the original payload. It never falls back to an `ACTIVE`
contract.

The environment binding `OUF_UDP_HISTORICAL_CONTRACT_CATALOG_PATH` points to a
versioned JSON catalog mounted from governed configuration. Each entry contains
`kind`, `ref`, `version`, `contentHash` and `status`. Missing, revoked or changed
entries make the plan `PAUSED`; failed checks are recorded using hashed
reference identifiers. An operator may resume only after the exact references
are resolvable again.

Planning requires `udp.replay.plan`. Execution and abort require a current
trusted-human authorization carrying `udp.replay.execute`. Execution reuses the
already verified RAW LakeObject, creates a distinct durable handoff and
materialization job, and records append-only replay evidence. It does not
overwrite the source handoff or published historical revisions.

The CI restore drill is executable evidence, not a statement that the deployment platform is already production-certified.

It performs a PostgreSQL 17 physical base backup with WAL archiving, commits one evidence event before the recovery target and one after it, destroys the primary container and recovers to the recorded timestamp. The retained event must exist and the later event must not.

The same drill cold-backs up a MinIO data directory containing an object whose locator, size and SHA-256 are registered in `lake_object`. It restores the object store into a new instance, verifies the metadata hash and starts the application against both restored systems. The scheduled reconciliation must complete with zero missing, mismatched or orphan objects.

`DR_RESULT.txt`, application logs and `SHA256SUMS` are uploaded by CI. The measured container-level restore time is useful for regression detection but is not a production RTO commitment. Infrastructure-specific WAL retention, off-site replication, encryption, key recovery, bucket versioning and production RPO/RTO approval remain deployment acceptance responsibilities.
