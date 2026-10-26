# Finora-AI

AI expense and financial intelligence SaaS: personal finance, business finance (GST-aware), and a data-grounded AI assistant.

**Status: project foundation only.** The repository contains the build setup, service skeletons, database tooling, a web shell, and CI. No product features exist yet, and no AI provider is integrated (provider and model are still pending owner approval).

## Approved stack
Microservices (Option A), Java 21 + Spring Boot 4.1.2, Maven, Flyway, PostgreSQL 16, Next.js (pnpm, TypeScript strict).

## Layout
| Path | Purpose |
|---|---|
| `services/<name>` | One Spring Boot service per approved boundary: `api-gateway`, `identity-access`, `finance`, `document`, `ingestion-integration`, `reporting`, `intelligence`, `platform`, `audit`, `admin-plane` |
| `libs/finance-core` | Shared pure calculation library (empty by design until money/rounding policy is decided) |
| `tests/e2e` | End-to-end tests: start the packaged gateway and identity-access jars as separate processes against PostgreSQL (needs `./mvnw verify`) |
| `libs/service-commons` | Cross-cutting technical code (no business logic): correlation ids, safe problem errors, internal token auth, resilient downstream client, idempotency/outbox/inbox persistence |
| `libs/service-testkit` | Test-only base classes and helpers (health, exposure, internal-auth, database-privilege and service-communication checks; throw-away keys; loopback stub server) |
| `db/<service>/migrations` | Flyway migrations, owned by exactly one service; packaged into that service at build time |
| `web` | Next.js application |
| `tools/gen-dev-keys.sh` | Throw-away P-256 keys for local internal service authentication (`.dev-keys/`, git-ignored) |
| `tools/dev-postgres.sh` | Local PostgreSQL 16 with one database and two roles (migrator, runtime) per service |
| `.github/workflows/ci.yml` | CI: backend (Maven + PostgreSQL) and web (lint, typecheck, test, build) |

Services never share tables or read each other's databases; the runtime role of one service cannot even connect to another service's database (checked by tests).

## Default ports (loopback only)
`api-gateway` 8080 · `identity-access` 8081 · `finance` 8082 · `document` 8083 · `ingestion-integration` 8084 · `reporting` 8085 · `intelligence` 8086 · `platform` 8087 · `audit` 8088 · `admin-plane` 8089 · web 3000. Services bind to `127.0.0.1` unless `SERVER_ADDRESS` is set.

## Prerequisites
JDK 21+, Maven (or the included `./mvnw`), Node ≥ 22.13 with pnpm 10, PostgreSQL 16 server binaries (local) — or any PostgreSQL 16 reachable via `PGHOST`/`PGPORT`/`PGUSER`.

## Quick start
```bash
cp .env.example .env            # placeholders only; edit locally, never commit
export FINORA_DEV_DB_PASSWORD=<choose a local password>
export FINORA_DB_PORT=5433
tools/dev-postgres.sh up        # init + start + provision local PostgreSQL (see script header for options)
./mvnw -B verify                # build and test every module (needs the database)
cd web && pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build
```
Run one service: `./mvnw -pl services/finance spring-boot:run` (health: `http://127.0.0.1:8082/actuator/health`).
When running as root (for example in a container), the script runs PostgreSQL as the `postgres` OS user and needs `FINORA_PG_DATA` outside your home directory.

## Gateway and service communication (Step 14)
- **Edge:** `api-gateway` is the only public entry for `/v1/**`. It validates request shape, matches one explicit route (longest match), rate limits (in memory, single replica), authenticates the session, mints a short-lived token for the single owning service, forwards allowlisted headers only, and maps failures to safe `application/problem+json` (`UPSTREAM_TIMEOUT` 504, `UPSTREAM_UNAVAILABLE` 503, `UPSTREAM_ERROR` 502). `/internal/**` and `/admin/**` are never routed.
- **Private services:** every service requires an internal token (ES256, one audience, 2-minute default lifetime) on everything except `/actuator/health/**`. Only the gateway can assert end users; services assert themselves. A user token must match the workspace in the path. Add a calling service under `finora.security.internal-token.trusted-issuers.<name>.public-key-path` only after an internal contract for it is approved.
- **Correlation:** `X-Request-Id` (UUID) and `traceparent` are validated, generated when absent, forwarded and echoed.
- **Resilience:** per-attempt timeout and total deadline; retries only for idempotent methods or requests with `Idempotency-Key` (a refused connection is retried for any request); circuit breaker and bulkhead per downstream.
- **Persistence:** each database-owning service has `idempotency_keys`, `outbox_events` and `inbox_events` (migration V2). The outbox is storage only: no relay, broker or transport exists.
- **Local keys:** `tools/gen-dev-keys.sh` writes `.dev-keys/gateway.{key,pub}.pem`; set `FINORA_KEYS_DIR` to use another directory. Tests generate their own keys in memory.

## Identity and sessions (Step 15)
First-party server-side opaque sessions in identity-access, validated by the gateway on every request; Argon2id passwords; CSRF token + Origin check; login throttling; expiring single-use reset tokens. Details, defaults and gaps: [`docs/identity-auth.md`](docs/identity-auth.md).

## Configuration and secrets
Defaults in each `application.yml` are for local development. Passwords come from the environment only (`FINORA_DEV_DB_PASSWORD`, or `DB_PASSWORD`/`DB_MIGRATION_PASSWORD` per service). No secret belongs in this repository; `.env*` files are git-ignored except `.env.example`.

## Known limitations
- Tests require a running PostgreSQL; there is no Testcontainers setup because the development sandbox has no Docker daemon.
- CI workflow has not been executed on GitHub yet; GitHub Actions are referenced by major-version tag, not pinned by commit SHA.
- Authentication (Step 15) is implemented: see `docs/identity-auth.md`. **Password-reset email cannot be delivered** (no provider configured), MFA and email verification are not implemented, and workspace membership/authorization is Step 16, so every `/v1/spaces/{id}/**` path returns 404 for now.
- No message transport/broker (PD-04), no mTLS (deployment platform undecided, PD-08), no job router (`/v1/jobs/{id}`), no streaming (SSE) through the gateway, and no row-level security/tenant context yet.
- Rate limiting is in memory and per gateway replica; response bodies are buffered (no size cap on downstream responses yet); numeric timeouts and limits are engineering defaults, not owner-approved values (DEC-50).
- Cross-process behaviour was checked locally with the packaged jars (gateway + identity-access), not in CI or on real infrastructure.
