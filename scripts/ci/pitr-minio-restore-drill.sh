#!/usr/bin/env bash
set -euo pipefail

dr_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/ouf-udp-dr.XXXXXX")"
evidence_dir="${GITHUB_WORKSPACE:-$(pwd)}/target/dr-evidence"
mkdir -p "$evidence_dir" "$dr_root/pg-primary" "$dr_root/pg-base" "$dr_root/pg-archive" "$dr_root/pg-restored" "$dr_root/minio-primary" "$dr_root/minio-backup" "$dr_root/minio-restored"
chmod 0777 "$dr_root/pg-primary" "$dr_root/pg-base" "$dr_root/pg-archive" "$dr_root/pg-restored" "$dr_root/minio-primary" "$dr_root/minio-backup" "$dr_root/minio-restored"

db_password="dr-ci-password"
minio_user="dr-minio-access"
minio_password="dr-minio-secret"
primary_db="udp-dr-pg-primary"
restored_db="udp-dr-pg-restored"
primary_minio="udp-dr-minio-primary"
restored_minio="udp-dr-minio-restored"
app_pid=""
last_step="initialization"

step(){ last_step="$1"; echo "DR_STEP=$last_step"; }
failure(){ local code="$?"; printf 'result=FAIL\nstep=%s\nline=%s\nexit_code=%s\n' "$last_step" "${BASH_LINENO[0]}" "$code" >"$evidence_dir/DR_FAILURE.txt"; exit "$code"; }

