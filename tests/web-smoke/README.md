# Browser smoke test (manual, real stack)

Drives the Next.js app in a real Chromium against the real gateway, identity-access, finance service and PostgreSQL.
**It is manual.** It is not part of `./mvnw verify`, `pnpm test` or any CI job, and nothing runs it automatically. Run it with:

```bash
./mvnw -B package -DskipTests            # the services' jars
tools/dev-postgres.sh up                  # then source its environment (FINORA_DB_HOST, FINORA_DB_PORT, FINORA_DEV_DB_PASSWORD)
(cd web && pnpm install --frozen-lockfile)
NODE_PATH=$(npm root -g) tests/web-smoke/run.sh [work-dir]     # Playwright must be resolvable through NODE_PATH
```

## Setup, isolation and cleanup

* It creates **its own throw-away databases** (`finora_smoke_<id>_identity` and `_finance`) as the database superuser (default `postgres`; `SMOKE_PG_SUPERUSER`), so dev data is never touched, and drops them at the end.
* It starts identity-access, finance, the gateway and `next dev` on local ports 18082, 18083, 18080 and 13000 with generated throw-away keys, and refuses to start if any of those ports is busy, if the database host is not local, or if the web address is not localhost.
* On exit (success, failure or interrupt) it stops everything it started, drops its databases and **verifies** that the ports are free and no smoke database remains; a leftover is reported as `CLEANUP PROBLEM` and fails the run.
* The gateway's per-person burst limit is raised for this run only, because scripted navigation is faster than a person; the limit itself is covered by gateway tests.
* Accounts use `example.test` addresses. Passwords, cookies and CSRF tokens are never printed.

## Diagnostics

Each journey prints numbered checks. On failure it prints the failing check, the page URL and the first part of the page text, recent browser console and server errors, saves a full-page screenshot (`<work-dir>/shots/failure-*.png`), and `run.sh` adds recent service errors. Unexpected 5xx responses or uncaught page errors fail a journey even when its checks passed. Logs: `<work-dir>/{identity,finance,gateway,web}.log`.

## What is real and what is stubbed

Everything is real (browser, app, gateway, identity-access, finance, PostgreSQL), including files chosen in the browser and downloads, except three clearly labelled `[stub]` checks in `personal.js` that answer one request in the browser (a rate-limit reply, an unreadable assistant reply, a 503 from the alerts endpoint) because the real stack does not produce those on demand. The Java end-to-end suite covers the real backend's rate limit.

* `personal.js` (54 checks, now including the redesigned shell: calendar month and day totals, adding an expense from the calendar and from the top bar, the notifications dropdown, quick search, sidebar collapse and theme choice): anonymous deep link to sign-in and back, safe `next`, not-found section, real data through the API, overview, report chart and exact figures, reload restoring the session, assistant (supported answer from real data, unsupported and hostile questions change nothing, what-if guardrail, honest AI state, `[stub]` rate limit and unreadable reply), imports (PDF refused, oversized refused, double click is one import, preview with the file's first rows, invalid row explained, no write before the explicit confirmation, partial result and error report download), statement import and reconciliation (a suggestion is matched only after the explicit confirmation), notifications read by the server, alerts on request, regional settings, plan and integrations unconfigured, `[stub]` failing service with recovery, account menu, small screen, and a session that ends elsewhere.
* `business.js` (46 checks): Owner MFA enrolment, workspace creation, role-aware navigation, clients, an issued invoice, an expense CSV import (preview creates nothing, explicit confirmation), receivables report, GST page labelled not an official filing, business assistant (overdue invoices from the server, unsupported question), accounting integration not configured, reconciliation page, the workspace-only administration page with platform administration stated as not available, an Accountant denied administration by URL before any administration data is requested, and a Viewer denied in the UI and by the server (403 on read and on mutations, nothing created) with a foreign workspace id answering 404.
