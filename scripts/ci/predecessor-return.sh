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
  local sha=$1 version=$2
  docker run -d --name udp-return-app --network host \
    -e PORT=18080 -e SERVER_ADDRESS=127.0.0.1 \
    -e OUF_UDP_DB_URL=jdbc:postgresql://127.0.0.1:15432/postgres \
    -e OUF_UDP_DB_USER=postgres -e OUF_UDP_DB_PASSWORD="$POSTGRES_PASSWORD" \
    -e SPRING_FLYWAY_ENABLED=true -e SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true \
    -e OUF_UDP_S3_BUCKET=udp-return-fixture -e OUF_UDP_S3_ENDPOINT=http://127.0.0.1:19000 \
    -e OUF_UDP_S3_PATH_STYLE=true -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY \
    -e OUF_UDP_LAKE_REQUIRED=true -e OUF_UDP_RAW_RETENTION_DAYS=1 \
    -e OUF_UDP_RAW_RETENTION_CLASS=AUDIT -e OUF_UDP_RAW_ACCESS_LABEL=OPEN -e OUF_UDP_TENANT_ID=tenant-ci \
    -e OUF_UDP_IAM_ENABLED=true -e OUF_UDP_IAM_ISSUER=http://127.0.0.1:18083 -e OUF_UDP_IAM_AUDIENCE=udp-ci \
    -e OUF_AUTHORIZATION_BUNDLE_FILE=/tmp/policy.json -e OUF_AUTHORIZATION_BUNDLE_ID=return-fixture \
    -e OUF_AUTHORIZATION_BUNDLE_VERSION=1 -e OUF_AUTHORIZATION_BUNDLE_SHA256="$(sha256sum "$FIXTURE_DIR/policy.json" | cut -d' ' -f1)" \
    -v "$FIXTURE_DIR/policy.json:/tmp/policy.json:ro" -v "$FIXTURE_DIR/gateway-token:/tmp/gateway-token:ro" \
    -e OUF_UDP_EXECUTION_ENABLED=true -e OUF_UDP_EXECUTION_GATEWAY_URL=http://127.0.0.1:18083 \
    -e OUF_UDP_EXECUTION_TOKEN_FILE=/tmp/gateway-token "udp-return:$sha" \
    '--spring.flyway.ignore-migration-patterns=*:future' >/dev/null
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

start "$PREDECESSOR" "$PREDECESSOR_SCHEMA"
node scripts/ci/predecessor-scenario.mjs seed
history > "$evidence/flyway-predecessor.txt"
stop
start "$candidate" "$CANDIDATE_SCHEMA"
touch "$FIXTURE_DIR/unavailable-once"
node scripts/ci/predecessor-scenario.mjs candidate
test "$(sql 'select count(distinct (canonical_hash,authority_hash)) from ouf_udp.object_revision')" = 2
test "$(sql 'select count(*) from ouf_udp.object_revision')" = 3
history > "$evidence/flyway-candidate.txt"
stop
start "$PREDECESSOR" "$CANDIDATE_SCHEMA"
touch "$FIXTURE_DIR/unavailable-once"
node scripts/ci/predecessor-scenario.mjs return "$(sql 'select urban_object_id from ouf_udp.urban_object')"
retry_events=$(sql "select handoff_id,event_type from ouf_udp.handoff_event
  where handoff_id in ('return-change','after-return') and event_type like 'PUBLICATION_DEPENDENCY_%'
  order by handoff_id,created_at,event_type")
test "$retry_events" = "$(printf '%s\n' \
  'after-return|PUBLICATION_DEPENDENCY_RETRY_WAIT' 'after-return|PUBLICATION_DEPENDENCY_RECOVERED' \
  'return-change|PUBLICATION_DEPENDENCY_RETRY_WAIT' 'return-change|PUBLICATION_DEPENDENCY_RECOVERED')"
echo "$retry_events" > "$evidence/retry-events.txt"
test "$(sql 'select count(*) from ouf_udp.lake_object where copy_no<>0')" = 0
test "$(sql 'select count(*) from ouf_udp.materialization_observation')" = 4
test "$(sql "select count(*) from ouf_udp.materialization_job where state='SUCCEEDED'")" = 4
history > "$evidence/flyway-return.txt"
diff -u "$evidence/flyway-candidate.txt" "$evidence/flyway-return.txt"
echo 'PASS: authenticated seed, migration, A/B/A history, predecessor read/write and idempotent retries' > "$evidence/result.txt"
