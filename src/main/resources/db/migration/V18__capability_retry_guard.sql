create table ouf_udp.capability_retry_guard(
  correlation_id text not null,
  principal_subject text not null,
  tenant_id text not null,
  requested_capability text not null,
  semantic_fingerprint text not null,
  attempt_count integer not null check(attempt_count>=1),
  threshold integer not null check(threshold>=1),
  state text not null check(state in('ACTIVE','STALLED')),
  first_seen_at timestamptz not null default transaction_timestamp(),
  updated_at timestamptz not null default transaction_timestamp(),
  expires_at timestamptz not null,
  primary key(correlation_id,principal_subject,tenant_id,requested_capability,semantic_fingerprint)
) with(fillfactor=80);
create index capability_retry_guard_expiry_idx on ouf_udp.capability_retry_guard(expires_at);
