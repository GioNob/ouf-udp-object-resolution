-- Every writer of the active object set or its current revision participates in
-- the same transaction-scoped advisory lock as governed candidate retrieval.
create function ouf_udp.lock_identity_scope() returns trigger language plpgsql as $$
declare
  old_key text;
  new_key text;
begin
  if tg_op = 'DELETE' then
    old_key := 'identity:' || old.tenant_id || ':' || old.canonical_type;
    perform pg_advisory_xact_lock(hashtextextended(old_key,0));
    return old;
  end if;
  new_key := 'identity:' || new.tenant_id || ':' || new.canonical_type;
  if tg_op = 'UPDATE' then
    old_key := 'identity:' || old.tenant_id || ':' || old.canonical_type;
    if old_key <> new_key then
      if old_key < new_key then
        perform pg_advisory_xact_lock(hashtextextended(old_key,0));
        perform pg_advisory_xact_lock(hashtextextended(new_key,0));
      else
        perform pg_advisory_xact_lock(hashtextextended(new_key,0));
        perform pg_advisory_xact_lock(hashtextextended(old_key,0));
      end if;
    else
      perform pg_advisory_xact_lock(hashtextextended(new_key,0));
    end if;
  else
    perform pg_advisory_xact_lock(hashtextextended(new_key,0));
  end if;
  return new;
end $$;
create trigger urban_object_identity_scope_lock before insert or update or delete on ouf_udp.urban_object
  for each row execute function ouf_udp.lock_identity_scope();
