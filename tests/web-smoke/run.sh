#!/usr/bin/env bash
# MANUAL browser smoke test of the Next.js app against the real backend (gateway, identity-access, finance, PostgreSQL).
# It is NOT part of `./mvnw verify` or `pnpm test`. It needs: built service jars (`./mvnw -B package -DskipTests`), dev PostgreSQL running
# (`tools/dev-postgres.sh up`, with its environment sourced), `web/node_modules` installed, and Playwright resolvable by node
# (NODE_PATH must contain it; a global install is enough). It starts everything on local ports 18080-18083 and 13000 and stops it again.
# Usage: tests/web-smoke/run.sh [work-dir]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${1:-$(mktemp -d)}"
mkdir -p "$WORK/keys" "$WORK/docs" "$WORK/shots"
FINORA_KEYS_DIR="$WORK/keys" "$ROOT/tools/gen-dev-keys.sh" >/dev/null
head -c 32 /dev/urandom | base64 > "$WORK/mfa.key"
export FINORA_KEYS_DIR="$WORK/keys" FINORA_COOKIE_SECURE=false
pids=()
cleanup() { for p in "${pids[@]}"; do kill -- "-$p" 2>/dev/null || kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT
cd "$ROOT"
SERVER_PORT=18082 FINORA_MFA_KEY_PATH="$WORK/mfa.key" FINORA_INVITATIONS_DEV_OUTBOX_FILE="$WORK/outbox.jsonl" java -jar services/identity-access/target/identity-access-0.1.0-SNAPSHOT.jar >"$WORK/identity.log" 2>&1 & pids+=($!)
SERVER_PORT=18083 FINORA_FINANCE_RECURRING_SCHEDULER_ENABLED=false FINORA_FINANCE_DOCUMENTS_STORAGE_DIR="$WORK/docs" java -jar services/finance/target/finance-0.1.0-SNAPSHOT.jar >"$WORK/finance.log" 2>&1 & pids+=($!)
SERVER_PORT=18080 FINANCE_URL=http://127.0.0.1:18083 IDENTITY_ACCESS_URL=http://127.0.0.1:18082 FINORA_GATEWAY_AUTH_CAPACITY=500 FINORA_ALLOWED_ORIGINS=http://localhost:13000 java -jar services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar >"$WORK/gateway.log" 2>&1 & pids+=($!)
GATEWAY_URL=http://127.0.0.1:18080 setsid bash -c 'cd web && exec pnpm exec next dev -p 13000' >"$WORK/web.log" 2>&1 & pids+=($!)
for _ in $(seq 1 120); do
  curl -fs http://127.0.0.1:18080/actuator/health >/dev/null 2>&1 && curl -fs http://localhost:13000/login >/dev/null 2>&1 && break
  sleep 1
done
curl -fs http://127.0.0.1:18080/actuator/health >/dev/null || { echo "gateway did not start; see $WORK/gateway.log" >&2; exit 1; }
OUTBOX="$WORK/outbox.jsonl" node tests/web-smoke/personal.js "$WORK"
OUTBOX="$WORK/outbox.jsonl" node tests/web-smoke/business.js "$WORK"
echo "Screenshots: $WORK/shots"
