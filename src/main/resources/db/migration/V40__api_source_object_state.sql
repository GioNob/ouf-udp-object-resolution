create table ouf_udp.api_source_object (
  tenant_id text not null, source_id text not null, type_code text not null, source_object_id text not null,
  epoch text not null, last_revision bigint not null check (last_revision > 0),
  state text not null check (state in ('ACTIVE','RETIRED')),
  retired_by text check (retired_by in ('EVENT','SNAPSHOT_DELETE','ABSENCE')),
  absence_snapshot_at text, -- preserve the source cut including sub-microsecond precision
  generation bigint,
  urban_object_id uuid references ouf_udp.urban_object,
  current_handoff text references ouf_udp.handoff_intake,
  primary key (tenant_id,source_id,type_code,source_object_id),
  check ((state='RETIRED') = (retired_by is not null)),
  check ((retired_by is not distinct from 'ABSENCE') = (absence_snapshot_at is not null)),
  check ((state='ACTIVE') = (current_handoff is not null))
);
create index api_object_support on ouf_udp.api_source_object(urban_object_id) where state='ACTIVE';

-- One immutable outcome per delivery, including superseded fingerprints and R4 evidence.
create table ouf_udp.api_handoff_outcome (
  handoff_id text primary key references ouf_udp.handoff_intake,
  position bigint generated always as identity unique,
  tenant_id text not null, source_id text not null, type_code text not null, source_object_id text not null,
  ingestion_run_id text not null, epoch text not null, kind text not null, revision bigint not null,
  generation bigint, rendering text not null, item_sha256 text not null, operation text not null,
  outcome text not null check (outcome in ('APPLIED','DUPLICATE','DUPLICATE_REPUBLISHED','CONFLICT',
    'SUPERSEDED','RETIRED','REACTIVATED','SEEN')),
  urban_object_id uuid references ouf_udp.urban_object,
  object_status text,
  conflict_with text references ouf_udp.api_handoff_outcome,
  check ((outcome='CONFLICT') = (conflict_with is not null))
);
create index api_observation_lookup on ouf_udp.api_handoff_outcome
  (tenant_id,source_id,type_code,source_object_id,epoch,revision);
create index api_absence_lookup on ouf_udp.api_handoff_outcome
  (tenant_id,source_id,type_code,source_object_id,epoch,generation) where kind='ABSENCE';
create index api_outcome_page on ouf_udp.api_handoff_outcome(tenant_id,source_id,ingestion_run_id,position);
create trigger api_outcome_immutable before update or delete on ouf_udp.api_handoff_outcome
  for each row execute function ouf_udp.reject_append_only_mutation();

create table ouf_udp.api_source_block (
  tenant_id text not null, source_id text not null, evidence_handoff text not null references ouf_udp.api_handoff_outcome,
  primary key(tenant_id,source_id)
);
-- The original contributions and profiles stay available for historical reads.
create table ouf_udp.api_withdrawn_support (
  handoff_id text primary key references ouf_udp.handoff_intake,
  withdrawn_by text not null references ouf_udp.handoff_intake
);
create view ouf_udp.current_property_contribution as select c.* from ouf_udp.property_contribution c
  where not exists(select 1 from ouf_udp.api_withdrawn_support w where w.handoff_id=c.handoff_id);

create table ouf_udp.api_property_profile (handoff_id text primary key references ouf_udp.handoff_intake, profile jsonb not null);

-- V39 claims READY, expired RUNNING and PAUSED with a due integrity timestamp.
-- Keep the API clock in its own column through admission, recovery and lease expiry.
alter table ouf_udp.materialization_job
  add column api_tenant_id text,
  add column api_semantics boolean not null default false,
  add column api_dispatch_at timestamptz;
create function ouf_udp.fence_api_dispatch() returns trigger language plpgsql as $$
begin
  if tg_op='INSERT' then
    select jsonb_exists(payload_json,'apiObservation') into strict new.api_semantics
      from ouf_udp.handoff_intake where handoff_id=new.handoff_id;
  end if;
  if not new.api_semantics then return new; end if;
  case new.state
    when 'READY' then
      new.state:='PAUSED';
      new.api_dispatch_at:=transaction_timestamp();
      new.next_integrity_check_at:=null;
    when 'RUNNING' then
      if new.lease_until is distinct from 'infinity'::timestamptz then
        new.api_dispatch_at:=new.lease_until;
        new.lease_until:='infinity';
      end if;
    when 'PAUSED' then
      if new.next_integrity_check_at is not null then
        new.api_dispatch_at:=new.next_integrity_check_at;
        new.next_integrity_check_at:=null;
      end if;
    else new.api_dispatch_at:=null;
  end case;
  return new;
end $$;
create trigger api_dispatch before insert or update on ouf_udp.materialization_job
  for each row execute function ouf_udp.fence_api_dispatch();
create index api_dispatch_due on ouf_udp.materialization_job(api_dispatch_at) where api_semantics;
