# R4a governed identity engine: implementation boundary

`GovernedIdentityEngine` is a deterministic decision core for issue #35. It is
not yet wired to `PublishedResolutionLoop`, `PublishedRuntimeConfiguration`, or
the database. The current `weighted` fail-closed gate remains in force. No
source activation or R-SMOKE claim follows from this branch.

The policy is pinned by `ref` and `version` and scoped to tenant, canonical
class and source. Every signal names an exact versioned semantic reference
and comparator. A sufficient rule requires an explicit uniqueness assertion
within that policy scope. Text normalization is NFKC, whitespace collapse and
locale-independent lowercase; concept IDs are exact; decimals are canonical
numeric values. Changing normalization requires a new comparator version.

Candidate retrieval must supply the *complete* bounded result, querying at
least `maxCandidates + 1`. The decision API now requires a `Candidates`
envelope pinned to policy ref/version, tenant and canonical class, with a
coverage reference for a complete index snapshot. Missing coverage returns
`REVIEW_REQUIRED` with `CANDIDATE_COVERAGE_UNVERIFIED`, even for an empty
candidate set or an otherwise sufficient match. A mismatched scope or policy
is invalid. This is an interface contract, not proof that an index is complete:
the future retrieval adapter must establish and retain the coverage reference
transactionally. If the extra row exists, the engine returns
`RESOLUTION_TOO_BROAD`; neither retrieval nor decision may truncate to a
first match. The engine rejects cross-tenant/class candidates and duplicate
object IDs. A missing or differently published semantic reference is neutral,
not an agreement. A competitor remains plausible unless a published signal
explicitly excludes it on disagreement. One sufficient candidate with no
plausible competitor may MATCH. Creation needs the source-scoped policy flag.
The returned evidence includes comparator version and both provenance refs,
without copying raw values into the decision.

`preflight` calls the same `decide` method used for individual observations
and reports the decision distribution and largest candidate set. Activation
will need governed acceptance limits for review volume and broad outcomes,
not a separate simulation algorithm.

## Integration gates still open

1. Agree the published, immutable policy schema and compatibility checks
   with Onboarding and Semantic Registry. Prohibit a semantic mapping from
   implying a uniqueness assertion. Add relation, temporal and spatial
   comparator contracts with explicit cardinality and applicability.
2. Implement indexed, tenant/class/time-scoped candidate retrieval and
   transactional coverage checks that issue the `Candidates` envelope.
   Existing canonical objects must be indexed or rebuilt before the policy
   is active; an incomplete index must fail closed. Persist the coverage
   reference with each decision and carry it through preflight probes.
3. Persist the complete comparison evidence with the append-only resolution
   decision. Connect `REVIEW_REQUIRED` and `RESOLUTION_TOO_BROAD` to durable
   HUMAN quarantine, and preserve the source binding continuity path.
4. Reconcile property conflicts under governed authority or HUMAN choice
   before serving a confirmed merge. Exercise exact historical replay,
   concurrent workers and the database-backed Gateway/THS acceptance path.

The cinema and traffic-light examples belong in regression fixtures only.
No field name or example literal has identity authority in production code.
