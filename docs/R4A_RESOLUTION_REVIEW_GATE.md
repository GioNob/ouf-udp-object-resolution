# R4a resolution review gate

Cross-module live snapshot and PET 1.7 handoff: [OUF handoff 27 September 2026](https://github.com/GioNob/ouf-semantic-registry/blob/codex/r4a-smoke-semantic-inventory/docs/handoffs/OUF_HANDOFF_2026-09-27_R4A.md). This branch is a prerequisite under review, not a deployed complete identity engine. The Semantic publication is live, while the Onboarding DRAFT for asset `8ec8ae90-808a-4d9e-907c-d56de119e376` is inactive and no Ingestion/UDP result is attested. Issue #35 tracks the general executable policy. Do not activate a weighted definition solely because Onboarding validates its shape.

The UDP PET separates durable handoff acknowledgement from later object
resolution. A handoff can be durable while its individual resolution job is
quarantined for HUMAN review. No canonical revision may be served for that
unresolved observation.

The current implementation compares a single configured `matchProperty`.
If it finds multiple active candidates it persists `REVIEW_REQUIRED` and a
`resolution_issue`. Both execution paths now leave the job `QUARANTINED`
with `UDP_RESOLUTION_REVIEW_REQUIRED`, rather than marking it `SUCCEEDED`.
Approval records the HUMAN decision and source binding, requeues that job,
and the existing worker materializes the approved target using the pinned
historical profiles. The automatic decision remains append-only. A dismissed
issue remains quarantined. Independent jobs can continue.

This is a prerequisite, not the general multi-property matching policy.
That policy must compare shared, versioned semantic property references and
typed normalized values, bound candidate generation, retain comparison
evidence, and route any uncertain identity or unresolved property conflict
to the same durable HUMAN review flow. File uploads may present the review
immediately, while unattended SUO pulls retain it until a HUMAN returns;
neither mode may silently select a candidate. The source-specific contract
quarantine in Ingestion is a separate condition before durable handoff.

## General matching gate to implement

The current single-`matchProperty` resolver must not be used as evidence that
a generic identity policy is ready. Onboarding already validates an optional
`resolution.weighted` structure (signals, weights, blocking properties,
thresholds and maximum candidate count), but UDP does not execute those
weights. One existing format fixture assigns all weight to a text NAME. Such a profile could silently merge distinct objects if a single-field
resolver handles it. The UDP historical profile resolver rejects a weighted
definition with
`UDP_WEIGHTED_RUNTIME_UNAVAILABLE` before any identity decision. Publishing or
activating a weighted profile must be gated on an end-to-end UDP implementation
and versioned contract test, not merely on Onboarding validation.

The general rule is expressed over *semantic evidence and its declared
constraints*, not over field names, example values or a frequency cutoff.
A property mapping identifies the meaning of a value; it does not claim the
value uniquely identifies an object. The published identity policy declares
the evidence role, comparator/version, scope and constraints of each mapped
property or relationship, including any justified uniqueness, cardinality,
temporal or geometric semantics. A many-to-one relationship cannot identify
one of its children just because the related object is the same. Repeatedness
and estimated selectivity can optimize candidate retrieval and inform a
review card; they do not turn evidence into or out of identity authority.

The executable engine is class-neutral and uses the same steps for every
published policy:

1. **Bind known source identity.** A valid existing
   `(sourceId,typeCode,sourceObjectId)` binding retains continuity. Source
   identity and canonical Urban Object identity remain separate.
2. **Generate candidates.** Apply the policy's bounded, indexed blocking
   predicates within compatible tenant, class and time scopes. Blocking is
   retrieval only. Overflow yields `RESOLUTION_TOO_BROAD` under the PET,
   never a truncated first-match decision.
3. **Compare evidence.** Align version-compatible semantic property and
   relationship references, normalize values with typed/versioned
   comparators, and evaluate their declared cardinality, uniqueness,
   temporal and geometry constraints. Compare the shared property set;
   missing observations are neither agreement nor contradiction. Preserve
   both positive and negative evidence, mapping and normalization versions,
   provenance and coverage.
4. **Decide under an explicit policy.** Automatic MATCH requires an
   approved sufficient identity rule whose premises are satisfied and whose
   competing candidates are excluded under that rule. A weighted score can
   rank and explain candidates but is not by itself an identity proof.
   Automatic NEW requires an explicit source-scoped creation policy and
   traceable provenance, with governed merge available later. Genuine
   unresolved competing identities or conflicting evidence yield
   `REVIEW_REQUIRED`, followed by the durable HUMAN workflow.
5. **Validate before scheduled activation.** Exercise the *same executable
   engine* on representative source observations and adversarial fixtures,
   checking decision invariance, candidate bounds and review volume. Fix
   a policy causing broad or excessive ambiguous outcomes before the source
   is scheduled; do not add an example-specific exception to the resolver.

Examples are regression tests of these general rules, never special cases
in the implementation: objects with equal labels but distinct evidence,
several children sharing one controller, repeated source bindings, unequal
property counts with a shared mapped subset, nearby geometries, and truly
ambiguous observations. Results must be invariant to input order. A confirmed
match unions non-conflicting contributions; conflicting overlapping values
follow governed property authority or explicit HUMAN choice.

Release evidence still needed: database-backed test of the published worker
path, a HUMAN approval through the Gateway, property conflict handling and
end-to-end serving after resumed materialization. No R-SMOKE PASS follows
from this change alone.
