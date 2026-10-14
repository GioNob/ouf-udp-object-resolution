# Native semantic reference capacity

The governed file channel accepts up to 32 exact semantic references per published
configuration. Native class and property artifacts have separate revision and
publication identifiers: a class plus twenty properties needs 21 bindings. A
containing published ontology may instead need one binding, but an arbitrary
wrapper must not be invented to conceal independent revisions.

UDP historical resolution now uses the same 32-reference bound as the MCP file
channel and Ingestion's compatibility/admission path. Every historical binding
still resolves its exact semantic ID, version, revision and publication. The
existing publication-membership and configuration-checksum checks remain.
No frozen schema, authorization, identity decision, HUMAN gate or database
migration changes. This is a bounded implementation capacity profile; the PET
does not prescribe the former limit of twenty semantic references.

The HTTP regression covers 21 and 32 references, rejection of 33 before semantic
requests, and mismatch in the last reference. It is a component test, not proof
of deployed Teatri ingestion, identity matching or serving.

Requests retain the existing 2 MiB response and five-second complete-exchange
bounds. Slow resolution does not extend worker leases or authorize stale
materialization; the existing claim fences remain mandatory. No new retry
session or target performance SLO is introduced.
