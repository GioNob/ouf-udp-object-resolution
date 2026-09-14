alter table ouf_udp.materialization_job drop constraint materialization_job_state_check;
alter table ouf_udp.materialization_job drop constraint materialization_job_check;

alter table ouf_udp.materialization_job
  add column state_version bigint not null default 0,
  add column integrity_baseline_hash text,
  add column missing_ref_hashes jsonb not null default '[]',
  add column integrity_attempts integer not null default 0,
  add column integrity_checked_at timestamptz,
  add column next_integrity_check_at timestamptz,
  add column safe_failure_code text;

alter table ouf_udp.materialization_job
  add constraint materialization_job_state_check
    check(state in('READY','RUNNING','PAUSED','QUARANTINED','SUCCEEDED','FAILED')),
  add constraint materialization_job_claim_check
    check((state='RUNNING')=(claimed_by is not null and lease_until is not null)),
  add constraint materialization_job_integrity_hash_check
    check(integrity_baseline_hash is null or integrity_baseline_hash ~ '^sha256:[0-9a-f]{64}$'),
  add constraint materialization_job_missing_refs_check
    check(jsonb_typeof(missing_ref_hashes)='array');

create index materialization_integrity_retry_idx
  on ouf_udp.materialization_job(next_integrity_check_at,created_at)
  where state='PAUSED';
