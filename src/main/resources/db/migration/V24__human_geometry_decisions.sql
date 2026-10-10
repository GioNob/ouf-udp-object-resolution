create table ouf_udp.human_geometry_decision(
  decision_id uuid primary key,
  issue_id uuid not null unique references ouf_udp.spatial_resolution_issue on delete restrict,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  property_iri text not null,
  policy_ref text not null,
  previous_geometry_revision uuid not null references ouf_udp.urban_geometry on delete restrict,
  candidate_geometry_revision uuid not null references ouf_udp.urban_geometry on delete restrict,
  chosen_geometry_revision uuid not null references ouf_udp.urban_geometry on delete restrict,
  actor_subject text not null,
  authorization_decision_ref text not null,
  reason text not null,
  correlation_id text not null,
  decided_at timestamptz not null default transaction_timestamp(),
  check(chosen_geometry_revision in(previous_geometry_revision,candidate_geometry_revision))
);
create index human_geometry_pair_idx on ouf_udp.human_geometry_decision(urban_object_id,policy_ref,previous_geometry_revision,candidate_geometry_revision);
create trigger human_geometry_decision_append_only before update or delete on ouf_udp.human_geometry_decision for each row execute function ouf_udp.reject_append_only_mutation();
