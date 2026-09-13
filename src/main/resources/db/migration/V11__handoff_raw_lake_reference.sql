alter table ouf_udp.handoff_intake add column raw_lake_object_id uuid references ouf_udp.lake_object on delete restrict;
create index handoff_raw_lake_object_idx on ouf_udp.handoff_intake(raw_lake_object_id) where raw_lake_object_id is not null;

