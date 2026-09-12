create table ouf_udp.object_revision(
  revision_id uuid primary key,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  revision_no bigint not null,
  canonical_hash text not null,
  authority_hash text not null,
  canonical_payload jsonb not null,
  access_labels jsonb not null,
  source_handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  bundle_ref text not null,
  semantic_publication_ref text not null,
  observed_at timestamptz,
  valid_from timestamptz,
  valid_to timestamptz,
  recorded_at timestamptz not null default transaction_timestamp(),
  unique(urban_object_id,revision_no),
  unique(urban_object_id,canonical_hash,authority_hash)
);

alter table ouf_udp.urban_object add column current_revision_id uuid references ouf_udp.object_revision on delete restrict;

create table ouf_udp.property_contribution(
  contribution_id uuid primary key,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  property_iri text not null,
  value_json jsonb not null,
  value_hash text not null,
  access_label text not null,
  source_id text not null,
  authority_rank integer not null,
  provenance_json jsonb not null,
  observed_at timestamptz,
  created_at timestamptz not null default transaction_timestamp(),
  unique(handoff_id,property_iri)
);
create index property_contribution_current_idx on ouf_udp.property_contribution(urban_object_id,property_iri,authority_rank,created_at desc);

create table ouf_udp.property_value(
  property_value_id uuid primary key,
  revision_id uuid not null references ouf_udp.object_revision on delete restrict,
  property_iri text not null,
  value_json jsonb not null,
  datatype text not null,
  access_label text not null,
  contribution_id uuid not null references ouf_udp.property_contribution on delete restrict,
  unique(revision_id,property_iri)
);

create table ouf_udp.property_conflict(
  conflict_id uuid primary key,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  property_iri text not null,
  contribution_refs jsonb not null,
  policy_ref text not null,
  state text not null default 'OPEN' check(state in('OPEN','RESOLVED','DISMISSED')),
  created_at timestamptz not null default transaction_timestamp()
);

create table ouf_udp.materialization_observation(
  observation_id uuid primary key,
  handoff_id text not null unique references ouf_udp.handoff_intake on delete restrict,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  revision_id uuid references ouf_udp.object_revision on delete restrict,
  material_change boolean not null,
  canonical_hash text not null,
  created_at timestamptz not null default transaction_timestamp()
);

create trigger object_revision_append_only before update or delete on ouf_udp.object_revision for each row execute function ouf_udp.reject_append_only_mutation();
create trigger property_contribution_append_only before update or delete on ouf_udp.property_contribution for each row execute function ouf_udp.reject_append_only_mutation();
create trigger property_value_append_only before update or delete on ouf_udp.property_value for each row execute function ouf_udp.reject_append_only_mutation();
create trigger materialization_observation_append_only before update or delete on ouf_udp.materialization_observation for each row execute function ouf_udp.reject_append_only_mutation();
