# Selective bitemporality and delta history

The default materialization profile keeps `checkpointInterval=1` and an empty
`bitemporalProperties` set. Existing source/type profiles therefore continue to
write full immutable checkpoints and use `validFrom`, `validTo`, `observedAt`
and `recordedAt` as temporal evidence without creating system intervals.

A governed `MaterializationProfile` may select a larger checkpoint interval.
The first revision and each configured periodic checkpoint retain the complete
canonical payload. Intermediate revisions store only a deterministic `set` /
`remove` patch and a reference to the preceding revision. The current payload
is maintained separately in `urban_object_current_state`; operational reads do
not reconstruct history. `CanonicalRevisionReconstructor` walks from a target
revision to its checkpoint, applies patches in order and verifies the resulting
canonical hash.

`DELTA_PATCH` and `PROPERTY_EVENTS` handoffs must identify the current base by
revision reference or canonical content hash as required by the frozen handoff
contract. A mismatched base fails before any revision is written.

Only property IRIs explicitly listed in `bitemporalProperties` receive complete
system intervals. A new materially different fact closes the previous open
system interval once and appends a new interval while preserving source-derived
valid time. Unlisted properties keep ordinary revision and system evidence.

When all four frozen change fingerprints are supplied (`canonicalContentHash`,
`geometryHash`, `relationshipsHash`, `contractEvidenceHash`), an identical
observation is recorded without loading or parsing the previous canonical JSON.
