-- Prepared inverted index. No coverage rows are published by this migration.
-- An activation/backfill process must prove completeness before setting complete=true.
create table ouf_udp.identity_lookup_coverage(
  tenant_id text not null,
  canonical_class text not null,
  policy_ref text not null,
  policy_version text not null,
  coverage_ref text not null,
  complete boolean not null default false,
  updated_at timestamptz not null default transaction_timestamp(),
  primary key(tenant_id,canonical_class,policy_ref,policy_version)
);

create table ouf_udp.identity_lookup_token(
  tenant_id text not null,
  canonical_class text not null,
  policy_ref text not null,
  policy_version text not null,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  revision_id uuid not null references ouf_udp.object_revision on delete restrict,
  property_iri text not null,
  semantic_ref text not null,
  comparator text not null,
  value_hash text not null,
  primary key(policy_ref,policy_version,urban_object_id,property_iri)
);
create index identity_lookup_seed_idx on ouf_udp.identity_lookup_token
  (tenant_id,canonical_class,policy_ref,policy_version,
   property_iri,semantic_ref,comparator,value_hash,urban_object_id);

-- Legacy resolution, governed resolution, materialization and merge/split all
-- touch urban_object. No caller can retain a stale complete coverage marker.
create function ouf_udp.invalidate_identity_lookup_coverage() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=old.tenant_id and canonical_class=old.canonical_type and complete;
  end if;
  if tg_op <> 'DELETE' then
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=new.tenant_id and canonical_class=new.canonical_type and complete;
  end if;
  return null;
end$$;
create trigger identity_lookup_scope_change after insert or delete or update of
  tenant_id,canonical_type,status,current_revision_id on ouf_udp.urban_object
  for each row execute function ouf_udp.invalidate_identity_lookup_coverage();

create function ouf_udp.invalidate_identity_lookup_token_coverage() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=old.tenant_id and canonical_class=old.canonical_class
        and policy_ref=old.policy_ref and policy_version=old.policy_version and complete;
  end if;
  if tg_op <> 'DELETE' then
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=new.tenant_id and canonical_class=new.canonical_class
        and policy_ref=new.policy_ref and policy_version=new.policy_version and complete;
  end if;
  return null;
end$$;
create trigger identity_lookup_token_change after insert or update or delete
  on ouf_udp.identity_lookup_token for each row
  execute function ouf_udp.invalidate_identity_lookup_token_coverage();
