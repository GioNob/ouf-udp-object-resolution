# Query budget governor resilience

Normative source: UDP PET v1.3 acceptance criteria UDP-A40, UDP-A41,
UDP-A48 and UDP-A49.

## Connection-pool bulkhead

`QueryBudgetStore` acquires a fair, bounded permit before creating its
`REQUIRES_NEW` transaction. This ordering is intentional: transaction advice
around a method may reserve a JDBC connection before method code runs, so the
bulkhead owns the `TransactionTemplate` instead. An excess request waits only
the configured acquisition interval and then fails closed with
`UDP_QUERY_BUDGET_BULKHEAD_SATURATED`.

Defaults are two concurrent budget transactions and a 100 ms acquisition
timeout. They are configurable with
`OUF_UDP_QUERY_BUDGET_BULKHEAD_PERMITS` and
`OUF_UDP_QUERY_BUDGET_BULKHEAD_ACQUIRE_TIMEOUT_MS`.

## Replica and restart evidence

The runtime lab creates independent application-replica fixtures with distinct
Hikari pools, local bulkheads, budget stores, retry guards and routers. Their
only shared state is PostgreSQL, matching the state boundary between Kubernetes
replicas. Concurrent equivalent adversarial requests are split across the
replicas and must produce one atomic retry count, one coherent orchestration
budget and one deterministic threshold transition to `TOOL_SELECTION_STALLED`.

The test then destroys one replica and creates a fresh pool/store instance. The
new instance must continue from the persisted budget rather than reset it.

## Evidence qualification

Pool contention and restart semantics are executable runtime acceptance tests.
The multi-replica adversarial result is `VERIFIED-LAB`: it validates isolated
replica state and separate connection pools, while production Kubernetes
routing, sizing and SLO acceptance remain deployment evidence.
