create table ouf_udp.lake_shadow_rebuild_plan(
  plan_id uuid primary key,
  tenant_id text not null,
  tier text not null check(tier in('RAW','NORMALIZED','CURATED','EXPORT')),
  state text not null check(state in('DRAFT','BUILDING','READY','CUTOVER','FAILED')),
  version bigint not null default 0,
  expected_object_count integer not null check(expected_object_count>0),
  verified_object_count integer not null default 0 check(verified_object_count>=0),
  baseline_digest text not null,
  shadow_digest text,
  safe_error_code text,
  created_by_subject text not null,
  created_at timestamptz not null default transaction_timestamp(),
  completed_at timestamptz,
  cutover_at timestamptz,
  cutover_by_subject text,
  authorization_decision_ref text,
  reason text,
  correlation_id text not null,
  lease_owner text,
  lease_until timestamptz,
  lease_generation bigint not null default 0,
  check((state='BUILDING')=(lease_owner is not null and lease_until is not null))
);
create unique index lake_shadow_one_active_plan on ouf_udp.lake_shadow_rebuild_plan(tenant_id,tier) where state in('DRAFT','BUILDING','READY');
create index lake_shadow_claim_idx on ouf_udp.lake_shadow_rebuild_plan(state,lease_until,created_at);

create table ouf_udp.lake_shadow_rebuild_entry(
  plan_id uuid not null references ouf_udp.lake_shadow_rebuild_plan on delete restrict,
  lake_object_id uuid not null references ouf_udp.lake_object on delete restrict,
  source_locator text not null,
  source_locator_fingerprint text not null,
  source_hash text not null,
  source_size_bytes bigint not null,
  shadow_locator text,
  state text not null check(state in('PENDING','VERIFIED','CUTOVER')),
  verified_at timestamptz,
  primary key(plan_id,lake_object_id)
);

create table ouf_udp.lake_shadow_rebuild_event(
  event_id uuid primary key,
  plan_id uuid not null references ouf_udp.lake_shadow_rebuild_plan on delete restrict,
  event_type text not null,
  actor_type text not null,
  actor_subject text not null,
  authorization_decision_ref text,
  correlation_id text not null,
  safe_detail jsonb not null default '{}',
  occurred_at timestamptz not null default transaction_timestamp()
);
create trigger lake_shadow_rebuild_event_append_only before update or delete on ouf_udp.lake_shadow_rebuild_event for each row execute function ouf_udp.reject_append_only_mutation();

create function ouf_udp.guard_lake_shadow_plan() returns trigger language plpgsql as $$
begin
  if tg_op='DELETE' then raise exception 'Shadow rebuild plan cannot be deleted'; end if;
  if old.plan_id<>new.plan_id or old.tenant_id<>new.tenant_id or old.tier<>new.tier or old.expected_object_count<>new.expected_object_count or old.baseline_digest<>new.baseline_digest or old.created_by_subject<>new.created_by_subject or old.created_at<>new.created_at or old.correlation_id<>new.correlation_id then raise exception 'Shadow rebuild plan identity is immutable'; end if;
  if old.state<>new.state and not ((old.state='DRAFT' and new.state in('BUILDING','FAILED')) or (old.state='BUILDING' and new.state in('READY','FAILED')) or (old.state='READY' and new.state in('CUTOVER','FAILED'))) then raise exception 'Invalid shadow rebuild transition % -> %',old.state,new.state; end if;
  if new.version<>old.version+1 then raise exception 'Shadow rebuild version must advance exactly once'; end if;
  return new;
end $$;
create trigger lake_shadow_rebuild_plan_guard before update or delete on ouf_udp.lake_shadow_rebuild_plan for each row execute function ouf_udp.guard_lake_shadow_plan();
