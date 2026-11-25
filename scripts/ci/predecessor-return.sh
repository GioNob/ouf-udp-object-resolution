#!/usr/bin/env bash
set -euo pipefail
: "${PREDECESSOR:?}" "${PREDECESSOR_SCHEMA:?}" "${CANDIDATE_SCHEMA:?}"
[[ "$PREDECESSOR" =~ ^[0-9a-f]{40}$ ]]
candidate=$(git rev-parse HEAD)
git merge-base --is-ancestor "$PREDECESSOR" "$candidate"
[[ "$candidate" != "$PREDECESSOR" ]]

export FIXTURE_DIR
FIXTURE_DIR=$(mktemp -d)
evidence=target/predecessor-return
mkdir -p "$evidence"
build(){
  local sha=$1
  git archive "$sha" | docker build --label "org.opencontainers.image.revision=$sha" -t "udp-return:$sha" -
  test "$(docker image inspect "udp-return:$sha" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')" = "$sha"
  docker image inspect "udp-return:$sha" --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}' >> "$evidence/images.txt"
}
build "$PREDECESSOR"
build "$candidate"
minio_image=$(bash scripts/ci/build-pinned-minio-image.sh)

cleanup(){
  docker rm -fv udp-return-app udp-return-pg udp-return-s3 >/dev/null 2>&1 || true
  if [[ -n "${fixture_pid:-}" ]]; then kill "$fixture_pid" 2>/dev/null || true; fi
  rm -rf "$FIXTURE_DIR"
}
trap cleanup EXIT
export POSTGRES_PASSWORD AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_DEFAULT_REGION=us-east-1
POSTGRES_PASSWORD=$(openssl rand -hex 24)
AWS_ACCESS_KEY_ID=$(openssl rand -hex 12)
AWS_SECRET_ACCESS_KEY=$(openssl rand -hex 32)
docker run -d --name udp-return-pg -p 127.0.0.1:15432:5432 -e POSTGRES_PASSWORD \
  postgis/postgis:17-3.5-alpine >/dev/null
docker run -d --name udp-return-s3 -p 127.0.0.1:19000:9000 -v /data -e MINIO_ROOT_USER="$AWS_ACCESS_KEY_ID" \
  -e MINIO_ROOT_PASSWORD="$AWS_SECRET_ACCESS_KEY" "$minio_image" server /data >/dev/null

sql(){
  docker exec -e PGOPTIONS='-c default_transaction_read_only=on' udp-return-pg \
    psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres -Atc "$1"
}
for attempt in {1..60}; do
  docker exec udp-return-pg pg_isready -h 127.0.0.1 -U postgres >/dev/null && break
  sleep 1
done
test "$(sql "select current_setting('server_version_num')::int / 10000")" = 17
test "$(sql "select to_regnamespace('ouf_udp') is null")" = t
curl -fsS --retry 30 --retry-connrefused --retry-delay 1 http://127.0.0.1:19000/minio/health/live >/dev/null
aws --endpoint-url http://127.0.0.1:19000 s3api create-bucket --bucket udp-return-fixture >/dev/null
node scripts/ci/predecessor-fixture.mjs &
fixture_pid=$!
curl -fsS --retry 15 --retry-connrefused --retry-delay 1 http://127.0.0.1:18083/.well-known/openid-configuration >/dev/null

