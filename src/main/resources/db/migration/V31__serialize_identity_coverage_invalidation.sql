-- Serialize all writes that invalidate a certified identity scope with
-- candidate retrieval and its same-transaction incremental refresh.
create or replace function ouf_udp.invalidate_identity_lookup_coverage() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    perform pg_advisory_xact_lock(hashtextextended('identity:' || old.tenant_id || ':' || old.canonical_type,0));
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=old.tenant_id and canonical_class=old.canonical_type and complete;
  end if;
  if tg_op <> 'DELETE' then
    if tg_op = 'INSERT' then
      perform pg_advisory_xact_lock(hashtextextended('identity:' || new.tenant_id || ':' || new.canonical_type,0));
    elsif (old.tenant_id,old.canonical_type) is distinct from
        (new.tenant_id,new.canonical_type) then
      perform pg_advisory_xact_lock(hashtextextended('identity:' || new.tenant_id || ':' || new.canonical_type,0));
    end if;
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=new.tenant_id and canonical_class=new.canonical_type and complete;
  end if;
  return null;
end$$;

create or replace function ouf_udp.invalidate_identity_lookup_token_coverage() returns trigger language plpgsql as $$
begin
  if tg_op <> 'INSERT' then
    perform pg_advisory_xact_lock(hashtextextended('identity:' || old.tenant_id || ':' || old.canonical_class,0));
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=old.tenant_id and canonical_class=old.canonical_class
        and policy_ref=old.policy_ref and policy_version=old.policy_version and complete;
  end if;
  if tg_op <> 'DELETE' then
    if tg_op = 'INSERT' then
      perform pg_advisory_xact_lock(hashtextextended('identity:' || new.tenant_id || ':' || new.canonical_class,0));
    elsif (old.tenant_id,old.canonical_class) is distinct from
        (new.tenant_id,new.canonical_class) then
      perform pg_advisory_xact_lock(hashtextextended('identity:' || new.tenant_id || ':' || new.canonical_class,0));
    end if;
    update ouf_udp.identity_lookup_coverage set complete=false,updated_at=transaction_timestamp()
      where tenant_id=new.tenant_id and canonical_class=new.canonical_class
        and policy_ref=new.policy_ref and policy_version=new.policy_version and complete;
  end if;
  return null;
end$$;
