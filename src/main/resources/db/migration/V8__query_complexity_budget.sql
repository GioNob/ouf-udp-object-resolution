create table ouf_udp.query_budget(
  correlation_id text not null,
  principal_subject text not null,
  tenant_id text not null,
  workload_class text not null,
  window_started_at timestamptz not null default transaction_timestamp(),
  expires_at timestamptz not null,
  graph_calls integer not null default 0,
  query_calls integer not null default 0,
  mismatch_calls integer not null default 0,
  nodes_observed bigint not null default 0,
  edges_observed bigint not null default 0,
  db_time_ms bigint not null default 0,
  result_bytes bigint not null default 0,
  lock_version bigint not null default 0,
  primary key(correlation_id,principal_subject,tenant_id,workload_class)
) with(fillfactor=80);
create index query_budget_expiry_idx on ouf_udp.query_budget(expires_at);