cleanup(){
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; fi
  docker rm -f "$primary_db" "$restored_db" "$primary_minio" "$restored_minio" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap failure ERR

wait_postgres(){
  local container="$1"
  local stable=0
  for attempt in {1..60}; do
    if docker exec -e PGPASSWORD="$db_password" "$container" \
      psql -h 127.0.0.1 -U ouf_udp -d ouf_udp -Atc "select 1" 2>/dev/null | grep -qx 1; then
      stable=$((stable + 1))
      [[ "$stable" -ge 3 ]] && return 0
    else
      stable=0
    fi
    sleep 1
  done
  docker logs "$container" >&2
  return 1
}

wait_http(){
  local url="$1"
  for attempt in {1..60}; do curl --fail --silent "$url" >/dev/null && return 0; sleep 1; done
  return 1
}

docker run -d --name "$primary_db" -p 127.0.0.1::5432 \
  -e POSTGRES_DB=ouf_udp -e POSTGRES_USER=ouf_udp -e POSTGRES_PASSWORD="$db_password" \
  -v "$dr_root/pg-primary:/var/lib/postgresql/data" -v "$dr_root/pg-base:/backup/base" -v "$dr_root/pg-archive:/archive" \
  postgis/postgis:17-3.5-alpine postgres -c wal_level=replica -c archive_mode=on -c archive_timeout=5s -c "archive_command=test ! -f /archive/%f && cp %p /archive/%f" >/dev/null
primary_db_port="$(docker port "$primary_db" 5432/tcp | awk -F: '{print $NF}')"
wait_postgres "$primary_db"
step postgres_primary_ready

OUF_UDP_DB_URL="jdbc:postgresql://127.0.0.1:$primary_db_port/ouf_udp?sslmode=disable" OUF_UDP_DB_USER=ouf_udp OUF_UDP_DB_PASSWORD="$db_password" OUF_UDP_LAKE_REQUIRED=false \
  java -jar target/udp-object-resolution-*.jar --server.port=18081 >"$evidence_dir/primary-migration.log" 2>&1 &
app_pid="$!"
wait_http http://127.0.0.1:18081/actuator/health
kill "$app_pid"
wait "$app_pid" 2>/dev/null || true
app_pid=""
step flyway_migrated

printf '{"dr":"baseline"}\n' >"$dr_root/object.json"
content_hash="sha256:$(sha256sum "$dr_root/object.json" | awk '{print $1}')"
content_size="$(wc -c <"$dr_root/object.json" | tr -d ' ')"

docker run -d --name "$primary_minio" -p 59000:9000 -e MINIO_ROOT_USER="$minio_user" -e MINIO_ROOT_PASSWORD="$minio_password" -v "$dr_root/minio-primary:/data" quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z server /data >/dev/null
wait_http http://127.0.0.1:59000/minio/health/live
AWS_ACCESS_KEY_ID="$minio_user" AWS_SECRET_ACCESS_KEY="$minio_password" AWS_DEFAULT_REGION=us-east-1 aws --endpoint-url http://127.0.0.1:59000 s3api create-bucket --bucket ouf-udp-dr >/dev/null
AWS_ACCESS_KEY_ID="$minio_user" AWS_SECRET_ACCESS_KEY="$minio_password" AWS_DEFAULT_REGION=us-east-1 aws --endpoint-url http://127.0.0.1:59000 s3api put-object --bucket ouf-udp-dr --key dr/object.json --body "$dr_root/object.json" --metadata "ouf-content-hash=$content_hash" >/dev/null
step minio_fixture_written

docker exec -e PGPASSWORD="$db_password" "$primary_db" psql -U ouf_udp -d ouf_udp -v ON_ERROR_STOP=1 -c "insert into ouf_udp.lake_object(lake_object_id,tenant_id,source_id,type_code,tier,content_hash,media_type,logical_size_bytes,locator,state,retention_class,access_label,retention_until,verified_at) values('10000000-0000-0000-0000-000000000001','dr-tenant','dr-source','DR_TYPE','RAW','$content_hash','application/json',$content_size,'dr/object.json','VERIFIED','ARCHIVAL','OPEN',transaction_timestamp()+interval '365 days',transaction_timestamp());" >/dev/null

docker exec -e PGPASSWORD="$db_password" "$primary_db" pg_basebackup -h 127.0.0.1 -U ouf_udp -D /backup/base -Fp -Xs -P --checkpoint=fast >/dev/null
step postgres_base_backup_complete

docker exec -e PGPASSWORD="$db_password" "$primary_db" psql -U ouf_udp -d ouf_udp -v ON_ERROR_STOP=1 -c "insert into ouf_udp.lake_object_event(event_id,lake_object_id,event_type,actor_subject,correlation_id,safe_detail) values('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','DR_RETAINED','SYSTEM','dr-retained','{}');" >/dev/null
recovery_target="$(docker exec -e PGPASSWORD="$db_password" "$primary_db" psql -U ouf_udp -d ouf_udp -Atc "select to_char(clock_timestamp()+interval '1 second','YYYY-MM-DD HH24:MI:SS.USOF')")"
sleep 2
docker exec -e PGPASSWORD="$db_password" "$primary_db" psql -U ouf_udp -d ouf_udp -v ON_ERROR_STOP=1 -c "insert into ouf_udp.lake_object_event(event_id,lake_object_id,event_type,actor_subject,correlation_id,safe_detail) values('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','DR_EXCLUDED','SYSTEM','dr-excluded','{}'); select pg_switch_wal();" >/dev/null
sleep 6
step recovery_wal_archived

docker stop "$primary_minio" >/dev/null
sudo tar -C "$dr_root/minio-primary" -cf "$dr_root/minio-backup/minio-data.tar" .
docker stop "$primary_db" >/dev/null
step primary_systems_stopped

sudo cp -a "$dr_root/pg-base/." "$dr_root/pg-restored/"
sudo cp -a "$dr_root/minio-backup/minio-data.tar" "$dr_root/minio-restored/"
sudo tar -C "$dr_root/minio-restored" -xf "$dr_root/minio-restored/minio-data.tar"
sudo find "$dr_root/minio-restored" -maxdepth 1 -name minio-data.tar -delete
sudo touch "$dr_root/pg-restored/recovery.signal"
{
  echo "restore_command = 'cp /archive/%f %p'"
  echo "recovery_target_time = '$recovery_target'"
  echo "recovery_target_action = 'promote'"
} | sudo tee -a "$dr_root/pg-restored/postgresql.auto.conf" >/dev/null
step restore_directories_prepared

restore_started="$(date +%s)"
docker run -d --name "$restored_db" -p 127.0.0.1::5432 -v "$dr_root/pg-restored:/var/lib/postgresql/data" -v "$dr_root/pg-archive:/archive:ro" postgis/postgis:17-3.5-alpine >/dev/null
restored_db_port="$(docker port "$restored_db" 5432/tcp | awk -F: '{print $NF}')"
wait_postgres "$restored_db"
docker run -d --name "$restored_minio" -p 59001:9000 -e MINIO_ROOT_USER="$minio_user" -e MINIO_ROOT_PASSWORD="$minio_password" -v "$dr_root/minio-restored:/data" quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z server /data >/dev/null
wait_http http://127.0.0.1:59001/minio/health/live
restore_completed="$(date +%s)"
step restored_systems_ready

retained="$(docker exec -e PGPASSWORD="$db_password" "$restored_db" psql -U ouf_udp -d ouf_udp -Atc "select count(*) from ouf_udp.lake_object_event where event_type='DR_RETAINED'")"
excluded="$(docker exec -e PGPASSWORD="$db_password" "$restored_db" psql -U ouf_udp -d ouf_udp -Atc "select count(*) from ouf_udp.lake_object_event where event_type='DR_EXCLUDED'")"
migration="$(docker exec -e PGPASSWORD="$db_password" "$restored_db" psql -U ouf_udp -d ouf_udp -Atc "select max(version::integer) from ouf_udp.flyway_schema_history where success")"
expected_migration="$(find src/main/resources/db/migration -maxdepth 1 -type f -name 'V*__*.sql' -printf '%f\n' | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -1)"
restored_hash="$(AWS_ACCESS_KEY_ID="$minio_user" AWS_SECRET_ACCESS_KEY="$minio_password" AWS_DEFAULT_REGION=us-east-1 aws --endpoint-url http://127.0.0.1:59001 s3api head-object --bucket ouf-udp-dr --key dr/object.json --query 'Metadata."ouf-content-hash"' --output text)"
printf 'retained=%s\nexcluded=%s\nflyway=%s\nexpected_flyway=%s\nrestored_hash=%s\nexpected_hash=%s\n' "$retained" "$excluded" "$migration" "$expected_migration" "$restored_hash" "$content_hash" >"$evidence_dir/DR_VALIDATION.txt"
[[ "$retained" == "1" && "$excluded" == "0" && "$migration" == "$expected_migration" && "$restored_hash" == "$content_hash" ]]
step recovery_target_verified

OUF_UDP_DB_URL="jdbc:postgresql://127.0.0.1:$restored_db_port/ouf_udp?sslmode=disable" OUF_UDP_DB_USER=ouf_udp OUF_UDP_DB_PASSWORD="$db_password" OUF_UDP_LAKE_REQUIRED=false OUF_UDP_S3_BUCKET=ouf-udp-dr OUF_UDP_S3_ENDPOINT=http://127.0.0.1:59001 OUF_UDP_S3_REGION=us-east-1 OUF_UDP_S3_PATH_STYLE=true OUF_UDP_LAKE_MAINTENANCE_INITIAL_DELAY_MS=1000 OUF_UDP_LAKE_MAINTENANCE_POLL_MS=1000 AWS_ACCESS_KEY_ID="$minio_user" AWS_SECRET_ACCESS_KEY="$minio_password" AWS_REGION=us-east-1 \
  java -jar target/udp-object-resolution-*.jar --server.port=18082 >"$evidence_dir/restored-application.log" 2>&1 &
app_pid="$!"
wait_http http://127.0.0.1:18082/actuator/health
scan_ok="0"
for attempt in {1..30}; do
  scan_ok="$(docker exec -e PGPASSWORD="$db_password" "$restored_db" psql -U ouf_udp -d ouf_udp -Atc "select count(*) from ouf_udp.lake_maintenance_attempt where state='SUCCEEDED' and missing_count=0 and checksum_mismatch_count=0 and orphan_count=0")"
  [[ "$scan_ok" -ge 1 ]] && break
  sleep 1
done
[[ "$scan_ok" -ge 1 ]]
step reconciliation_verified

kill "$app_pid"
wait "$app_pid" 2>/dev/null || true
app_pid=""
step restored_application_stopped

cat >"$evidence_dir/DR_RESULT.txt" <<EOF
result=PASS
postgres_version=17
flyway_version=$migration
recovery_target=$recovery_target
retained_before_target=$retained
excluded_after_target=$excluded
restored_object_hash=$restored_hash
expected_object_hash=$content_hash
post_restore_reconciliation=SUCCEEDED
reconciliation_findings=0
restore_elapsed_seconds=$((restore_completed-restore_started))
EOF
(cd "$evidence_dir" && sha256sum DR_RESULT.txt primary-migration.log restored-application.log >SHA256SUMS)
