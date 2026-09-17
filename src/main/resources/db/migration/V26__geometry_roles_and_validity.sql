alter table ouf_udp.urban_geometry add column geometry_role text not null default 'PRIMARY';
alter table ouf_udp.urban_geometry add column valid_from timestamptz;
alter table ouf_udp.urban_geometry add column valid_to timestamptz;
alter table ouf_udp.urban_geometry add constraint geometry_validity_nonempty check(valid_from is null or valid_to is null or valid_from < valid_to);
create table ouf_udp.urban_geometry_role_current(
  urban_object_id uuid not null references ouf_udp.urban_object,
  geometry_role text not null,
  geometry_revision_id uuid not null references ouf_udp.urban_geometry,
  primary key(urban_object_id,geometry_role)
);
insert into ouf_udp.urban_geometry_role_current select urban_object_id,'PRIMARY',geometry_revision_id from ouf_udp.urban_geometry_current;
-- Default spatial queries and identity evidence use only the currently valid primary geometry.
create view ouf_udp.urban_geometry_active as
select c.* from ouf_udp.urban_geometry_current c join ouf_udp.urban_geometry g using(geometry_revision_id)
where (g.valid_from is null or g.valid_from<=transaction_timestamp())
  and (g.valid_to is null or g.valid_to>transaction_timestamp());
create table ouf_udp.geometry_observation(
  handoff_id text not null references ouf_udp.handoff_intake,
  geometry_revision_id uuid not null references ouf_udp.urban_geometry,
  observed_at timestamptz,
  primary key(handoff_id,geometry_revision_id)
);
create index geometry_observation_time_idx on ouf_udp.geometry_observation(geometry_revision_id,observed_at desc);
create trigger geometry_observation_append_only before update or delete on ouf_udp.geometry_observation for each row execute function ouf_udp.reject_append_only_mutation();
