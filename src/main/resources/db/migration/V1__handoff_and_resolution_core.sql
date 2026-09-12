create schema if not exists ouf_udp;

create table ouf_udp.handoff_intake(
  handoff_id text primary key,
  ingestion_run_id text not null,
  ingestion_id text not null,
  source_id text not null,
  type_code text not null,
  source_object_id text not null,
  content_hash text not null,
  payload_json jsonb not null,
  receipt_ref text not null unique,
  state text not null default 'DURABLE' check(state in('DURABLE','PROCESSING','PROCESSED','FAILED')),
  received_at timestamptz not null default transaction_timestamp(),
  processed_at timestamptz,
  failure_code text
);

create table ouf_udp.materialization_job(
  job_id uuid primary key,
  handoff_id text not null unique references ouf_udp.handoff_intake on delete restrict,
  state text not null default 'READY' check(state in('READY','RUNNING','SUCCEEDED','FAILED')),
  attempts integer not null default 0,
  claimed_by text,
  lease_until timestamptz,
  created_at timestamptz not null default transaction_timestamp(),
  updated_at timestamptz not null default transaction_timestamp(),
  check((state='RUNNING')=(claimed_by is not null and lease_until is not null))
);
create index materialization_claim_idx on ouf_udp.materialization_job(state,lease_until,created_at);

create table ouf_udp.urban_object(
  urban_object_id uuid primary key,
  canonical_type text not null,
  canonical_key text,
  match_key text,
  status text not null default 'ACTIVE' check(status in('ACTIVE','SUPERSEDED','DELETED')),
  revision bigint not null default 1,
  created_at timestamptz not null default transaction_timestamp(),
  updated_at timestamptz not null default transaction_timestamp(),
  unique(canonical_type,canonical_key)
);
create index urban_object_match_idx on ouf_udp.urban_object(canonical_type,match_key) where status='ACTIVE';

create table ouf_udp.source_binding(
  source_id text not null,
  type_code text not null,
  source_object_id text not null,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  first_handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  created_at timestamptz not null default transaction_timestamp(),
  primary key(source_id,type_code,source_object_id)
);

create table ouf_udp.resolution_decision(
  resolution_decision_id uuid primary key,
  handoff_id text not null unique references ouf_udp.handoff_intake on delete restrict,
  candidate_ref text not null,
  outcome text not null check(outcome in('MATCH','NEW_OBJECT','REVIEW_REQUIRED','REJECTED')),
  target_urban_object_id uuid references ouf_udp.urban_object on delete restrict,
  strategy_id text not null,
  strategy_version text not null,
  evidence_refs jsonb not null,
  confidence numeric(6,5),
  decided_by text not null,
  decided_at timestamptz not null default transaction_timestamp(),
  policy_ref text not null,
  check((outcome='MATCH')=(target_urban_object_id is not null))
);

create table ouf_udp.resolution_issue(
  issue_id uuid primary key,
  resolution_decision_id uuid not null unique references ouf_udp.resolution_decision on delete restrict,
  handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  reason_code text not null,
  candidate_refs jsonb not null,
  evidence_refs jsonb not null,
  state text not null default 'OPEN' check(state in('OPEN','RESOLVED','DISMISSED')),
  created_at timestamptz not null default transaction_timestamp()
);

create table ouf_udp.handoff_event(
  event_id uuid primary key,
  handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  event_type text not null,
  safe_detail jsonb not null default '{}',
  created_at timestamptz not null default transaction_timestamp()
);

create function ouf_udp.reject_append_only_mutation() returns trigger language plpgsql as $$
begin raise exception using errcode='23001',message='UDP evidence is append-only'; end$$;
create trigger resolution_decision_append_only before update or delete on ouf_udp.resolution_decision for each row execute function ouf_udp.reject_append_only_mutation();
create trigger handoff_event_append_only before update or delete on ouf_udp.handoff_event for each row execute function ouf_udp.reject_append_only_mutation();
