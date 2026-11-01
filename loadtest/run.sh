#!/usr/bin/env bash
# Starts PostgreSQL with 100,000 rows and the built JAR, then lets k6 run 50 parallel refreshes with and without the
# result cache while the heap is sampled. Usage: ./mvnw -q package -DskipTests && loadtest/run.sh [heap] [clients]
set -euo pipefail

HEAP=${1:-1g}
CLIENTS=${2:-50}
PORT=${LOADTEST_PORT:-18198}
DB_PORT=${LOADTEST_DB_PORT:-15499}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
BASE=http://localhost:$PORT
ADMIN=admin:loadtest-admin-pw
DB=jdbcodata-loadtest-db
APP_PID=
SAMPLER_PID=

cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" 2>/dev/null || true
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  docker rm -f "$DB" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

api() { curl -sf -u "$ADMIN" -H 'Content-Type: application/json' "$@"; }

docker run -d --rm --name "$DB" -p "127.0.0.1:$DB_PORT:5432" -e POSTGRES_PASSWORD=load -e POSTGRES_USER=load \
  -e POSTGRES_DB=load postgres:17-alpine >/dev/null
until docker exec "$DB" pg_isready -q -U load -d load; do :; done
until docker exec "$DB" psql -q -U load -d load -c 'select 1' >/dev/null 2>&1; do :; done
docker exec "$DB" psql -q -U load -d load -c "CREATE TABLE sales AS
  SELECT g AS id, 'Customer ' || (g % 500) AS customer, 'Region ' || (g % 7) AS region,
         (g % 9000) + 0.25 AS amount, TIMESTAMP '2025-01-01' + g * INTERVAL '1 minute' AS sold_at
  FROM generate_series(1, 100000) g"

start_app() {
  rm -rf "$WORK/data"
  JDBCODATA_ADMIN_PASSWORD=${ADMIN#admin:} java "-Xmx$HEAP" -jar "$ROOT/target/jdbcodata.jar" --server.port="$PORT" \
    --server.address=127.0.0.1 --jdbcodata.data-dir="$WORK/data" --jdbcodata.security.allow-plain-http=true \
    --jdbcodata.security.rate-limit-per-minute=0 \
    "--spring.datasource.url=jdbc:h2:file:$WORK/data/meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" >"$WORK/app.log" 2>&1 &
  APP_PID=$!
  until curl -sf -o /dev/null "$BASE/actuator/health"; do kill -0 "$APP_PID"; done
}

stop_app() {
  kill "$SAMPLER_PID" 2>/dev/null || true
  kill "$APP_PID" 2>/dev/null || true
  while kill -0 "$APP_PID" 2>/dev/null; do :; done
  APP_PID= SAMPLER_PID=
}

create() { # name cacheEnabled
  local ds id
  ds=$(api "$BASE/api/v1/datasources" -d "{\"name\":\"load\",\"jdbcUrl\":\"jdbc:postgresql://localhost:$DB_PORT/load\",\"maxRows\":100000}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  id=$(api "$BASE/api/v1/queries" -d "{\"name\":\"$1\",\"datasourceId\":\"$ds\",\"cacheEnabled\":$2,\"cacheTtlSec\":3600,\"sql\":\"SELECT id, customer, region, amount, sold_at FROM sales\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  api "$BASE/api/v1/queries/$id/snapshot" -d '{"username":"load","password":"load"}' >/dev/null
}

sample() {
  while kill -0 "$APP_PID" 2>/dev/null; do
    curl -s -m 5 -u "$ADMIN" "$BASE/actuator/prometheus" \
      | awk '/^jvm_memory_used_bytes.*area="heap"/ {s+=$NF} END {if (s) printf "%.0f\n", s/1048576}' >>"$WORK/heap-mb.txt"
    sleep 1
  done
}

RESULTS=$ROOT/loadtest/results
mkdir -p "$RESULTS"
# one fresh service per scenario: an OutOfMemoryError in the first must not decide the second
for scenario in "Uncached false" "Cached true"; do
  read -r feed cache <<<"$scenario"
  echo "Running $feed ..."
  start_app
  create "$feed" "$cache"
  : >"$WORK/heap-mb.txt"
  sample &
  SAMPLER_PID=$!
  docker run --rm --network host -u "$(id -u)" -v "$ROOT/loadtest:/scripts:ro" -v "$RESULTS:/results" \
    -e BASE="$BASE" -e FEED="$feed" -e LOGIN=load:load -e CLIENTS="$CLIENTS" grafana/k6:0.57.0 \
    run --quiet /scripts/refresh.js >"$WORK/k6-$feed.log" 2>&1 || true
  [ -f "$RESULTS/$feed.json" ] || tail -5 "$WORK/k6-$feed.log"
  python3 "$ROOT/loadtest/report.py" "$RESULTS/$feed.json" "$WORK/heap-mb.txt" "$feed" "$HEAP" "$CLIENTS"
  echo "  OutOfMemoryError in the log: $(grep -c OutOfMemoryError "$WORK/app.log" || true)"
  stop_app
done
