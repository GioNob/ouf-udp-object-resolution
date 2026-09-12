create extension if not exists postgis;
create extension if not exists pgcrypto;

create table ouf_udp.urban_geometry(
  geometry_revision_id uuid primary key,
  urban_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  geometry geometry(Geometry,4326) not null,
  geometry_hash text not null,
  source_crs text not null,
  canonical_srid integer not null check(canonical_srid=4326),
  normalization_version text not null,
  access_label text not null,
  evidence_json jsonb not null,
  created_at timestamptz not null default transaction_timestamp(),
  unique(urban_object_id,geometry_hash,normalization_version)
);
create index urban_geometry_gist_idx on ouf_udp.urban_geometry using gist(geometry);
create index urban_geometry_object_idx on ouf_udp.urban_geometry(urban_object_id,created_at desc);

create table ouf_udp.urban_geometry_current(
  urban_object_id uuid primary key references ouf_udp.urban_object on delete restrict,
  geometry_revision_id uuid not null unique references ouf_udp.urban_geometry on delete restrict
);

create table ouf_udp.spatial_resolution_issue(
  issue_id uuid primary key,
  handoff_id text not null references ouf_udp.handoff_intake on delete restrict,
  source_object_id uuid not null references ouf_udp.urban_object on delete restrict,
  relation_iri text,
  reason_code text not null check(reason_code in('SPATIAL_CRS_REQUIRED','SPATIAL_CRS_MISMATCH','SPATIAL_INVALID_GEOMETRY','SPATIAL_NO_MATCH','SPATIAL_MULTIPLE_MATCHES','SPATIAL_POLICY_INVALID','SELF_LOOP_NOT_ALLOWED')),
  candidate_refs jsonb not null default '[]',
  evidence_json jsonb not null,
  state text not null default 'OPEN' check(state in('OPEN','RESOLVED','DISMISSED','SUPERSEDED')),
  created_at timestamptz not null default transaction_timestamp()
);
create unique index spatial_issue_open_idx on ouf_udp.spatial_resolution_issue(handoff_id,coalesce(relation_iri,''),reason_code) where state='OPEN';

create trigger urban_geometry_append_only before update or delete on ouf_udp.urban_geometry for each row execute function ouf_udp.reject_append_only_mutation();
