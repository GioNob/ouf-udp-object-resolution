create table ouf_udp.merge_resolution_issue(
  issue_id uuid primary key,
  plan_id uuid not null references ouf_udp.governance_plan on delete restrict,
  relationship_id uuid not null references ouf_udp.urban_relationship on delete restrict,
  reason_code text not null check(reason_code in('SELF_LOOP_NOT_ALLOWED')),
  evidence_json jsonb not null,
  state text not null default 'OPEN' check(state in('OPEN','RESOLVED','DISMISSED')),
  created_at timestamptz not null default transaction_timestamp(),
  unique(plan_id,relationship_id,reason_code)
);

create table ouf_udp.merge_property_contribution_link(
  link_id uuid primary key,
  plan_id uuid not null references ouf_udp.governance_plan on delete restrict,
  survivor_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  contribution_id uuid not null references ouf_udp.property_contribution on delete restrict,
  disposition text not null default 'REEVALUATE_AUTHORITY' check(disposition in('REEVALUATE_AUTHORITY','ACCEPTED','CONFLICT')),
  created_at timestamptz not null default transaction_timestamp(),
  unique(plan_id,contribution_id)
);

create trigger merge_property_link_append_only before update or delete on ouf_udp.merge_property_contribution_link for each row execute function ouf_udp.reject_append_only_mutation();
