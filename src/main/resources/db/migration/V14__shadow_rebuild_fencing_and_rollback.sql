alter table ouf_udp.lake_shadow_rebuild_plan drop constraint lake_shadow_rebuild_plan_state_check;
alter table ouf_udp.lake_shadow_rebuild_plan add constraint lake_shadow_rebuild_plan_state_check check(state in('DRAFT','BUILDING','READY','CUTOVER','ROLLED_BACK','FAILED'));
alter table ouf_udp.lake_shadow_rebuild_plan add column rolled_back_at timestamptz;
alter table ouf_udp.lake_shadow_rebuild_plan add column rolled_back_by_subject text;

alter table ouf_udp.lake_shadow_rebuild_entry drop constraint lake_shadow_rebuild_entry_state_check;
alter table ouf_udp.lake_shadow_rebuild_entry add constraint lake_shadow_rebuild_entry_state_check check(state in('PENDING','VERIFIED','CUTOVER','ROLLED_BACK'));
alter table ouf_udp.lake_shadow_rebuild_entry add column verified_generation bigint;

create or replace function ouf_udp.guard_lake_shadow_plan() returns trigger language plpgsql as $$
begin
  if tg_op='DELETE' then raise exception 'Shadow rebuild plan cannot be deleted'; end if;
  if old.plan_id<>new.plan_id or old.tenant_id<>new.tenant_id or old.tier<>new.tier or old.expected_object_count<>new.expected_object_count or old.baseline_digest<>new.baseline_digest or old.created_by_subject<>new.created_by_subject or old.created_at<>new.created_at or old.correlation_id<>new.correlation_id then raise exception 'Shadow rebuild plan identity is immutable'; end if;
  if old.state<>new.state and not ((old.state='DRAFT' and new.state in('BUILDING','FAILED')) or (old.state='BUILDING' and new.state in('READY','FAILED')) or (old.state='READY' and new.state in('CUTOVER','FAILED')) or (old.state='CUTOVER' and new.state='ROLLED_BACK')) then raise exception 'Invalid shadow rebuild transition % -> %',old.state,new.state; end if;
  if new.version<>old.version+1 then raise exception 'Shadow rebuild version must advance exactly once'; end if;
  return new;
end $$;
