-- Catalog distinct field sets once per indexed policy. A lookup only expands
-- shapes with no common field; differently shaped objects whose common fields
-- all disagree are already distinct under the governed strategy.
create table ouf_udp.identity_lookup_shape_catalog(
  tenant_id text not null,
  canonical_class text not null,
  policy_ref text not null,
  policy_version text not null,
  field_set_hash text not null,
  fields_json jsonb not null check(jsonb_typeof(fields_json)='array'),
  indexed_objects bigint not null check(indexed_objects>0),
  primary key(tenant_id,canonical_class,policy_ref,policy_version,field_set_hash)
);
create trigger identity_lookup_shape_catalog_change after insert or update or delete
  on ouf_udp.identity_lookup_shape_catalog for each row
  execute function ouf_udp.invalidate_identity_lookup_token_coverage();
