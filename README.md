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
| `libs/service-testkit` | Test-only base classes (health, exposure, database-privilege checks) |
| `db/<service>/migrations` | Flyway migrations, owned by exactly one service; packaged into that service at build time |
| `web` | Next.js application |
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

## Configuration and secrets
Defaults in each `application.yml` are for local development. Passwords come from the environment only (`FINORA_DEV_DB_PASSWORD`, or `DB_PASSWORD`/`DB_MIGRATION_PASSWORD` per service). No secret belongs in this repository; `.env*` files are git-ignored except `.env.example`.

## Known limitations
- Tests require a running PostgreSQL; there is no Testcontainers setup because the development sandbox has no Docker daemon.
- CI workflow has not been executed on GitHub yet; GitHub Actions are referenced by major-version tag, not pinned by commit SHA.
- No authentication, gateway routing, inter-service communication, or message transport exists yet; those require further owner approvals.
