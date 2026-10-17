-- Immutable per-member provenance complements the representative contribution FK.
-- Existing rows keep their original interpretation until an explicit new profile is published.
alter table ouf_udp.property_value add column reconciliation_evidence jsonb;
alter table ouf_udp.property_value add constraint reconciliation_evidence_object
  check (reconciliation_evidence is null or jsonb_typeof(reconciliation_evidence)='object');

create table ouf_udp.handoff_property_profile(
  handoff_id text primary key references ouf_udp.handoff_intake on delete restrict,
  canonical_type text not null,
  bundle_ref text not null,
  profile jsonb not null,
  profile_hash text not null
);
create trigger handoff_property_profile_append_only before update or delete on ouf_udp.handoff_property_profile
  for each row execute function ouf_udp.reject_append_only_mutation();
