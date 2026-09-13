# Disaster recovery baseline

The CI restore drill is executable evidence, not a statement that the deployment platform is already production-certified.

It performs a PostgreSQL 17 physical base backup with WAL archiving, commits one evidence event before the recovery target and one after it, destroys the primary container and recovers to the recorded timestamp. The retained event must exist and the later event must not.

The same drill cold-backs up a MinIO data directory containing an object whose locator, size and SHA-256 are registered in `lake_object`. It restores the object store into a new instance, verifies the metadata hash and starts the application against both restored systems. The scheduled reconciliation must complete with zero missing, mismatched or orphan objects.

`DR_RESULT.txt`, application logs and `SHA256SUMS` are uploaded by CI. The measured container-level restore time is useful for regression detection but is not a production RTO commitment. Infrastructure-specific WAL retention, off-site replication, encryption, key recovery, bucket versioning and production RPO/RTO approval remain deployment acceptance responsibilities.
