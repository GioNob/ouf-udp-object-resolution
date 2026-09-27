# R4a resolution review gate

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
weights. One existing format fixture assigns all weight to a text NAME. Such
a profile can silently merge the first two same-named objects if the
single-field resolver is allowed to handle it. Publishing or activating a
weighted profile must be gated on an end-to-end UDP implementation and
versioned contract test, not merely on Onboarding validation.

The implemented policy must separate four questions:

1. **Find candidates:** blocking properties and spatial windows narrow a
   tenant/class-scoped search. A common value such as a generic name or a
   shared controller can be useful here but is not identity evidence.
2. **Assess shared evidence:** compare only version-compatible semantic
   property references and normalized values. Measure value prevalence,
   independence of signals and observed coverage; missing properties are
   not equalities or contradictions. A controller relation is many-to-one
   unless an approved contract explicitly states otherwise.
3. **Decide:** retain a source binding when its identity is stable. Automatic
   MATCH requires the approved combination of discriminating signals and
   separation from runners-up; a score or single common value is never
   enough. Automatic NEW is allowed only under an explicit source policy
   with traceable provenance and later governed merge. Plausible competing
   identities require REVIEW_REQUIRED. Over-limit candidate generation
   yields RESOLUTION_TOO_BROAD per PET, never a truncated first-match choice.
4. **Operate:** simulate the policy on representative source samples before
   activation, including common literals and dense geometry. Report the
   fraction of MATCH/NEW/REVIEW_REQUIRED/TOO_BROAD by source and class.
   An excessive broad-result rate blocks scheduled activation so that
   operators do not inherit a large per-record review queue.

Acceptance fixtures must include: same-name traffic lights with different
positions; multiple lights attached to one controller; genuinely identical
source binding across runs; a cinema with five properties compared against
another with twelve and four mapped shared properties; close but distinct
geometry; and a true conflict requiring HUMAN review. The result must be
invariant under record order and must retain the versioned evidence and
lineage. A matched object unions non-conflicting property contributions;
conflicting overlapping values follow approved authority or HUMAN choice.

Release evidence still needed: database-backed test of the published worker
path, a HUMAN approval through the Gateway, property conflict handling and
end-to-end serving after resumed materialization. No R-SMOKE PASS follows
from this change alone.
