-- D-059. Existing rows enter the uncategorized scope with their original proofs.
alter table ouf_udp.urban_object add column category_iri text check (category_iri <> '');
alter table ouf_udp.identity_preactivation_attestation add column category_iri text;

alter table ouf_udp.identity_lookup_coverage
  add column category_iri text check (category_iri <> ''),
  drop constraint identity_lookup_coverage_pkey,
  add unique nulls not distinct (tenant_id,canonical_class,policy_ref,policy_version,category_iri);

alter table ouf_udp.identity_lookup_token
  add column category_iri text check (category_iri <> ''),
  drop constraint identity_lookup_token_pkey,
  add unique nulls not distinct (tenant_id,canonical_class,policy_ref,policy_version,category_iri,urban_object_id,property_iri);

alter table ouf_udp.identity_lookup_shape
  add column category_iri text check (category_iri <> ''),
  drop constraint identity_lookup_shape_pkey,
  add unique nulls not distinct (tenant_id,canonical_class,policy_ref,policy_version,category_iri,urban_object_id);

alter table ouf_udp.identity_lookup_shape_catalog
  add column category_iri text check (category_iri <> ''),
  drop constraint identity_lookup_shape_catalog_pkey,
  add unique nulls not distinct (tenant_id,canonical_class,policy_ref,policy_version,category_iri,field_set_hash);

-- Retain V1 canonical-key uniqueness among uncategorized rows. Categorized keys
-- belong to the new scope; both branches still allow any number of missing keys.
alter table ouf_udp.urban_object drop constraint urban_object_canonical_type_canonical_key_key;
create unique index urban_object_legacy_key_idx on ouf_udp.urban_object
  (canonical_type,canonical_key) where category_iri is null;
create unique index urban_object_scope_key_idx on ouf_udp.urban_object
  (tenant_id,canonical_type,category_iri,canonical_key) where category_iri is not null;
drop index ouf_udp.urban_object_match_idx;
create index urban_object_match_idx on ouf_udp.urban_object
  (tenant_id,canonical_type,coalesce(category_iri,''),match_key,urban_object_id) where status='ACTIVE';
create index urban_object_identity_backfill_idx on ouf_udp.urban_object
  (tenant_id,canonical_type,coalesce(category_iri,''),urban_object_id) where status='ACTIVE';
drop index ouf_udp.identity_lookup_seed_idx;
create index identity_lookup_seed_idx on ouf_udp.identity_lookup_token
  (tenant_id,canonical_class,coalesce(category_iri,''),policy_ref,policy_version,
   property_iri,semantic_ref,comparator,value_hash,urban_object_id);
drop index ouf_udp.identity_lookup_shape_idx;
create index identity_lookup_shape_idx on ouf_udp.identity_lookup_shape
  (tenant_id,canonical_class,coalesce(category_iri,''),policy_ref,policy_version,field_set_hash,urban_object_id);

-- One encoding for both application and trigger locks.
create function ouf_udp.identity_scope_lock_id(tenant text, class_iri text, category text)
returns bigint language sql stable as $$
  select hashtextextended('identity:' || jsonb_build_array(tenant,class_iri,category)::text,0)
$$;

create or replace function ouf_udp.lock_identity_scope() returns trigger language plpgsql as $$
declare previous_id bigint; next_id bigint;
begin
  if tg_op <> 'INSERT' then
    previous_id := ouf_udp.identity_scope_lock_id(old.tenant_id,old.canonical_type,old.category_iri);
  end if;
  if tg_op <> 'DELETE' then
    next_id := ouf_udp.identity_scope_lock_id(new.tenant_id,new.canonical_type,new.category_iri);
  end if;
  perform pg_advisory_xact_lock(least(previous_id,next_id));
  if previous_id <> next_id then
    perform pg_advisory_xact_lock(greatest(previous_id,next_id));
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end $$;

create function ouf_udp.invalidate_identity_scope(tenant text, class_iri text, category text,
    policy text default null, version text default null) returns void language plpgsql as $$
begin
  perform pg_advisory_xact_lock(ouf_udp.identity_scope_lock_id(tenant,class_iri,category));
  update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
    where tenant_id=tenant and canonical_class=class_iri and category_iri is not distinct from category
      and (policy is null or (policy_ref=policy and policy_version=version)) and complete;
end $$;

create or replace function ouf_udp.invalidate_identity_lookup_coverage() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    perform ouf_udp.invalidate_identity_scope(old.tenant_id,old.canonical_type,old.category_iri);
  end if;
  if tg_op <> 'DELETE' then
    perform ouf_udp.invalidate_identity_scope(new.tenant_id,new.canonical_type,new.category_iri);
  end if;
  return null;
end $$;
drop trigger identity_lookup_scope_change on ouf_udp.urban_object;
create trigger identity_lookup_scope_change after insert or delete or update of
  tenant_id,canonical_type,category_iri,status,current_revision_id on ouf_udp.urban_object
  for each row execute function ouf_udp.invalidate_identity_lookup_coverage();

create or replace function ouf_udp.invalidate_identity_lookup_token_coverage() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    perform ouf_udp.invalidate_identity_scope(old.tenant_id,old.canonical_class,old.category_iri,old.policy_ref,old.policy_version);
  end if;
  if tg_op <> 'DELETE' then
    perform ouf_udp.invalidate_identity_scope(new.tenant_id,new.canonical_class,new.category_iri,new.policy_ref,new.policy_version);
  end if;
  return null;
end $$;

create or replace function ouf_udp.invalidate_identity_lookup_property() returns trigger language plpgsql as $$
begin
  perform ouf_udp.invalidate_identity_scope(o.tenant_id,o.canonical_type,o.category_iri)
    from ouf_udp.object_revision r join ouf_udp.urban_object o on o.urban_object_id=r.urban_object_id
    where r.revision_id=new.revision_id and o.current_revision_id=new.revision_id;
  return null;
end $$;

create or replace function ouf_udp.invalidate_identity_lookup_projection() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    perform ouf_udp.invalidate_identity_scope(o.tenant_id,o.canonical_type,o.category_iri)
      from ouf_udp.urban_object o where o.urban_object_id=old.urban_object_id;
  end if;
  if tg_op <> 'DELETE' then
    perform ouf_udp.invalidate_identity_scope(o.tenant_id,o.canonical_type,o.category_iri)
      from ouf_udp.urban_object o where o.urban_object_id=new.urban_object_id;
  end if;
  return null;
end $$;
