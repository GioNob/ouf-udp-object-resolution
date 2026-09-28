alter table ouf_udp.human_resolution_decision
  drop constraint human_resolution_decision_issue_id_key;
create index human_resolution_decision_issue_latest_idx
  on ouf_udp.human_resolution_decision(issue_id,decided_at desc,decision_id desc);
