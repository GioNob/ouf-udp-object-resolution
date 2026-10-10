-- UDP PET §§22–24. Score details contain metrics, not copies of source property values.
alter table ouf_udp.resolution_decision add column score_evidence jsonb;
create index urban_current_identity_blocking_idx on ouf_udp.urban_object_current_state using gin(canonical_payload jsonb_path_ops);
