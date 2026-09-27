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

Release evidence still needed: database-backed test of the published worker
path, a HUMAN approval through the Gateway, property conflict handling and
end-to-end serving after resumed materialization. No R-SMOKE PASS follows
from this change alone.
