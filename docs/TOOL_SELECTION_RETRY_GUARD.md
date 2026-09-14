# PET tool selection and retry-loop guard

Normative source: UDP PET v1.3 sections 100.2 and 101.9.2, with acceptance
criteria UDP-A39, UDP-A46, UDP-A50 and UDP-A51.

## Shared deterministic retry guard

`CapabilityRetryGuard` persists authoritative retry state in PostgreSQL using
correlation, principal, tenant, requested capability and semantic fingerprint as
its key. The mismatch threshold is configured through
`OUF_UDP_CAPABILITY_MISMATCH_THRESHOLD` and defaults to three.

For threshold `N`, attempts 1 through `N-1` receive the ordinary actionable
`QUERY_CAPABILITY_MISMATCH`. Attempt `N` changes the shared guard to `STALLED`
and returns non-retryable `TOOL_SELECTION_STALLED`. Later equivalent calls stay
blocked before object lookup, Query Planner and serving SQL. The response offers
only governed actions: narrow the request, ask the user for clarification, use
an available analytical capability, or stop.

## Semantic equivalence policy v1

The fingerprint includes the requested capability, typed logical purpose,
anchor identifier and normalized relationship-type set. Relationship ordering,
duplicates, surrounding whitespace and operational limit changes are cosmetic
and do not reset the guard. A different anchor, relationship set or recognized
logical purpose is substantive and receives a distinct fingerprint. This policy
is versioned by this implementation and acceptance test; new semantic fields
must enter change control before they can reset retry state.

## Cognitive-guidance regression

`tool-selection-regression-v1.json` freezes representative Italian and English
prompts for specialist related/spatial search, recursive traversal and one-hop
progressive exploration. CI evaluates them against a deterministic guidance
harness and requires the configured majority threshold. This suite detects
description/tool-selection regressions; it is not a datastore security control.
The Capability Router, cumulative governor and retry guard remain authoritative.
