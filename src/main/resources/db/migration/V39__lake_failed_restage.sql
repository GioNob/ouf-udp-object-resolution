-- Permit an identical-content retry at the same locator for NORMALIZED and CURATED.
create or replace function ouf_udp.guard_lake_object_mutation() returns trigger language plpgsql as $$
begin
  if tg_op='DELETE' then raise exception 'LakeObject metadata cannot be deleted'; end if;
  if old.lake_object_id<>new.lake_object_id or old.tenant_id<>new.tenant_id or old.source_id<>new.source_id
      or old.type_code<>new.type_code or old.tier<>new.tier or old.content_hash<>new.content_hash
      or old.media_type<>new.media_type or old.logical_size_bytes<>new.logical_size_bytes
      or old.retention_class<>new.retention_class or old.access_label<>new.access_label
      or old.created_at<>new.created_at then
    raise exception 'LakeObject identity is immutable';
  end if;
  if old.state<>new.state and not (
      (old.tier in ('NORMALIZED','CURATED') and old.state='FAILED' and new.state='STAGED' and old.locator=new.locator)
      or (old.state='STAGED' and new.state in('DURABLE','FAILED'))
      or (old.state='DURABLE' and new.state in('VERIFIED','QUARANTINED'))
      or (old.state='VERIFIED' and new.state in('COMPACTED','COLD','DELETION_PENDING','QUARANTINED'))
      or (old.state='COMPACTED' and new.state in('COLD','DELETION_PENDING','QUARANTINED'))
      or (old.state='COLD' and new.state in('VERIFIED','DELETION_PENDING','QUARANTINED'))
      or (old.state='QUARANTINED' and new.state in('VERIFIED','DELETION_PENDING'))
      or (old.state='DELETION_PENDING' and new.state in('DELETED','VERIFIED'))) then
    raise exception 'Invalid LakeObject state transition % -> %',old.state,new.state;
  end if;
  if new.state_version<>old.state_version+1 then raise exception 'LakeObject version must advance exactly once'; end if;
  return new;
end $$;
