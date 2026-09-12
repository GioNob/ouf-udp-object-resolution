create table ouf_udp.urban_relationship(
  relationship_id uuid primary key,
  source_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  relation_iri text not null,
  target_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  current_revision_id uuid,
  status text not null default 'ACTIVE' check(status in('ACTIVE','SUPERSEDED')),
  created_at timestamptz not null default transaction_timestamp(),
  unique(source_object_id,relation_iri,target_object_id)
);
create index relationship_outbound_idx on ouf_udp.urban_relationship(source_object_id,relation_iri);
create index relationship_inbound_idx on ouf_udp.urban_relationship(target_object_id,relation_iri);

create table ouf_udp.relationship_contribution(
  contribution_id uuid primary key,
  handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  source_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  relation_iri text not null,
  source_value jsonb not null,
  source_value_hash text not null,
  strategy_ref text not null,
  policy_ref text not null,
  access_label text not null,
  provenance_json jsonb not null,
  created_at timestamptz not null default transaction_timestamp(),
  unique(handoff_id,relation_iri,source_value_hash)
);

create table ouf_udp.relationship_revision(
  relationship_revision_id uuid primary key,
  relationship_id uuid not null references ouf_udp.urban_relationship on delete restrict,
  revision_no bigint not null,
  contribution_id uuid not null references ouf_udp.relationship_contribution on delete restrict,
  evidence_hash text not null,
  resolution_evidence jsonb not null,
  access_label text not null,
  valid_from timestamptz,
  valid_to timestamptz,
  created_at timestamptz not null default transaction_timestamp(),
  unique(relationship_id,revision_no),
  unique(relationship_id,evidence_hash)
);
alter table ouf_udp.urban_relationship add constraint relationship_current_revision_fk foreign key(current_revision_id) references ouf_udp.relationship_revision on delete restrict;

create table ouf_udp.relationship_issue(
  issue_id uuid primary key,
  handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  contribution_id uuid not null references ouf_udp.relationship_contribution on delete restrict,
  source_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  relation_iri text not null,
  reason_code text not null check(reason_code in('NO_MATCH','MULTIPLE_MATCHES','TARGET_NOT_ACTIVE','SELF_LOOP_NOT_ALLOWED')),
  on_no_match text not null,
  candidate_refs jsonb not null,
  evidence_refs jsonb not null,
  state text not null default 'OPEN' check(state in('OPEN','RESOLVED','DISMISSED','SUPERSEDED')),
  created_at timestamptz not null default transaction_timestamp(),
  unique(handoff_id,relation_iri,contribution_id)
);
create unique index relationship_issue_open_idx on ouf_udp.relationship_issue(source_object_id,relation_iri,reason_code) where state='OPEN';

create trigger relationship_contribution_append_only before update or delete on ouf_udp.relationship_contribution for each row execute function ouf_udp.reject_append_only_mutation();
create trigger relationship_revision_append_only before update or delete on ouf_udp.relationship_revision for each row execute function ouf_udp.reject_append_only_mutation();