start(){
  local sha=$1 version=$2 maintenance_delay=${3:-3600000}
  docker run -d --name udp-return-app --network host \
    -e PORT=18080 -e SERVER_ADDRESS=127.0.0.1 \
    -e OUF_UDP_DB_URL=jdbc:postgresql://127.0.0.1:15432/postgres \
    -e OUF_UDP_DB_USER=postgres -e OUF_UDP_DB_PASSWORD="$POSTGRES_PASSWORD" \
    -e SPRING_FLYWAY_ENABLED=true -e SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true \
    -e OUF_UDP_S3_BUCKET=udp-return-fixture -e OUF_UDP_S3_ENDPOINT=http://127.0.0.1:19000 \
    -e OUF_UDP_S3_PATH_STYLE=true -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY \
    -e OUF_UDP_LAKE_REQUIRED=true -e OUF_UDP_RAW_RETENTION_DAYS=1 \
    -e OUF_UDP_RAW_RETENTION_CLASS=AUDIT -e OUF_UDP_RAW_ACCESS_LABEL=OPEN -e OUF_UDP_TENANT_ID=tenant-ci \
    -e OUF_UDP_LAKE_MAINTENANCE_INITIAL_DELAY_MS="$maintenance_delay" \
    -e OUF_UDP_INGESTION_WORKLOAD=ci-ingestion -e OUF_UDP_IAM_ENABLED=true -e OUF_UDP_IAM_ISSUER=http://127.0.0.1:18083 -e OUF_UDP_IAM_AUDIENCE=udp-ci \
    -e OUF_AUTHORIZATION_BUNDLE_FILE=/tmp/policy.json -e OUF_AUTHORIZATION_BUNDLE_ID=return-fixture \
    -e OUF_AUTHORIZATION_BUNDLE_VERSION=1 -e OUF_AUTHORIZATION_BUNDLE_SHA256="$(sha256sum "$FIXTURE_DIR/policy.json" | cut -d' ' -f1)" \
    -v "$FIXTURE_DIR/policy.json:/tmp/policy.json:ro" -v "$FIXTURE_DIR/gateway-token:/tmp/gateway-token:ro" \
    -e OUF_UDP_EXECUTION_ENABLED=true -e OUF_UDP_EXECUTION_GATEWAY_URL=http://127.0.0.1:18083 \
    -e OUF_UDP_EXECUTION_TOKEN_FILE=/tmp/gateway-token "udp-return:$sha" \
    '--spring.flyway.ignore-migration-patterns=*:future' "--ouf.udp.execution.initial-delay-ms=${4:-1000}" >/dev/null
  for attempt in {1..120}; do
    if curl -fsS http://127.0.0.1:18080/actuator/health/readiness 2>/dev/null | jq -e '.status=="UP"' >/dev/null; then
      test "$(sql "select version from ouf_udp.flyway_schema_history where version is not null and success order by installed_rank desc limit 1")" = "$version"
      return
    fi
    test "$(docker inspect -f '{{.State.Running}}' udp-return-app)" = true || break
    sleep 1
  done
  echo "Readiness failed for $sha" >&2
  return 1
}
stop(){ docker stop -t 60 udp-return-app >/dev/null && docker rm udp-return-app >/dev/null; }
history(){ sql 'select installed_rank,version,script,checksum,success from ouf_udp.flyway_schema_history order by installed_rank'; }
source_raw(){
  sql "select o.$2 from ouf_udp.handoff_intake h join ouf_udp.lake_object o
    on o.lake_object_id=h.source_raw_lake_object_id where h.handoff_id='$1'"
}
copy_lineage(){
  sql "select h.handoff_id,o.lake_object_id,o.copy_no,o.state,o.locator,o.state_version,o.blocking_lineage_refs
    from ouf_udp.handoff_intake h join ouf_udp.lake_object o on o.lake_object_id=h.source_raw_lake_object_id
    where h.handoff_id in ('return-seed','return-restore') order by o.copy_no"
}
verified_type(){
  sql "select safe_detail->>'canonicalType' from ouf_udp.handoff_event
    where handoff_id='return-change' and event_type='MATERIALIZATION_TYPE_VERIFIED'"
}

start "$PREDECESSOR" "$PREDECESSOR_SCHEMA"
node scripts/ci/predecessor-scenario.mjs seed
cp "$FIXTURE_DIR/reference-methods" "$evidence/references-predecessor.txt"
test "$(sort -u "$evidence/references-predecessor.txt")" = POST
: > "$FIXTURE_DIR/reference-methods"
history > "$evidence/flyway-predecessor.txt"
stop
# Two synthetic preconditions. UDP itself detects the damaged bytes, allocates
# the replacement and resolves the reused key through its authenticated inputs.
seed_key=$(source_raw return-seed locator)
test "$(source_raw return-seed copy_no)" = 0
test -n "$seed_key"
printf '%s' 'damaged CI fixture' > "$FIXTURE_DIR/damaged"
aws --endpoint-url http://127.0.0.1:19000 s3api put-object --bucket udp-return-fixture \
  --key "$seed_key" --body "$FIXTURE_DIR/damaged" >/dev/null
docker exec udp-return-pg psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres -c \
  "insert into ouf_udp.urban_object(urban_object_id,tenant_id,canonical_type,canonical_key,match_key,status)
   values(gen_random_uuid(),'tenant-ci','ci:Worksite','ci-reused','ci-reused','RETIRED')" >/dev/null
start "$candidate" "$CANDIDATE_SCHEMA" 1000
for attempt in {1..30}; do
  [[ "$(source_raw return-seed state)" == QUARANTINED ]] && break
  sleep 1
