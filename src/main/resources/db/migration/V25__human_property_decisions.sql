alter table ouf_udp.property_conflict add column evidence_hash text;
alter table ouf_udp.property_conflict add column materialization_profile jsonb;
create unique index property_conflict_evidence_idx on ouf_udp.property_conflict(urban_object_id,property_iri,policy_ref,evidence_hash) where evidence_hash is not null;
create table ouf_udp.human_property_decision(
  decision_id uuid primary key,
  conflict_id uuid not null unique references ouf_udp.property_conflict on delete restrict,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  property_iri text not null,
  policy_ref text not null,
  evidence_hash text not null,
  chosen_contribution uuid not null references ouf_udp.property_contribution on delete restrict,
  chosen_evidence_hash text not null,
  expected_revision uuid not null references ouf_udp.object_revision on delete restrict,
  actor_subject text not null,
  authorization_decision_ref text not null,
  reason text not null,
  correlation_id text not null,
  decided_at timestamptz not null default transaction_timestamp()
);
create index human_property_choice_idx on ouf_udp.human_property_decision(urban_object_id,property_iri,policy_ref,evidence_hash);
create trigger human_property_decision_append_only before update or delete on ouf_udp.human_property_decision for each row execute function ouf_udp.reject_append_only_mutation();
