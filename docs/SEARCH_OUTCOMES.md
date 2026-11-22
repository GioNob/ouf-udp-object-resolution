# UDP search and acquisition outcomes

Both forms of `urban.object.search` and `POST /api/udp/v1/operations/acquisition` return `outcome` in successful responses. The acquisition route keeps the existing `ouf.udp.acquisition-evidence.v1` projection and the dedicated owner receipt. The same route is available from the isolated `UdpAcquisitionReadApplication` entrypoint.

| Outcome | Owner observation |
|---|---|
| `NO_OBJECTS` | The initial authorized view is empty at the read snapshot and has no verified materialization to poll. For acquisition this refers to the specified source and run. |
| `MATERIALIZATION_PENDING` | UDP has a durable receipt for an authorized handoff with no materialization observation and an active job or a supported scheduled pause. `pollAfterSeconds` suggests a reread in 5 to 3600 seconds. |
| `PARTIAL` | The response carries a continuation cursor. Use `nextCursor` for object search and `nextHandoffId` for acquisition evidence. |
| `COMPLETE` | The authorized page is complete or its cursor is exhausted. Acquisition items may contain intake, job and resolution evidence. |
| `INCOMPLETE` | Row filtering, a projection limit or a blocked acquisition job prevents a complete view and there is no available continuation. |

An available cursor takes precedence. Acquisition reads evaluate pending work across the requested run when pagination ends, including handoffs from earlier pages. The existing `partial` flags retain their meanings. Search marks rows omitted by authorization. Acquisition also marks pagination and property truncation. Ingestion ACK remains separately verifiable.

Object search uses its existing exact canonical type filter. The published worker records the verified type once under the current job claim before the materialization transaction. A type-specific reread hint requires that evidence, retained tenant ownership and the same source/run and label authorization as acquisition evidence. Jobs awaiting initial profile verification can already be visible in the exact run read. The pending lookup checks at most 100 tenant-bound candidates and reads them from the same database snapshot as the page. It has no cross-page snapshot token.

The five-second floor applies to ready/running work. A scheduled publication retry or the start of geometry validity supplies the delay for supported pauses. A budget exhausted into quarantine cannot produce a delay. These reads neither start work nor perform retries. Decision fields for the chatbot belong to MCP.
