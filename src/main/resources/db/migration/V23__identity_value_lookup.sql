-- Prepared inverted index. No coverage rows are published by this migration.
-- An activation/backfill process must prove completeness before setting complete=true.
create table ouf_udp.identity_lookup_coverage(
  tenant_id text not null,
  canonical_class text not null,
  policy_ref text not null,
  policy_version text not null,
  coverage_ref text not null,
  -- A complete marker is valid only for observations with this exact field set.
  -- The future verifier must establish this shape for every active object.
  field_set_hash text not null,
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

-- A current revision can still receive an append-only property_value row.
-- Serialize that change with candidate reads and invalidate its coverage.
create function ouf_udp.invalidate_identity_lookup_property() returns trigger language plpgsql as $$
declare scope_tenant text; scope_class text;
begin
  select o.tenant_id,o.canonical_type into scope_tenant,scope_class
    from ouf_udp.object_revision r join ouf_udp.urban_object o on o.urban_object_id=r.urban_object_id
    where r.revision_id=new.revision_id and o.current_revision_id=new.revision_id;
  if found then
    perform pg_advisory_xact_lock(hashtextextended('identity:' || scope_tenant || ':' || scope_class,0));
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=scope_tenant and canonical_class=scope_class and complete;
  end if;
  return null;
end$$;
create trigger identity_lookup_current_property after insert on ouf_udp.property_value
  for each row execute function ouf_udp.invalidate_identity_lookup_property();
