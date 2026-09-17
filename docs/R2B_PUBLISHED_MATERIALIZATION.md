# R2b published materialization and durable intake

Human outcome: an authorized reader can find an automatically acquired object and inspect its lineage; classified properties remain protected. Ingestion delivery success and UDP serving availability are distinct states.

Enable `ouf.udp.execution.enabled=true` and configure `ouf.udp.execution.gateway-url`, `ouf.udp.execution.token-file` and the existing explicit Lake tenant/retention/access/S3 properties. This runtime composition uses one governed intake tenant per deployment. It does not infer data tenancy from a client payload. The canonical object receives the configured tenant; matching is tenant-filtered.

The new Lake endpoint authenticates SERVICE identity and evaluates `datalake.write` against the configured tenant and actual source/run. Handoff evaluates `udp.candidate.write`. Neither capability grants serving access. Both declared and chunked request bodies are bounded before JSON allocation.

Lake writes reuse the real Lake lifecycle and S3 adapter. Handoff validates that `rawObjectRef` resolves to a VERIFIED RAW object belonging to the same tenant, source and type, retains that exact reference and creates a blocking lineage reference in the durable handoff transaction. The recovery envelope remains separately stored. V19 only adds this reference; earlier migrations are untouched.

Duplicate handoff identity now compares the complete JSON payload as well as content hash: a changed source, contract or raw reference cannot reuse an existing receipt merely because candidate content matches.

`PublishedRuntimeConfiguration` resolves the exact `bundleId:version:checksum` through the Onboarding owner Gateway route, verifies its checksum and resolves exact Semantic identity triples. It checks handoff references against the pinned execution configuration. Historical file catalogs remain the existing disabled-runtime/laboratory option; enabling this runtime selects the owner resolver. Temporary owner unavailability produces the existing reference-integrity retry/pause state, never fallback to latest.

Approved `extractionProfile.runtime.udp` contains explicit resolution and materialization profiles using the existing `UdpPorts` records. Canonical type must match an approved target class. Property source/target IRIs and labels must match the approved mapping and data-access policies. Profiles are not generated from unclassified incoming data.

The scheduled loop reuses resolution and canonical materialization services. Owner lookups happen outside transactions; then a short transaction locks the still-valid claimed job, serializes competing resolutions for the canonical type and atomically commits resolution/materialization/job completion. Existing controlled domain tests remain available without enabling the production loop.

The R2b process fixture proves CSV/REST acquisition through to authorized current reads, lost ACK recovery, restricted-field omission and serving after process restart with PostgreSQL/PostGIS and MinIO. It does not constitute APISIX, real IAM/THS, real Semantic, all GIS/relationship/spatial paths, replay, capacity or production acceptance. Full browser/MCP journeys are still tracked in R4 and operational status cards in R3.
