alter table ouf_udp.urban_object add column tenant_id text not null default 'default';
create index urban_object_tenant_type_status_idx on ouf_udp.urban_object(tenant_id,canonical_type,status,urban_object_id);

create table ouf_udp.serving_access_audit(
  audit_id uuid primary key,
  principal_type text not null,
  principal_subject text not null,
  tenant_id text not null,
  capability text not null,
  resource_ref text not null,
  classification text not null,
  outcome text not null check(outcome in('ALLOWED','DENIED','REDACTED')),
  authorization_decision_ref text not null,
  correlation_id text not null,
  occurred_at timestamptz not null default transaction_timestamp()
);
create trigger serving_access_audit_append_only before update or delete on ouf_udp.serving_access_audit for each row execute function ouf_udp.reject_append_only_mutation();
