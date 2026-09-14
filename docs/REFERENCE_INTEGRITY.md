# Materialization reference-integrity gate

The UDP checks every referenced contract at the point of use, after a worker has
claimed a durable materialization job and before resolution or canonical
materialization configuration is requested.

The gate resolves the exact source schema, bundle, semantic publication set,
adapter profile and any mapping, authority-policy or relationship-strategy
references present in the HandoffPayload. Resolution uses the governed catalog
mounted through `OUF_UDP_HISTORICAL_CONTRACT_CATALOG_PATH`; it never substitutes
an `ACTIVE` version.

Missing or revoked references move the job to `PAUSED`. A persisted exponential
backoff makes the job eligible for a later worker claim. After
`OUF_UDP_REFERENCE_INTEGRITY_MAX_ATTEMPTS` unsuccessful checks the job becomes
`QUARANTINED`. Malformed contract data, an invalid catalog, or a content-hash
change for a reference already checked by the job is quarantined immediately.

Evidence contains only the baseline hash, hashed missing-reference identifiers,
the attempt number and a safe failure code. Resolution and materialization ports
are not invoked on a failed gate. Kubernetes readiness remains local and does
not synchronously ping all cross-module dependencies.
