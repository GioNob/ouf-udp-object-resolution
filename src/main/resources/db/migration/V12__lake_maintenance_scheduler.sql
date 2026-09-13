create table ouf_udp.lake_maintenance_schedule(
  task_name text primary key,
  state text not null check(state in('READY','RUNNING','PAUSED')),
  next_run_at timestamptz not null,
  interval_seconds integer not null check(interval_seconds between 60 and 2592000),
  lease_owner text,
  lease_until timestamptz,
  lease_generation bigint not null default 0,
  consecutive_failures integer not null default 0 check(consecutive_failures>=0),
  last_safe_error_code text,
  last_scan_id uuid,
  last_started_at timestamptz,
  last_completed_at timestamptz,
  updated_at timestamptz not null default transaction_timestamp(),
  check((state='RUNNING')=(lease_owner is not null and lease_until is not null))
);
create index lake_maintenance_claim_idx on ouf_udp.lake_maintenance_schedule(state,next_run_at,lease_until,task_name);

create table ouf_udp.lake_maintenance_attempt(
  attempt_id uuid primary key,
  task_name text not null references ouf_udp.lake_maintenance_schedule on delete restrict,
  lease_generation bigint not null,
  worker_id text not null,
  state text not null check(state in('SUCCEEDED','FAILED','LEASE_LOST')),
  scan_id uuid,
  missing_count integer,
  checksum_mismatch_count integer,
  orphan_count integer,
  safe_error_code text,
  started_at timestamptz not null,
  completed_at timestamptz not null default transaction_timestamp()
);
create index lake_maintenance_attempt_history_idx on ouf_udp.lake_maintenance_attempt(task_name,started_at desc);
create trigger lake_maintenance_attempt_append_only before update or delete on ouf_udp.lake_maintenance_attempt for each row execute function ouf_udp.reject_append_only_mutation();

insert into ouf_udp.lake_maintenance_schedule(task_name,state,next_run_at,interval_seconds)
values('STORAGE_RECONCILIATION','READY',transaction_timestamp(),86400);
