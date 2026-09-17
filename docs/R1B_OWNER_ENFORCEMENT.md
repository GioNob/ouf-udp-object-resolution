# R1b owner enforcement — PET mapping

Normative sources: Authorization 1.5 §§109.2–109.3, 36.10 (owner-side DAL), 34.2 (operational detail and denial semantics); Semantic 1.3 §65/§160.7. This follow-up closes the code boundaries identified in the previous R1b endpoint audit. Real IAM/workload/THS browser acceptance remains AUT-04/R6.

Shared SDK 1.2 adds `OwnerAuthorization`, a request-bound evaluator using the exact same immutable snapshot as servlet authentication. `candidates()` is admission only: it checks declared actor/scope and MUST NOT authorize release. `decide`/`require` is called with owner-resolved resource metadata before each output. No directory or remote PDP lookup occurs in this path. Raw capability/label headers and coarse attributes remain non-authoritative.

The HTTP serving adapter binds the owner evaluator and ignores `ouf.allowedDataLabels`. DB metadata supplies labels, object/revision/property identifiers, relationship source/target and lineage source. Current/history/search properties, geometry, relationships (including target visibility) and lineage are checked before output. Source identity and raw lineage references each require their separate grant on the actual resource. Missing required stored labels deny. Object identity metadata is the initial OPEN projection; payload fields keep their persisted DAL. Resource type `object` uses the object UUID, with optional `propertyIri`, `revisionRef`, `canonicalType`, `projection` and `sourceRef` attributes; edges use type `relationship` with edge UUID and sourceObjectRef/targetObjectRef/relationIri.

Filtered pages set `partial=true` and suppress continuation cursors that would expose excluded identifiers. Redactions record the actual bundle/version/capability reference without payload. Legacy seven-argument ServingAuthorizationContext is retained for controlled domain fixtures; the HTTP adapter always supplies the owner binding. Tests use a real PostgreSQL-backed HTTP object projection: forged trusted label attribute cannot expose RESTRICTED content, matching scoped grant works, wrong object denies.

Graph neighbors/traverse and related-search apply the same owner resource checks to
stored edge source/job/DAL metadata and both object endpoints. Traversal removes
paths reached only through denied edges. Spatial nearby/intersects/within/intersection-search
require both object and geometry visibility plus the spatial operation on each hit;
anchor-based operations also authorize the anchor geometry. SQL candidate labels
are not grants. Batched metadata and query rows share a repeatable-read transaction.
Filtered query results carry `partial=true`; IDs and distances of denied hits are
not serialized. The original query size, depth, timeout and cumulative budget
limits remain in force. Scoped policies must grant the necessary object read and
geometry read capabilities in addition to the query capability.

`GraphQueryRuntimeTest.httpGraphAndRelatedSearchCannotBypassTargetOrSourcePolicy`
and `SpatialQueryRuntimeTest.httpSpatialRoutesApplyGeometryAndSourcePolicyBeforeReturningHits`
exercise all seven HTTP routes with real PostgreSQL/PostGIS fixtures, positive
controls, explicit target/source/job denials and untrusted label attributes.
