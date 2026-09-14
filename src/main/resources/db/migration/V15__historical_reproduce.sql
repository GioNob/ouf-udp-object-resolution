create table ouf_udp.replay_plan(
  replay_plan_id uuid primary key,
  tenant_id text not null,
  source_handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  raw_lake_object_id uuid not null references ouf_udp.lake_object on delete restrict,
  mode text not null check(mode in('REPRODUCE')),
  state text not null check(state in('READY','PAUSED','SUCCEEDED','ABORTED')),
  version bigint not null default 0,
  baseline_refs jsonb not null,
  baseline_hash text,
  source_payload_hash text not null,
  missing_ref_hashes jsonb not null default '[]',
  reason text not null,
  created_by_subject text not null,
  authorization_decision_ref text not null,
  correlation_id text not null,
  replay_handoff_id text unique references ouf_udp.handoff_intake on delete restrict,
  created_at timestamptz not null default transaction_timestamp(),
  completed_at timestamptz,
  aborted_at timestamptz
);
create index replay_plan_source_idx on ouf_udp.replay_plan(tenant_id,source_handoff_id,created_at);

create table ouf_udp.replay_event(
  event_id uuid primary key,
  replay_plan_id uuid not null references ouf_udp.replay_plan on delete restrict,
  event_type text not null,
  actor_type text not null,
  actor_subject text not null,
  authorization_decision_ref text not null,
  correlation_id text not null,
  safe_detail jsonb not null default '{}',
  occurred_at timestamptz not null default transaction_timestamp()
);
create trigger replay_event_append_only before update or delete on ouf_udp.replay_event
  for each row execute function ouf_udp.reject_append_only_mutation();

create function ouf_udp.guard_replay_plan_mutation() returns trigger language plpgsql as $$
begin
  if tg_op='DELETE' then raise exception 'Replay plan cannot be deleted'; end if;
  if old.replay_plan_id<>new.replay_plan_id
     or old.tenant_id<>new.tenant_id
     or old.source_handoff_id<>new.source_handoff_id
     or old.raw_lake_object_id<>new.raw_lake_object_id
     or old.mode<>new.mode
     or old.baseline_refs<>new.baseline_refs
     or old.source_payload_hash<>new.source_payload_hash
     or old.reason<>new.reason
     or old.created_by_subject<>new.created_by_subject
     or old.authorization_decision_ref<>new.authorization_decision_ref
     or old.correlation_id<>new.correlation_id
     or old.created_at<>new.created_at then
    raise exception 'Replay plan immutable fields cannot change';
  end if;
  if new.version<>old.version+1 then raise exception 'Replay plan version must advance exactly once'; end if;
  if old.state='SUCCEEDED' or old.state='ABORTED' then raise exception 'Replay plan is terminal'; end if;
  if not ((old.state='PAUSED' and new.state in('READY','ABORTED'))
       or (old.state='READY' and new.state in('PAUSED','SUCCEEDED','ABORTED'))) then
    raise exception 'Invalid replay plan state transition % -> %',old.state,new.state;
  end if;
  return new;
end $$;
create trigger replay_plan_guard before update or delete on ouf_udp.replay_plan
  for each row execute function ouf_udp.guard_replay_plan_mutation();
