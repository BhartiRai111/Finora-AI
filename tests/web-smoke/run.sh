#!/usr/bin/env bash
# MANUAL browser smoke test of the Next.js app against the real backend (gateway, identity-access, finance, PostgreSQL) in a real Chromium.
# It is NOT part of `./mvnw verify`, `pnpm test` or any CI job: nothing runs it automatically.
#
# Prerequisites
#   * built service jars:        ./mvnw -B package -DskipTests
#   * local dev PostgreSQL:      tools/dev-postgres.sh up   (and its environment sourced: FINORA_DB_HOST/FINORA_DB_PORT/FINORA_DEV_DB_PASSWORD)
#     The script connects as the database superuser (default `postgres`, override with SMOKE_PG_SUPERUSER) to create and drop its OWN databases.
#   * web dependencies:          (cd web && pnpm install --frozen-lockfile)
#   * Playwright for node:       resolvable through NODE_PATH (a global install is enough)
#
# What it does
#   1. creates throw-away databases finora_smoke_<id>_identity / _finance (owned by the services' migrator roles), so your dev data is never touched;
#   2. starts identity-access, finance, the gateway and `next dev` on local ports 18080-18083 and 13000 with generated throw-away keys;
#   3. runs personal.js and business.js (each prints its numbered checks; on failure: a screenshot, the page text and recent browser errors);
#   4. stops every process it started, drops its databases, and verifies that nothing is left (ports free, databases gone).
#
# The gateway's per-person burst limit is raised for this run only (scripted navigation is faster than a person; the limit itself is covered by gateway tests).
# It refuses to run against a non-local database or web address. Test accounts use example.test addresses; passwords, cookies and CSRF tokens are never printed.
# Usage: tests/web-smoke/run.sh [work-dir]      (work-dir keeps logs and screenshots; default: a new temp directory)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${1:-$(mktemp -d)}"
ID="$(date +%s)$RANDOM"
HOST="${FINORA_DB_HOST:-localhost}"
PORT="${FINORA_DB_PORT:-5433}"
SUPER="${SMOKE_PG_SUPERUSER:-postgres}"
case "$HOST" in localhost|127.0.0.1) ;; *) echo "Refusing: FINORA_DB_HOST=$HOST is not local. This script creates and drops databases." >&2; exit 2;; esac
for p in 18080 18082 18083 13000; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then echo "Refusing: port $p is already in use (is an earlier run still going?)." >&2; exit 2; fi
done
psql_super() { psql -h 127.0.0.1 -p "$PORT" -U "$SUPER" -v ON_ERROR_STOP=1 -Atq "$@"; }
DB_ID="finora_smoke_${ID}_identity"
DB_FIN="finora_smoke_${ID}_finance"
mkdir -p "$WORK/keys" "$WORK/docs" "$WORK/shots"
pids=()
status=1

cleanup() {
  for p in "${pids[@]}"; do kill -- "-$p" 2>/dev/null || kill "$p" 2>/dev/null || true; done
  sleep 2
  psql_super -d postgres -c "drop database if exists $DB_ID with (force)" -c "drop database if exists $DB_FIN with (force)" >/dev/null 2>&1 || true
  left=0
  for p in 18080 18082 18083 13000; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then echo "CLEANUP PROBLEM: port $p is still listening" >&2; left=1; fi
  done
  remaining="$(psql_super -d postgres -c "select count(*) from pg_database where datname like 'finora_smoke_${ID}_%'" 2>/dev/null || echo '?')"
  [ "$remaining" = "0" ] || { echo "CLEANUP PROBLEM: $remaining smoke database(s) remain" >&2; left=1; }
  [ "$left" = 0 ] && echo "Cleanup verified: processes stopped, ports free, smoke databases dropped."
  [ "$status" = 0 ] && [ "$left" != 0 ] && status=1
  echo "Logs and screenshots: $WORK"
  exit "$status"
}
trap cleanup EXIT

psql_super -d postgres -c "create database $DB_ID owner finora_identity_access_migrator" -c "create database $DB_FIN owner finora_finance_migrator" \
  || { echo "Could not create the throw-away databases as '$SUPER' on port $PORT. Is dev PostgreSQL running and are its roles provisioned?" >&2; exit 2; }

FINORA_KEYS_DIR="$WORK/keys" "$ROOT/tools/gen-dev-keys.sh" >/dev/null
head -c 32 /dev/urandom | base64 > "$WORK/mfa.key"
export FINORA_KEYS_DIR="$WORK/keys" FINORA_COOKIE_SECURE=false
cd "$ROOT"
JDBC="jdbc:postgresql://$HOST:$PORT"
SERVER_PORT=18082 DB_URL="$JDBC/$DB_ID" DB_MIGRATION_URL="$JDBC/$DB_ID" FINORA_MFA_KEY_PATH="$WORK/mfa.key" FINORA_INVITATIONS_DEV_OUTBOX_FILE="$WORK/outbox.jsonl" \
  java -jar services/identity-access/target/identity-access-0.1.0-SNAPSHOT.jar >"$WORK/identity.log" 2>&1 & pids+=($!)
SERVER_PORT=18083 DB_URL="$JDBC/$DB_FIN" DB_MIGRATION_URL="$JDBC/$DB_FIN" FINORA_FINANCE_RECURRING_SCHEDULER_ENABLED=false FINORA_FINANCE_DOCUMENTS_STORAGE_DIR="$WORK/docs" \
  java -jar services/finance/target/finance-0.1.0-SNAPSHOT.jar >"$WORK/finance.log" 2>&1 & pids+=($!)
SERVER_PORT=18080 FINANCE_URL=http://127.0.0.1:18083 IDENTITY_ACCESS_URL=http://127.0.0.1:18082 FINORA_GATEWAY_AUTH_CAPACITY=500 FINORA_GATEWAY_RATELIMIT_CAPACITY=600 FINORA_ALLOWED_ORIGINS=http://localhost:13000 \
  java -jar services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar >"$WORK/gateway.log" 2>&1 & pids+=($!)
GATEWAY_URL=http://127.0.0.1:18080 setsid bash -c 'cd web && exec pnpm exec next dev -p 13000' >"$WORK/web.log" 2>&1 & pids+=($!)

for _ in $(seq 1 150); do
  curl -fs http://127.0.0.1:18080/actuator/health >/dev/null 2>&1 && curl -fs http://localhost:13000/login >/dev/null 2>&1 && break
  sleep 1
done
if ! curl -fs http://127.0.0.1:18080/actuator/health >/dev/null 2>&1 || ! curl -fs http://localhost:13000/login >/dev/null 2>&1; then
  echo "The stack did not start. Last lines of each log:" >&2
  for f in identity finance gateway web; do echo "--- $f.log" >&2; tail -n 8 "$WORK/$f.log" | cut -c1-240 >&2; done
  exit 1
fi

export OUTBOX="$WORK/outbox.jsonl"
fail=0
node tests/web-smoke/personal.js "$WORK" || fail=1
node tests/web-smoke/business.js "$WORK" || fail=1
if [ "$fail" != 0 ]; then
  echo "A journey failed. Recent service errors (secrets are not logged by the services):" >&2
  for f in gateway identity finance; do echo "--- $f.log" >&2; grep -E " ERROR | WARN " "$WORK/$f.log" | tail -n 5 | cut -c1-240 >&2; done
  exit 1
fi
status=0
