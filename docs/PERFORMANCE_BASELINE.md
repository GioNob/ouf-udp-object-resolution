# Performance baseline

The performance gate is a reproducible CI baseline, not a production capacity claim. It runs on a GitHub-hosted Ubuntu 24.04 runner with Java 21 and a dedicated PostGIS 17 service.

The workload uses five warm-up iterations, fixed measured iteration counts and deterministic cardinalities. It exercises the real Spring services and PostgreSQL transactions for durable handoff intake, deterministic resolution, canonical materialization, governed indexed search and PostGIS proximity search. The JSON evidence records the runtime versions, runner processor count, cardinalities, every sorted sample, p50, p95, maximum, throughput and the threshold used for every workload. Portable SHA-256 checksums freeze the report files.

Thresholds are deliberately broad enough for shared-runner variance and act as regression tripwires. They are not SLOs. Production sizing still requires representative data distributions, concurrent clients, production infrastructure, network latency and an agreed service-level objective.

Run locally with a clean PostgreSQL/PostGIS database:

```bash
mvn -B -ntp -Dtest=PerformanceBaselineIT test
```

Evidence is written to `target/performance-evidence/` and uploaded by the `performance-baseline` CI job even when a threshold fails.
