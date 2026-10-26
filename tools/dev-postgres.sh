#!/usr/bin/env bash
# Local PostgreSQL for Finora development and tests. One database per service, each with a
# migrator role (owns the database, runs Flyway) and a runtime role (DML only, no DDL).
#
# Usage: tools/dev-postgres.sh {init|start|stop|status|provision|up|reset}
#   init       create a local cluster (first time only)
#   start|stop|status   control the local cluster
#   provision  create/refresh roles and databases (idempotent); needs FINORA_DEV_DB_PASSWORD
#   up         init (if needed) + start + provision
#   reset      destroy the local cluster and run `up`
#
# Environment (all optional except the password):
#   FINORA_DEV_DB_PASSWORD   password for all local roles (never commit a real secret)
#   FINORA_PG_BIN            directory with initdb/pg_ctl/psql (default: pg_config --bindir)
#   FINORA_PG_DATA           data directory (default: <repo>/.local/postgres)
#   FINORA_PG_PORT           port for the local cluster (default: 5433)
#   FINORA_PG_EXTERNAL=1     do not manage a cluster; provision an existing server using
#                            PGHOST/PGPORT/PGUSER/PGPASSWORD (used by CI)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_BIN="${FINORA_PG_BIN:-$(pg_config --bindir 2>/dev/null || echo /usr/lib/postgresql/16/bin)}"
PG_DATA="${FINORA_PG_DATA:-$ROOT/.local/postgres}"
PG_PORT="${FINORA_PG_PORT:-5433}"
EXTERNAL="${FINORA_PG_EXTERNAL:-0}"
SERVICES=(identity-access finance document ingestion-integration reporting intelligence platform audit)

# PostgreSQL refuses to run as root; when we are root (for example in a container) use the postgres OS user.
as_pg() { if [ "$(id -u)" = "0" ] && [ "$EXTERNAL" != "1" ]; then runuser -u postgres -- "$@"; else "$@"; fi; }

psql_admin() {
  if [ "$EXTERNAL" = "1" ]; then
    "${FINORA_PG_BIN:+$FINORA_PG_BIN/}psql" -X -q -v ON_ERROR_STOP=1 -d postgres "$@"
  else
    as_pg "$PG_BIN/psql" -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$PG_PORT" -U postgres -d postgres "$@"
  fi
}

cmd_init() {
  if [ -f "$PG_DATA/PG_VERSION" ]; then echo "cluster already initialised at $PG_DATA"; return; fi
  mkdir -p "$PG_DATA"
  if [ "$(id -u)" = "0" ]; then chown postgres:postgres "$PG_DATA"; fi
  chmod 700 "$PG_DATA"
  # Local development only: trust on the Unix socket and loopback for the postgres superuser,
  # scram-sha-256 for every application role over TCP.
  as_pg "$PG_BIN/initdb" -D "$PG_DATA" -U postgres -E UTF8 --auth-local=trust --auth-host=scram-sha-256 >/dev/null
  # pg_hba.conf is first-match: the trust rule for the postgres superuser must come before the scram rule.
  as_pg sh -c "{ printf 'host all postgres 127.0.0.1/32 trust\n'; cat '$PG_DATA/pg_hba.conf'; } > '$PG_DATA/pg_hba.conf.new' && mv '$PG_DATA/pg_hba.conf.new' '$PG_DATA/pg_hba.conf'"
  echo "initialised $PG_DATA"
}

cmd_start() {
  if as_pg "$PG_BIN/pg_ctl" -D "$PG_DATA" status >/dev/null 2>&1; then echo "already running"; return; fi
  as_pg "$PG_BIN/pg_ctl" -D "$PG_DATA" -l "$PG_DATA/server.log" -w \
    -o "-p $PG_PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=$PG_DATA" start >/dev/null
  echo "started on 127.0.0.1:$PG_PORT"
}

cmd_stop()   { as_pg "$PG_BIN/pg_ctl" -D "$PG_DATA" -m fast stop >/dev/null 2>&1 && echo "stopped" || echo "not running"; }
cmd_status() { as_pg "$PG_BIN/pg_ctl" -D "$PG_DATA" status; }

cmd_provision() {
  : "${FINORA_DEV_DB_PASSWORD:?set FINORA_DEV_DB_PASSWORD (local development password; do not commit it)}"
  for svc in "${SERVICES[@]}"; do
    local db="finora_${svc//-/_}"
    psql_admin -v pw="$FINORA_DEV_DB_PASSWORD" -v mig="${db}_migrator" -v app="${db}_app" -v db="$db" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'mig', :'pw')
  WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'mig') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app', :'pw')
  WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'mig', :'pw') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'app', :'pw') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'mig')
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db') \gexec
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"app";
SQL
    echo "provisioned $db"
  done
}

case "${1:-}" in
  init) cmd_init ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  provision) cmd_provision ;;
  up) if [ "$EXTERNAL" != "1" ]; then cmd_init; cmd_start; fi; cmd_provision ;;
  reset) cmd_stop || true; rm -rf "$PG_DATA"; "$0" up ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
