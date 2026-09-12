# OUF UDP Object Resolution

Initial Java 21 and PostgreSQL 17 implementation of the OUF Data Lake, Urban Data Platform and Urban Object Registry PET v1.3.

The first vertical slice provides frozen HandoffPayload validation, idempotent durable intake, leased materialization jobs, deterministic object resolution and append-only decision evidence. Durable ACK means that the input and recovery metadata are durable; it does not claim that asynchronous materialization has completed.

## Local verification

Set `OUF_UDP_DB_URL`, `OUF_UDP_DB_USER` and `OUF_UDP_DB_PASSWORD` for an empty PostgreSQL 17 database, then run `mvn clean verify`.
