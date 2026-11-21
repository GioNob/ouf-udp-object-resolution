-- Existing revisions and their immutable foreign keys stay intact. Only the revision
-- number identifies an occurrence of a state in the object's ordered history.
do $migration$
declare hash_constraint name;
begin
  select conname into strict hash_constraint from pg_constraint
    where conrelid='ouf_udp.object_revision'::regclass and contype='u'
      and pg_get_constraintdef(oid)='UNIQUE (urban_object_id, canonical_hash, authority_hash)';
  execute format('alter table ouf_udp.object_revision drop constraint %I',hash_constraint);
end $migration$;

-- V1 keys were global across tenants and across retired objects. The old
-- uniqueness guarantees existing live rows satisfy the narrower index.
create unique index urban_object_active_canonical_key on ouf_udp.urban_object
  (tenant_id,canonical_type,canonical_key) where status='ACTIVE';
alter table ouf_udp.urban_object drop constraint urban_object_canonical_type_canonical_key_key;

-- Existing lake:// references denote copy zero forever. A later upload may
-- create a separately verified copy without altering quarantined bytes or lineage.
alter table ouf_udp.lake_object add column copy_no bigint not null default 0 check(copy_no>=0);
alter table ouf_udp.lake_object add constraint lake_object_scoped_copy_key unique(tenant_id,tier,content_hash,source_id,type_code,copy_no);
alter table ouf_udp.lake_object drop constraint lake_object_tenant_id_tier_content_hash_source_id_type_code_key;

create trigger lake_object_copy_immutable before update of copy_no on ouf_udp.lake_object
  for each row when (old.copy_no is distinct from new.copy_no)
  execute function ouf_udp.reject_append_only_mutation();

-- Keep ambiguous pre-V30 rows owner-visible for attribution without exposing
-- their identifiers or even their presence through a tenant's review package.
create view ouf_udp.unattributed_resolution_issue as
  select issue_id,version,created_at from ouf_udp.resolution_issue where tenant_id is null and state='OPEN';
revoke all on ouf_udp.unattributed_resolution_issue from public;

-- OPEN reviews from production can use the same versioned reevaluation action.
alter table ouf_udp.resolution_issue add column reevaluation_pending boolean not null default false;

-- Retain the evidence a HUMAN previously saw when an issue is reassessed.
-- The first update of an existing issue archives its original package version.
create table ouf_udp.resolution_issue_evidence_history(
  issue_id uuid not null references ouf_udp.resolution_issue,
  version bigint not null,
  reason_code text not null,
  candidate_refs jsonb not null,
  evidence_refs jsonb not null,
  archived_at timestamptz not null default transaction_timestamp(),
  primary key(issue_id,version)
);
create function ouf_udp.archive_review_evidence() returns trigger language plpgsql as $$
begin
  insert into ouf_udp.resolution_issue_evidence_history(issue_id,version,reason_code,candidate_refs,evidence_refs)
    values(old.issue_id,old.version,old.reason_code,old.candidate_refs,old.evidence_refs);
  return new;
end $$;
create trigger archive_resolution_review before update of candidate_refs,evidence_refs,reason_code on ouf_udp.resolution_issue
  for each row when (new.version>old.version) execute function ouf_udp.archive_review_evidence();
create trigger resolution_issue_evidence_immutable before update or delete on ouf_udp.resolution_issue_evidence_history
  for each row execute function ouf_udp.reject_append_only_mutation();

-- A stale worker could mark the intake FAILED after a successful committed
-- completion. Reconcile only when that completion and its observation agree.
with repaired as (
  update ouf_udp.handoff_intake h set state='PROCESSED',failure_code=null
  from ouf_udp.materialization_job j
  where j.handoff_id=h.handoff_id and j.state='SUCCEEDED' and h.state='FAILED'
    and h.processed_at is not null
    and exists(select 1 from ouf_udp.materialization_observation o where o.handoff_id=h.handoff_id and o.revision_id is not null)
    and exists(select 1 from ouf_udp.handoff_event e where e.handoff_id=h.handoff_id and e.event_type='RESOLUTION_COMPLETED')
  returning h.handoff_id
)
insert into ouf_udp.handoff_event(event_id,handoff_id,event_type,safe_detail)
select gen_random_uuid(),handoff_id,'INTAKE_COMPLETION_RECONCILED','{"migration":"V37"}'::jsonb from repaired;
