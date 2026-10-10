# Same-nature identity guards for the current file path

Default comparison is confined to the same canonical type and configured tenant. This patch also enforces those conditions when reusing an existing source binding and when replaying a prior automatic decision. The target must remain ACTIVE. No cross-vocabulary equivalence is inferred.

A new observation with an incompatible active source binding becomes REVIEW_REQUIRED with UDP_SOURCE_BINDING_SCOPE_MISMATCH. It neither changes the binding nor creates a substitute object. Candidate references for this issue are empty to avoid exposing a foreign-tenant target. Replaying an existing materializing decision with an incompatible target fails closed with UDP_RESOLUTION_REPLAY_TARGET_SCOPE_MISMATCH; append-only decisions remain unchanged.

Source-binding resolution is serialized by a transaction-scoped advisory lock on the exact source identity. Selected object rows are held until commit; an unsuccessful binding write aborts the transaction instead of returning an unbound object. Blank match attributes are absent evidence, not a shared identity key.

PostgreSQL runtime regressions cover different natures with identical fields, reclassification, foreign tenant, inactive target, incompatible replay, blank evidence and unchanged same-nature continuity. This does not certify all identity/governance surfaces, weighted scoring or equivalent-class publication. No migration, production lifecycle action, data rewrite or default profile activation is introduced.
