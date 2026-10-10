-- Tenant attribution is required before an issue can be included in a human
-- review package. Historical rows are backfilled only when every candidate
-- still identifies an object in exactly one tenant; unresolved rows remain
-- invisible and block package preparation until an operator reconciles them.
alter table ouf_udp.resolution_issue add column tenant_id text;
create index resolution_issue_tenant_open_idx on ouf_udp.resolution_issue
  (tenant_id,issue_id) where state='OPEN';

with candidate_scope as (
  select i.issue_id,min(o.tenant_id) tenant_id,
         count(distinct o.tenant_id) tenant_count,
         count(*) resolved_count,jsonb_array_length(i.candidate_refs) ref_count
  from ouf_udp.resolution_issue i
  cross join lateral jsonb_array_elements_text(case when jsonb_typeof(i.candidate_refs)='array'
    then i.candidate_refs else '[]'::jsonb end) c(value)
  join ouf_udp.urban_object o on o.urban_object_id=case
    when c.value ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    then c.value::uuid else null end
  group by i.issue_id
)
update ouf_udp.resolution_issue i set tenant_id=s.tenant_id
from candidate_scope s where s.issue_id=i.issue_id
  and s.tenant_count=1 and s.resolved_count=s.ref_count;

create table ouf_udp.resolution_review_package_confirmation(
  package_id uuid primary key,
  tenant_id text not null,
  snapshot_hash text not null,
  issue_count integer not null check(issue_count>0),
  actor_subject text not null,
  authorization_decision_ref text not null,
  correlation_id text not null,
  confirmed_at timestamptz not null default transaction_timestamp()
);
create trigger resolution_review_package_append_only before update or delete
  on ouf_udp.resolution_review_package_confirmation for each row
  execute function ouf_udp.reject_append_only_mutation();
