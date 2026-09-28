create table ouf_udp.identity_preactivation_attestation(
  attestation_id uuid primary key,
  configuration_hash text not null check(configuration_hash ~ '^sha256:[0-9a-f]{64}$'),
  tenant_id text not null,
  source_id text not null,
  canonical_class text not null,
  policy_ref text not null,
  policy_version text not null,
  policy_fingerprint text not null,
  coverage_ref text not null,
  indexed_objects bigint not null check(indexed_objects>=0),
  actor_subject text not null,
  created_at timestamptz not null default transaction_timestamp()
);
create index identity_preactivation_lookup_idx on ouf_udp.identity_preactivation_attestation
  (configuration_hash,tenant_id,source_id,created_at desc);
