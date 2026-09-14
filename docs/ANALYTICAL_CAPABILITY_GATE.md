# PET analytical capability gate

Normative source: UDP PET v1.3 acceptance criterion UDP-A36.

The operational serving router classifies composite analytical requests as
`ANALYTICAL_COMPOSITE`. Such a request is rejected before object visibility
lookup, Query Planner, graph/spatial execution, or serving SQL with the
machine-readable code `QUERY_REQUIRES_ANALYTICAL_CAPABILITY`.

The response is non-retryable and declares the required capability class as
`ANALYTICAL_OR_GOVERNED_ASYNC_JOB`. This deployment advertises neither an
analytical capability nor an asynchronous analytical job, so
`asynchronousJobAvailable` is false. The response never redirects or invokes a
different capability. It offers only governed actions: narrow the request to an
operational query, use an approved analytical capability when one is available,
submit a governed asynchronous job when one is available, or stop.

The rejection consumes one Agent Orchestration Budget unit through shared
PostgreSQL state. It does not increment query execution, node, edge, or database
execution-time counters. Runtime evidence uses an unknown anchor and verifies
that no retry-guard or application-serving path is reached.
