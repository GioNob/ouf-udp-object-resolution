# Repair of current-main acceptance fixtures

Date: 2026-10-10. This change repairs the existing acceptance environment without importing the complete R4a development branches or their migrations.

The unavailable quay.io MinIO image is replaced with the same RELEASE.2025-09-07T16-13-09Z binary obtained from the upstream GitHub release and verified with SHA-256 7c5bd8512c6e966455b1d198209358b2d191c77a83ab377c4073281065fb855f. The isolated CI image has no impact on deployed workload images, credentials, provider leases or production state. Data-lake and recovery assertions remain in place.

Both module acceptance and PostgreSQL PITR/MinIO restore drills use the verified local fixture image. Recovery assertions, post-restore application checks, migration checks and lake maintenance checks remain unchanged.

Reference revision containing prior scoped qualification: 83249a897eb4add4289b5181b3299f48ea4c0f99. Current-main validation must still run on the new commit; green results from the reference branch do not constitute evidence for this exact backport. This record does not close the full file-to-search or installation acceptance gates.
