create table ouf_udp.lake_object(
  lake_object_id uuid primary key,
  tenant_id text not null,
  source_id text not null,
  type_code text not null,
  tier text not null check(tier in('RAW','NORMALIZED','CURATED','EXPORT')),
  content_hash text not null,
  media_type text not null,
  logical_size_bytes bigint not null check(logical_size_bytes>=0),
  locator text not null,
  previous_locator text,
  state text not null check(state in('STAGED','DURABLE','VERIFIED','COMPACTED','COLD','QUARANTINED','DELETION_PENDING','DELETED','FAILED')),
  retention_class text not null check(retention_class in('OPERATIONAL','AUDIT','ARCHIVAL','LEGAL_HOLD','DERIVED_REBUILDABLE')),
  access_label text not null,
  retention_until timestamptz not null,
  legal_hold boolean not null default false,
  blocking_lineage_refs integer not null default 0 check(blocking_lineage_refs>=0),
  historical_replay_required boolean not null default false,
  active_export_refs integer not null default 0 check(active_export_refs>=0),
  verified_at timestamptz,
  purge_after timestamptz,
  deleted_at timestamptz,
  lease_owner text,
  lease_until timestamptz,
  lease_generation bigint not null default 0,
  state_version bigint not null default 0,
  created_at timestamptz not null default transaction_timestamp(),
  unique(tenant_id,tier,content_hash,source_id,type_code)
);
create index lake_object_lifecycle_idx on ouf_udp.lake_object(state,retention_until,lake_object_id);
create index lake_object_source_idx on ouf_udp.lake_object(tenant_id,source_id,type_code,tier,state);

create table ouf_udp.lake_object_event(
  event_id uuid primary key,
  lake_object_id uuid not null references ouf_udp.lake_object on delete restrict,
  event_type text not null,
  from_state text,
  to_state text,
  actor_subject text not null,
  authorization_decision_ref text,
  correlation_id text not null,
  safe_detail jsonb not null default '{}',
  occurred_at timestamptz not null default transaction_timestamp()
);

create table ouf_udp.compaction_manifest(
  manifest_id uuid primary key,
  tenant_id text not null,
  output_lake_object_id uuid not null references ouf_udp.lake_object on delete restrict,
  output_content_hash text not null,
  state text not null check(state in('STAGED','VERIFIED','FAILED','ROLLED_BACK')),
  created_at timestamptz not null default transaction_timestamp(),
  verified_at timestamptz
);
create table ouf_udp.compaction_manifest_entry(
  manifest_id uuid not null references ouf_udp.compaction_manifest on delete restrict,
  input_lake_object_id uuid not null references ouf_udp.lake_object on delete restrict,
  logical_offset bigint not null check(logical_offset>=0),
  logical_length bigint not null check(logical_length>=0),
  primary key(manifest_id,input_lake_object_id)
);

create table ouf_udp.storage_reconciliation_finding(
  finding_id uuid primary key,
  finding_type text not null check(finding_type in('MISSING_OBJECT','ORPHAN_STORAGE','CHECKSUM_MISMATCH','DANGLING_LINEAGE','INVALID_MANIFEST')),
  severity text not null check(severity in('MEDIUM','HIGH','CRITICAL')),
  lake_object_id uuid references ouf_udp.lake_object on delete restrict,
  locator_fingerprint text not null,
  safe_evidence jsonb not null,
  scan_id uuid not null,
  occurred_at timestamptz not null default transaction_timestamp()
);
create index storage_finding_scan_idx on ouf_udp.storage_reconciliation_finding(scan_id,finding_type);

create trigger lake_object_event_append_only before update or delete on ouf_udp.lake_object_event for each row execute function ouf_udp.reject_append_only_mutation();
create trigger compaction_manifest_append_only before update or delete on ouf_udp.compaction_manifest for each row execute function ouf_udp.reject_append_only_mutation();
create trigger compaction_manifest_entry_append_only before update or delete on ouf_udp.compaction_manifest_entry for each row execute function ouf_udp.reject_append_only_mutation();
create trigger storage_reconciliation_finding_append_only before update or delete on ouf_udp.storage_reconciliation_finding for each row execute function ouf_udp.reject_append_only_mutation();

create function ouf_udp.guard_lake_object_mutation() returns trigger language plpgsql as $$
begin
  if tg_op='DELETE' then raise exception 'LakeObject metadata cannot be deleted'; end if;
  if old.lake_object_id<>new.lake_object_id or old.tenant_id<>new.tenant_id or old.source_id<>new.source_id or old.type_code<>new.type_code or old.tier<>new.tier or old.content_hash<>new.content_hash or old.media_type<>new.media_type or old.logical_size_bytes<>new.logical_size_bytes or old.retention_class<>new.retention_class or old.access_label<>new.access_label or old.created_at<>new.created_at then raise exception 'LakeObject identity is immutable'; end if;
  if old.state<>new.state and not ((old.state='STAGED' and new.state in('DURABLE','FAILED')) or (old.state='DURABLE' and new.state in('VERIFIED','QUARANTINED')) or (old.state='VERIFIED' and new.state in('COMPACTED','COLD','DELETION_PENDING','QUARANTINED')) or (old.state='COMPACTED' and new.state in('COLD','DELETION_PENDING','QUARANTINED')) or (old.state='COLD' and new.state in('VERIFIED','DELETION_PENDING','QUARANTINED')) or (old.state='QUARANTINED' and new.state in('VERIFIED','DELETION_PENDING')) or (old.state='DELETION_PENDING' and new.state in('DELETED','VERIFIED'))) then raise exception 'Invalid LakeObject state transition % -> %',old.state,new.state; end if;
  if new.state_version<>old.state_version+1 then raise exception 'LakeObject version must advance exactly once'; end if;
  return new;
end $$;
create trigger lake_object_guard before update or delete on ouf_udp.lake_object for each row execute function ouf_udp.guard_lake_object_mutation();
