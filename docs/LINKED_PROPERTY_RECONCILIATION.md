# Linked object properties: native V1

Implements the approved [reconciliation specification](https://github.com/GioNob/ouf-deploy/blob/83e2e85a0ae4107ba52486de7bdcbfe4502761cc/docs/LINKED_OBJECT_PROPERTY_RECONCILIATION_V1.md). This is generic UDP behavior over canonical handoffs. It has no CSV, source-name or Teatri branch. APIs, scheduled deltas, files, GeoPackage attributes and dynamic layers use the same property engine after their adapters have produced canonical values and stable source-record identities.

## Activation

Apply additive migration V35 with the module release, then publish an explicitly approved materialization profile. Existing five-field property rules retain their previous behavior and serialized rule hashes. Installing this release alone does not activate a rule, retry quarantined jobs, reapply identity decisions or rewrite current objects. Previously quarantined handoffs without a pinned property profile report preview unavailable; the UI does not invent a merge result.

Example property in `extractionProfile.runtime.udp.materialization.properties`:

```json
{
  "sourceField": "urn:asset:displayName",
  "propertyIri": "urn:asset:displayName",
  "datatype": "string",
  "accessLabel": "RESTRICTED",
  "authorityOrder": ["registry-api", "survey-layer"],
  "reconciliation": {
    "policyRef": "urn:ouf:policy:linked-object-property-reconciliation:v1",
    "cardinality": "SINGLE",
    "normalizerRef": "NFC_TRIM",
    "normalizerVersion": 1,
    "validity": "SOURCE_INTERVAL",
    "revisionField": "urn:source:recordRevision"
  }
}
```

The outer materialization `policyRef`, contract bundle and canonical type are pinned per handoff before identity review. Policy publication must reflect each source's actual contract: a version field must contain a nonnegative integral revision of that source record, not a wall-clock observation time. Omit `revisionField` when the source offers no such guarantee; conflicting observations then remain visible.

## Behavior

* Missing, null and blank input fields contribute nothing and never remove an existing value. This includes omitted fields in a delta. `false` and numeric zero are values.
* `EXACT_TYPED` compares typed canonical values. `NFC_TRIM` version 1 only trims and applies Unicode NFC to strings; it does not infer case, synonyms or geographic equivalence. Original values and every source contribution remain retained.
* `SINGLE` chooses the highest-priority currently valid source. Different values at the same rank create a property conflict; the previous value remains and other properties continue. Different records in the same source do not overwrite one another. An explicit revision supersedes only the same source/record/type. An expired superseding revision does not resurrect an older revision of that record.
* `MULTI` performs a typed, deduplicated union with provenance for each member. A scalar is one member, including a string containing commas; text is never split into a list.
* An optional `deletionField` requires `SINGLE`, an explicit record revision and a nonempty source authority list. Only an authorized listed source's explicit boolean deletion can remove the property. Null is never a deletion command. Historical revisions and original contributions remain.
* `SOURCE_INTERVAL` uses `sourceIdentity.validFrom` / `validTo` with an exclusive upper bound; `UNBOUNDED` explicitly declares no interval filter. Current/search serving hides a reconciled property if any displayed member loses all valid support between scheduler runs. It does not silently select a different value while serving; a subsequent materialization recomputes selection. History continues exposing retained revisions under authorization.
* Every supporting contribution must pass serving authorization, in addition to the resulting property's label. Coalescing does not downgrade classifications or bypass source/job/revision scope.
* A conflict can be decided only through the separate native property-authority API, bound to tenant, object, property, current revision, pinned policy and evidence. Identity-link approval does not choose a conflicting property value. New evidence invalidates a stale decision.

## Review and supported boundaries

Resolution packages include server-computed current/incoming/result comparisons, applied rules and provenance. Preview and commit share the same candidate preparation and reconciler. The preview participates in the package hash, so changes to the candidate revision or evidence require a fresh review. The browser exposes source details and keeps raw JSON available.

Supported datatypes are string, number, integer, boolean and non-geometric object. Geometry, CRS transformations, spatial topology and relationship resolution retain their dedicated native profiles and authorization. This engine does not apply string normalization to coordinates or perform geometric union. Existing CRS/grid and temporal regression suites remain required.

Single-valued bitemporal results derive their validity from the winning contributions rather than the last incoming source. Multivalue or explicit-deletion rules combined with property bitemporal intervals are rejected until a native interval contract is qualified. Unsupported normalizers, versions and cardinalities are rejected. More than 10,000 retained contributions for one object/property fails explicitly with `UDP_PROPERTY_EVIDENCE_LIMIT`; evidence is never silently truncated.

Qualification runs on Java 21 and PostgreSQL 17: `LinkedPropertyReconcilerTest`, `LinkedPropertyRuntimeTest`, and existing materialization, temporal, governed serving and identity suites. Tests exercise source-record revisions, complementary fields, delta omissions, normalization/provenance, field conflicts, multivalue access, explicit deletion, tenant-bound previews and stale human decisions.
