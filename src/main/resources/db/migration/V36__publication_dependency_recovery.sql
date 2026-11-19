-- Read-only publication dependency episodes; no replay or release of historical quarantines.
alter table ouf_udp.materialization_job
 add column dependency_episode_id uuid,
 add column dependency_started_at timestamptz,
 add column dependency_attempts integer not null default 0 check (dependency_attempts >= 0),
 add column dependency_cumulative_attempts bigint not null default 0 check (dependency_cumulative_attempts >= 0);
alter table ouf_udp.materialization_job add constraint dependency_episode_consistent check
 ((dependency_episode_id is null and dependency_started_at is null and dependency_attempts=0)
  or (dependency_episode_id is not null and dependency_started_at is not null and dependency_attempts>0));
