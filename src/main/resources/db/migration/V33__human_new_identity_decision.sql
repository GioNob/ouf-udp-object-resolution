alter table ouf_udp.human_resolution_decision
  drop constraint human_resolution_decision_action_check;
alter table ouf_udp.human_resolution_decision
  add constraint human_resolution_decision_action_check
  check(action in('APPROVE_MATCH','CREATE_NEW','DISMISS'));
