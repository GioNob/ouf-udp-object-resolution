-- Retain the exact durable source bytes independently from the handoff recovery envelope.
alter table ouf_udp.handoff_intake add column source_raw_lake_object_id uuid references ouf_udp.lake_object(lake_object_id);
