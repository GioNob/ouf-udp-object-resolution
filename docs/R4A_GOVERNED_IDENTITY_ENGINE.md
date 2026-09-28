# R4a governed identity engine: implementation boundary

`GovernedIdentityEngine` is a deterministic decision core for issue #35. A
read-only PostgreSQL candidate adapter is available, but the core is not yet
wired to `PublishedResolutionLoop` or `PublishedRuntimeConfiguration`. The
current `weighted` fail-closed gate remains in force. No
source activation or R-SMOKE claim follows from this branch.

`PublishedIdentityPolicy.decode` now reads the proposed `governedIdentity`
shape under `resolution` strictly and constructs the same policy record used
by the decision core. It checks exact fields, tenant/source/class scope,
mapped signal IDs, pinned semantic reference syntax and required assertion
references. It is not invoked by the active publication resolver. A syntactic
assertion reference does not verify that the approved publication grants the
claimed uniqueness, exclusion or sufficient rule; that verification is still
a prerequisite for activation.
The identical `identity-governed-proposal-v1.json` fixture is exercised by
Onboarding proposal validation and this UDP decoder; it is a contract example,
not a deployed policy or a proof of semantic authority.
`GovernedIdentitySubjectMapper` projects one Ingestion handoff through the
published materialization mapping, matching policy signal IRIs to mapped
canonical fields and the handoff's semantic publication. A missing value stays
missing; an unmapped signal or publication mismatch fails closed. The source
file format and originating vertical do not enter identity comparison.

The published resolver now accepts only the six explicit legacy fields
(`strategyId`, `strategyVersion`, `policyRef`, `canonicalType`,
`canonicalKeyProperty`, `matchProperty`) and requires nonblank values.
`weighted`, `governedIdentity`, any other unrecognized field in the published
resolution configuration, or an incomplete legacy profile cannot be
interpreted as an executable single-property strategy. This concerns the
configuration bundle resolved from Onboarding, not the source file or the
Ingestion-to-UDP handoff. UDP receives per-record handoffs from Ingestion with
`sourceIdentity`, `canonicalPayload`, lineage and contract references; UDP
decides the canonical Urban Object identity and materializes its revision.
This compatibility check preserves the existing runtime gate while the new
policy contract and indexed retrieval are developed.

The policy is pinned by `ref` and `version` and scoped to tenant, canonical
class and source. Every signal names an exact versioned semantic reference
and comparator. Signals that assert uniqueness or exclude on disagreement,
and every sufficient rule, must now carry an explicit governance assertion
reference. Comparison evidence retains the signal assertion reference.
These references are traceability fields, not self-authenticating grants:
Onboarding and UDP still need to resolve them against an approved, immutable
publication and verify scope, cardinality and validity before activation.
A sufficient rule requires an explicit uniqueness assertion within that
policy scope. Text normalization is NFKC, whitespace collapse and
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
If a candidate satisfies a sufficient identity rule yet another signal
excludes it, the conflicting evidence requires HUMAN review rather than
automatic creation of a second object.
`ScopedIdentityCandidateRepository` scans active objects in the entire
tenant/class through `urban_object_tenant_type_status_idx`, ordered by object
ID and limited to `maxCandidates + 1`. It reads signal values from each
object's current revision and the winning contribution's semantic publication
reference in the same SQL statement. The PostgreSQL snapshot is recorded in
the coverage reference. An extra row marks the envelope incomplete and
forces `RESOLUTION_TOO_BROAD`. A small class has complete coverage at that
statement snapshot; no blocking-key subset can silently omit competitors.
This deliberately conservative scan is not a selective identity index for a
large class. Migration V22 adds a transaction-scoped tenant/class advisory
lock on every `urban_object` insert/update; `GovernedIdentityScopeLock`
acquires the same key before retrieval in an explicit transaction. Its
database-backed test checks both sides against a second connection. The
published worker does not yet use this protocol. Source-binding changes and
HUMAN repointing still need continuity checks in the atomic decision path.

The returned evidence includes comparator version and both provenance refs,
without copying raw values into the decision.
`GovernedIdentityReviewRepository` can persist a non-automatic outcome in the
existing append-only `resolution_decision` and durable `resolution_issue`,
including policy version, snapshot coverage, all assessments and signal
provenance. It maps `RESOLUTION_TOO_BROAD` to a review decision with a distinct
reason and an empty selectable candidate list; the existing HUMAN approval
endpoint therefore cannot approve one of the truncated rows. This repository
is not invoked by the published worker yet. Successful MATCH and NEW_OBJECT
still need an atomic write path and source-binding continuity checks.

`preflight` calls the same `decide` method used for individual observations
and reports the decision distribution and largest candidate set. Activation
will need governed acceptance limits for review volume and broad outcomes,
not a separate simulation algorithm.

## Integration gates still open

1. Agree the published, immutable policy schema and compatibility checks
   with Onboarding and Semantic Registry. Resolve each governance assertion
   ref against an approved publication, verifying scope, cardinality and
   temporal applicability; a semantic mapping must never imply uniqueness.
   Add relation, temporal and spatial comparators with explicit applicability.
2. Extend the current whole-class indexed snapshot to temporal scope and
   production scale, and hold the new scope lock across retrieval, decision,
   append-only persistence and binding writes in the published worker.
   If a selective identity index is introduced, backfill existing canonical
   objects and prove complete coverage before activation. An incomplete
   index must fail closed. Persist the coverage reference with each decision
   and carry it through preflight probes.
3. Connect the prepared review persistence to durable HUMAN quarantine and
   preserve source binding continuity on resume. Implement atomic MATCH and
   NEW_OBJECT persistence, retaining complete comparison evidence and
   snapshot coverage in each append-only decision.
4. Reconcile property conflicts under governed authority or HUMAN choice
   before serving a confirmed merge. Exercise exact historical replay,
   concurrent workers and the database-backed Gateway/THS acceptance path.

The cinema and traffic-light examples belong in regression fixtures only.
No field name or example literal has identity authority in production code.
