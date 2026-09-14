# PET MCP Capability Router

Normative source: UDP PET v1.3 sections 41.3, 48.3 and 101.9.1, with
acceptance criteria UDP-A26, UDP-A31, UDP-A32, UDP-A33, UDP-A45 and UDP-A47.

`CapabilityRouter` is a separate enforcement component invoked before graph
input reaches object visibility lookup, logical/physical planning or serving
SQL. The traversal purpose is a closed enum rather than free natural language.
Only `RECURSIVE_HIERARCHY` may proceed to the bounded recursive planner.

Cross-domain relationship, cross-domain spatial and exploratory one-hop forms
are rejected with HTTP 422 and a machine-readable `QUERY_CAPABILITY_MISMATCH`.
The payload contains requested and recommended capabilities, a stable reason,
retryability, reusable and missing arguments, the recommended input-schema
reference and the split budget impact required by the PET.

The Router never calls the recommended capability. A consumer must issue a new
explicit call, which is authenticated and authorized independently and consumes
the same correlation budget. Mismatch accounting is committed to the shared
PostgreSQL BudgetStore so it remains cross-Pod; it does not increment query
execution, node, edge or DB-time counters. This operational-state write is not a
serving query and does not invoke the datastore planner/repository.

Semantic retry equivalence, configurable mismatch thresholds and
`TOOL_SELECTION_STALLED` are intentionally deferred to the following PET
increment.
