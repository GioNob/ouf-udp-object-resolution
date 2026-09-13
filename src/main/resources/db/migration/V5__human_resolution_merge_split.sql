alter table ouf_udp.urban_object drop constraint urban_object_status_check;
alter table ouf_udp.urban_object add constraint urban_object_status_check check(status in('ACTIVE','MERGED','SPLIT','RETIRED','SUPERSEDED','DELETED'));
alter table ouf_udp.urban_object add column redirect_to uuid references ouf_udp.urban_object on delete restrict;
alter table ouf_udp.source_binding add column state text not null default 'ACTIVE' check(state in('ACTIVE','UNRESOLVED','RETIRED'));

alter table ouf_udp.resolution_issue add column version bigint not null default 0;
alter table ouf_udp.resolution_issue add column resolved_at timestamptz;
alter table ouf_udp.resolution_issue add column resolved_by_subject text;
alter table ouf_udp.resolution_issue add column resolution_reason text;
alter table ouf_udp.resolution_issue add column authorization_decision_ref text;

create table ouf_udp.human_resolution_decision(
  decision_id uuid primary key,
  issue_id uuid not null unique references ouf_udp.resolution_issue on delete restrict,
  action text not null check(action in('APPROVE_MATCH','DISMISS')),
  target_urban_object_id uuid references ouf_udp.urban_object on delete restrict,
  actor_subject text not null,
  authorization_decision_ref text not null,
  reason text not null,
  evidence_hash text not null,
  decided_at timestamptz not null default transaction_timestamp(),
  check((action='APPROVE_MATCH')=(target_urban_object_id is not null))
);

create table ouf_udp.governance_plan(
  plan_id uuid primary key,
  action text not null check(action in('MERGE','SPLIT')),
  subject_object_ids jsonb not null,
  proposal_json jsonb not null,
  impact_json jsonb not null,
  state text not null default 'DRAFT' check(state in('DRAFT','EXECUTED','CANCELLED')),
  version bigint not null default 0,
  created_by_subject text not null,
  created_at timestamptz not null default transaction_timestamp(),
  executed_at timestamptz,
  decision_ref text,
  reason text,
  idempotency_key text unique
);

create table ouf_udp.object_identity_history(
  history_id uuid primary key,
  object_id uuid not null references ouf_udp.urban_object on delete restrict,
  transition text not null check(transition in('MERGED','SPLIT','RETIRED')),
  successor_ids jsonb not null,
  plan_id uuid not null references ouf_udp.governance_plan on delete restrict,
  decision_ref text not null,
  occurred_at timestamptz not null default transaction_timestamp(),
  unique(object_id,plan_id)
);

create table ouf_udp.source_binding_history(
  history_id uuid primary key,
  source_id text not null,
  type_code text not null,
  source_object_id text not null,
  previous_urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  new_urban_object_id uuid references ouf_udp.urban_object on delete restrict,
  disposition text not null check(disposition in('REALLOCATED','UNRESOLVED')),
  plan_id uuid not null references ouf_udp.governance_plan on delete restrict,
  occurred_at timestamptz not null default transaction_timestamp()
);

create table ouf_udp.relationship_identity_history(
  history_id uuid primary key,
  relationship_id uuid not null references ouf_udp.urban_relationship on delete restrict,
  previous_source_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  previous_target_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  disposition text not null check(disposition in('REPOINTED','SUPERSEDED','REVIEW_REQUIRED')),
  plan_id uuid not null references ouf_udp.governance_plan on delete restrict,
  occurred_at timestamptz not null default transaction_timestamp()
);

create table ouf_udp.split_resolution_issue(
  issue_id uuid primary key,
  plan_id uuid not null references ouf_udp.governance_plan on delete restrict,
  issue_type text not null check(issue_type in('BINDING_UNRESOLVED','RELATIONSHIP_REVIEW_REQUIRED')),
  resource_ref text not null,
  evidence_json jsonb not null,
  state text not null default 'OPEN' check(state in('OPEN','RESOLVED','DISMISSED')),
  created_at timestamptz not null default transaction_timestamp()
);

create table ouf_udp.governance_audit(
  audit_id uuid primary key,
  action text not null,
  outcome text not null,
  plan_id uuid,
  actor_type text not null,
  actor_subject text not null,
  tenant_id text not null,
  capability text not null,
  authorization_decision_ref text not null,
  reason text,
  correlation_id text not null,
  evidence_hash text not null,
  occurred_at timestamptz not null default transaction_timestamp()
);

create trigger governance_audit_append_only before update or delete on ouf_udp.governance_audit for each row execute function ouf_udp.reject_append_only_mutation();
create trigger identity_history_append_only before update or delete on ouf_udp.object_identity_history for each row execute function ouf_udp.reject_append_only_mutation();
create trigger source_binding_history_append_only before update or delete on ouf_udp.source_binding_history for each row execute function ouf_udp.reject_append_only_mutation();
create trigger relationship_identity_history_append_only before update or delete on ouf_udp.relationship_identity_history for each row execute function ouf_udp.reject_append_only_mutation();
create trigger human_resolution_decision_append_only before update or delete on ouf_udp.human_resolution_decision for each row execute function ouf_udp.reject_append_only_mutation();
