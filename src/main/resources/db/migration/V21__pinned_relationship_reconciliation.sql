drop index ouf_udp.relationship_issue_open_idx;
create unique index relationship_issue_open_idx on ouf_udp.relationship_issue(contribution_id) where state='OPEN';

-- Original handoff and profile remain immutable. Only scheduling/currentness is mutable.
create table ouf_udp.relationship_reconciliation(
  task_id uuid primary key,
  handoff_id text not null references ouf_udp.handoff_intake,
  source_object_id uuid not null references ouf_udp.urban_object,
  binding_key text not null,
  profile_json jsonb not null,
  active boolean not null default true,
  next_attempt_at timestamptz not null default transaction_timestamp(),
  unique(handoff_id,source_object_id)
);
create unique index relationship_current_binding on ouf_udp.relationship_reconciliation(binding_key) where active;
create index relationship_reconcile_due on ouf_udp.relationship_reconciliation(next_attempt_at,task_id) where active;
create table ouf_udp.relationship_current_support(
  task_id uuid not null references ouf_udp.relationship_reconciliation,
  relationship_id uuid not null references ouf_udp.urban_relationship,
  revision_id uuid not null references ouf_udp.relationship_revision,
  active boolean not null default true,
  primary key(task_id,relationship_id)
);
create index relationship_support_edge on ouf_udp.relationship_current_support(relationship_id) where active;