done
test "$(source_raw return-seed state)" = QUARANTINED
copy_lineage > "$evidence/copy-quarantined.txt"
touch "$FIXTURE_DIR/unavailable-once"
node scripts/ci/predecessor-scenario.mjs candidate
cp "$FIXTURE_DIR/reference-methods" "$evidence/references-candidate.txt"
test "$(sort -u "$evidence/references-candidate.txt")" = POST
: > "$FIXTURE_DIR/reference-methods"
test "$(verified_type)" = 'ci:Worksite'
verified_type > "$evidence/materialization-type-candidate.txt"
object_id=$(sql "select urban_object_id from ouf_udp.urban_object where tenant_id='tenant-ci' and canonical_type='ci:Worksite'
  and canonical_key='ci-row' and status='ACTIVE'")
test "$(sql "select count(distinct (canonical_hash,authority_hash)) from ouf_udp.object_revision where urban_object_id='$object_id'")" = 2
test "$(sql "select count(*) from ouf_udp.object_revision where urban_object_id='$object_id'")" = 3
test "$(source_raw return-restore copy_no)" = 1
test "$(source_raw return-restore state)" = VERIFIED
test "$(source_raw return-restore locator)" != "$seed_key"
test "$(source_raw return-seed blocking_lineage_refs)" = 1
test "$(source_raw return-restore blocking_lineage_refs)" = 1
test "$(sql "select count(*) from ouf_udp.urban_object where tenant_id='tenant-ci' and canonical_type='ci:Worksite' and canonical_key='ci-reused'")" = 2
copy_lineage > "$evidence/copies-candidate.txt"
head -n 1 "$evidence/copies-candidate.txt" | diff -u "$evidence/copy-quarantined.txt" -
history > "$evidence/flyway-candidate.txt"
stop
start "$candidate" "$CANDIDATE_SCHEMA" 3600000 3600000
node scripts/ci/predecessor-scenario.mjs pending
stop
start "$PREDECESSOR" "$CANDIDATE_SCHEMA"
touch "$FIXTURE_DIR/unavailable-once"
node scripts/ci/predecessor-scenario.mjs return "$object_id"
cp "$FIXTURE_DIR/reference-methods" "$evidence/references-return.txt"
test "$(sort -u "$evidence/references-return.txt")" = POST
: > "$FIXTURE_DIR/reference-methods"
verified_type > "$evidence/materialization-type-return.txt"
diff -u "$evidence/materialization-type-candidate.txt" "$evidence/materialization-type-return.txt"
retry_events=$(sql "select handoff_id,event_type from ouf_udp.handoff_event
  where handoff_id in ('return-change','after-return') and event_type like 'PUBLICATION_DEPENDENCY_%'
  order by handoff_id,created_at,event_type")
test "$retry_events" = "$(printf '%s\n' \
  'after-return|PUBLICATION_DEPENDENCY_RETRY_WAIT' 'after-return|PUBLICATION_DEPENDENCY_RECOVERED' \
  'return-change|PUBLICATION_DEPENDENCY_RETRY_WAIT' 'return-change|PUBLICATION_DEPENDENCY_RECOVERED')"
echo "$retry_events" > "$evidence/retry-events.txt"
copy_lineage > "$evidence/copies-return.txt"
diff -u "$evidence/copies-candidate.txt" "$evidence/copies-return.txt"
aws --endpoint-url http://127.0.0.1:19000 s3api get-object --bucket udp-return-fixture \
  --key "$seed_key" "$FIXTURE_DIR/after-return" >/dev/null
cmp "$FIXTURE_DIR/damaged" "$FIXTURE_DIR/after-return"
test "$(source_raw after-return copy_no)" = 0
test "$(sql 'select count(*) from ouf_udp.lake_object where copy_no>0')" = 1
test "$(sql "select count(*) from ouf_udp.source_binding b join ouf_udp.urban_object u using(urban_object_id)
  where u.status='ACTIVE' and u.canonical_key='ci-reused' and b.source_object_id in ('ci-reused','ci-new-binding')")" = 2
test "$(sql 'select count(*) from ouf_udp.materialization_observation')" = 7
test "$(sql "select count(*) from ouf_udp.materialization_job where state='SUCCEEDED'")" = 7
history > "$evidence/flyway-return.txt"
diff -u "$evidence/flyway-candidate.txt" "$evidence/flyway-return.txt"
test "$(sql "select attempts from ouf_udp.materialization_job where handoff_id='pending-api'")" = 0
test "$(sql "select count(*) from ouf_udp.api_handoff_outcome where handoff_id='pending-api'")" = 0
# The V39 intake admits an API handoff without api_tenant_id; the V40 trigger still fences it.
node scripts/ci/predecessor-scenario.mjs admit
test "$(sql "select state||':'||attempts||':'||api_semantics||':'||(api_tenant_id is null) from ouf_udp.materialization_job
  where handoff_id='return-api'")" = 'PAUSED:0:true:true'
stop
start "$candidate" "$CANDIDATE_SCHEMA"
node scripts/ci/predecessor-scenario.mjs resume
sql "select handoff_id,outcome from ouf_udp.api_handoff_outcome order by position" > "$evidence/api-outcomes.txt"
echo 'PASS: V39 extension intake, fenced pending and V39-admitted API jobs, V40 resume and adoption, A/B/A history, B8 copies and lineage, scoped canonical key, bridge read/write and idempotent retries' > "$evidence/result.txt"
