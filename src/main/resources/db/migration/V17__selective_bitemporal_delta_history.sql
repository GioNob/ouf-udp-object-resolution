alter table ouf_udp.object_revision
  alter column canonical_payload drop not null,
  add column representation text not null default 'FULL_CHECKPOINT',
  add column base_revision_id uuid references ouf_udp.object_revision on delete restrict,
  add column canonical_delta jsonb,
  add column change_fingerprint text;

alter table ouf_udp.object_revision
  add constraint object_revision_representation_check check(
    (representation='FULL_CHECKPOINT' and canonical_payload is not null and canonical_delta is null)
    or
    (representation='DELTA_PATCH' and canonical_payload is null and canonical_delta is not null and base_revision_id is not null)
  );

create table ouf_udp.urban_object_current_state(
  urban_object_id uuid primary key references ouf_udp.urban_object on delete restrict,
  revision_id uuid not null unique references ouf_udp.object_revision on delete restrict,
  canonical_hash text not null,
  authority_hash text not null,
  authority_state jsonb not null,
  canonical_payload jsonb not null,
  access_labels jsonb not null,
  change_fingerprint text,
  updated_at timestamptz not null default transaction_timestamp()
);
insert into ouf_udp.urban_object_current_state(urban_object_id,revision_id,canonical_hash,authority_hash,authority_state,canonical_payload,access_labels)
select o.urban_object_id,r.revision_id,r.canonical_hash,r.authority_hash,r.authority_state,r.canonical_payload,r.access_labels
from ouf_udp.urban_object o join ouf_udp.object_revision r on r.revision_id=o.current_revision_id;

alter table ouf_udp.materialization_observation add column change_fingerprint text;

create table ouf_udp.property_bitemporal_interval(
  interval_id uuid primary key,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  property_iri text not null,
  revision_id uuid not null references ouf_udp.object_revision on delete restrict,
  contribution_id uuid not null references ouf_udp.property_contribution on delete restrict,
  value_hash text not null,
  valid_from timestamptz,
  valid_to timestamptz,
  system_from timestamptz not null default transaction_timestamp(),
  system_to timestamptz,
  policy_ref text not null,
  check(valid_to is null or valid_from is null or valid_to>valid_from),
  check(system_to is null or system_to>=system_from)
);
create unique index property_bitemporal_open_idx on ouf_udp.property_bitemporal_interval(urban_object_id,property_iri) where system_to is null;
create index property_bitemporal_history_idx on ouf_udp.property_bitemporal_interval(urban_object_id,property_iri,system_from desc);

create function ouf_udp.guard_bitemporal_interval_mutation() returns trigger language plpgsql as $$
begin
  if tg_op='DELETE' then raise exception 'Bitemporal interval cannot be deleted'; end if;
  if old.interval_id<>new.interval_id or old.urban_object_id<>new.urban_object_id or old.property_iri<>new.property_iri
     or old.revision_id<>new.revision_id or old.contribution_id<>new.contribution_id or old.value_hash<>new.value_hash
     or old.valid_from is distinct from new.valid_from or old.valid_to is distinct from new.valid_to
     or old.system_from<>new.system_from or old.policy_ref<>new.policy_ref
     or old.system_to is not null or new.system_to is null then
    raise exception 'Bitemporal interval is immutable except for one system close';
  end if;
  return new;
end $$;
create trigger property_bitemporal_interval_guard before update or delete on ouf_udp.property_bitemporal_interval
  for each row execute function ouf_udp.guard_bitemporal_interval_mutation();
