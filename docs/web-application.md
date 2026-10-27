# Web application (Step 23: core application UI)

The Next.js app in `web/` is a client of the existing gateway APIs. It has no backend of its own, no database and no secrets. Everything shown comes from
the backend; the browser lays out the backend's figures and never recalculates money, tax, balances or conversions. Step 23 changed no backend code and no
migrations.

## Route structure

| Route | Who | What |
|---|---|---|
| `/` | anyone | landing page |
| `/login`, `/register` | anonymous | sign in (with optional `?next=` return path), create an account |
| `/invitations/accept` | anyone with an invitation link | accept an invitation (the token is in the URL fragment, never sent as a query) |
| `/account` | signed in | profile, password, two-step verification (MFA) |
| `/workspaces` | signed in | list workspaces, create a business workspace |
| `/finance` and `/finance/<section>` | signed in | **personal finance** in the person's own personal profile |
| `/workspaces/<id>` and `/workspaces/<id>/<section>` | members | **business workspace** finance and workspace administration; a personal profile id redirects to `/finance` |
| `/api/gw/*` | (proxy) | same-origin proxy to the gateway; `/api/health` is the web app's own health check |

Sections are real URLs (deep links, reload, back and forward all work). A missing section answers "Page not found" inside the shell; a section the role
should not have answers "Access denied" and loads none of its data.

* Personal sections (`web/src/lib/sections.ts`, `PERSONAL_SECTIONS`): overview, accounts, transactions, recurring, bills, subscriptions, budgets, goals,
  categories, reports, assistant, imports, documents, reconcile, regional (currency and region), notifications, alerts, plan, integrations.
* Business sections (`BUSINESS_SECTIONS`): summary, invoices, payments, expenses, clients, vendors, projects, reports, assistant, imports, documents,
  reconcile, regional, GST, notifications, alerts, integrations, plan, administration (workspace only), business settings, members, invitations, workspace.

## Application shell

* `AppShell` (`components/shell`): a labelled section navigation grouped by purpose, the current page marked with `aria-current="page"`, a page title and a
  one-sentence description (also the document title), and a "Sections" menu button on small screens. It makes no requests.
* Top bar (`Header`): brand, workspace switcher (follows the page; a personal profile goes to `/finance`, a business workspace to `/workspaces/<id>`), the two
  main areas, and the **account menu** (`AccountMenu`: name and email from the session, Account and security, Sign out; arrow keys, Escape, focus return).
* A skip-to-content link, visible focus outlines, `prefers-color-scheme` themes and reduced-motion respect are in `globals.css`.
* Shared states: `Loading` (status text plus placeholder bars), `ErrorState`, `EmptyState`, `AccessDenied`, `Skeleton` (`components/ui/States.tsx`) and
  accessible dialogs (`components/ui/Dialog.tsx`: labelled modal, focus moves in and is kept inside, Escape closes, focus returns). Browser `prompt()` is no
  longer used: voiding an exchange rate, resolving an alert and cancelling a subscription use `ReasonDialog`, which shows a failure without closing and ignores
  a second submit while the first is running.

## Authentication, sessions and API access

* The session is the existing first-party server-side opaque session in an **HttpOnly, same-origin cookie**; the browser never reads it. Only the CSRF token
  is held in memory (never in `localStorage`, `sessionStorage` or a URL) and is sent as `X-CSRF-Token` on state-changing requests. Passwords are only sent to
  the sign-in/registration endpoints.
* `SessionProvider` restores the session with `GET /v1/me` on load and exposes `loading`, `anonymous`, `mfa` (second step required), `error` and `ready`.
  `AuthGate` has an explicit screen for each, including a retry when the server cannot be reached, so nothing waits forever.
* **Expired or revoked sessions:** if any request made while signed in is refused as unauthenticated (`401 AUTH_REQUIRED`), the app leaves the signed-in
  state and shows "Your session has ended" with a link back to sign in. A permission refusal (403) does not end the session.
* **Return after sign-in:** the sign-in link carries `?next=<path>`; `safeNext` accepts only same-site paths (no absolute URLs, `//host`, backslashes or
  control characters; never `/login` or `/register`), otherwise the person lands on `/workspaces`.
* Authorization is the server's. Navigation visibility and the role hints in `lib/business.ts`, `lib/permissions.ts` and `lib/sections.ts` only decide what to
  show; every request is authorized again by the gateway and the service, and a hidden section typed by hand is denied by the server as well (verified in the
  browser smoke test: a Viewer gets 403 on the invoices API and 404 on a foreign workspace id).
* Errors: `describeError` maps statuses and codes (network, rate limit with retry time, session ended, CSRF, MFA step-up, forbidden, not found, validation,
  conflict, feature unavailable, server error) to safe messages; server internals are never shown. A plan or subscription refusal explains itself ("Your ...").
* Writes use an `Idempotency-Key` that is reused when the same form is resubmitted after a failure, so a retry cannot duplicate the record.

## Configuration

| Variable | Where | Purpose |
|---|---|---|
| `GATEWAY_URL` | web server only, read when `next dev`/`next build` starts (default `http://127.0.0.1:8080`) | target of the `/api/gw/*` proxy. It is never sent to the browser |
| `FINORA_ALLOWED_ORIGINS` | gateway | browser origins allowed to make state-changing requests (must include the web origin) |
| `FINORA_COOKIE_SECURE` | identity-access, gateway | `false` only for plain-HTTP local development |

There are no `NEXT_PUBLIC_*` variables and the app contains no API keys, URLs of services other than its own origin, or credentials.

## Workflows available today

Personal (each uses the real endpoints and shows the server's result): accounts, transactions (search, filter, cursor paging, create, correct, void),
recurring rules, bills and subscriptions, budgets, goals, categories, reports with CSV export (cash flow, budget versus actual, net worth) and a forecast, CSV
import (preview, column mapping, row errors, duplicate policy, explicit commit), documents, reconciliation (suggestions confirmed by the person), the
assistant, currency and regional settings with fiscal periods and manual exchange rates, notifications, alerts, plan and billing records, integration status.

Business: clients, vendors, projects with profitability, expenses, invoices (draft, issue, cancel), payments and allocation, summaries, reports (business
performance, receivables aging and by client, project profitability) with export, imports, documents, reconciliation, assistant, regional settings, GST
registration and per-record GST details, the GST summary, the record-level GST transaction list and the preparation worksheet CSV, accounting integration status
and synchronisation history, notifications, alerts, plan and billing, a workspace-only administration overview, business settings, members and invitations.

### Reports and charts

A bar chart is drawn only when the returned data supports it: at least two periods and at least one non-zero value among the charted columns
(`cash-flow`, `business-performance`, `net-worth`, `budget-vs-actual`, `receivables`, `receivables-by-client`, `project-profitability`). Each currency has its own
chart and table; currencies are never added together. Numbers are used only to size bars; labels show the backend's own decimal strings, the chart has a text
description and a legend, and the table with every figure is always beside it. Converted views (Step 21) stay in their own block with the rate date and source, and
a missing or stale rate makes the conversion unavailable rather than guessed.

### Role-dependent behaviour (hints; the server decides)

Owner: everything, including plan changes and the billing contact. Admin: administration, settings, members (no record-level
assistant detail). Accountant: invoices, payments, imports, reconciliation, documents, reports, exports. Contributor: expenses and documents they may upload.
Auditor: read-only finance, imports, reconciliation and members. Manager and Viewer: the workspace section only, because the finance matrix gives them no
workspace-finance read. See `docs/identity-auth.md` and `docs/business-finance.md` for the matrix.

## Honest limitations (unconfigured providers and missing backend capabilities)

* **AI:** no AI provider is configured; the assistant shows deterministic results and says so. Nothing in the UI claims a live model.
* **OCR:** no OCR engine; documents report OCR as unavailable.
* **Payments and billing:** no payment provider; billing records are application records, never shown as confirmed payments unless the server holds a verified
  provider reference. Plans are provisional with no prices.
* **Notification delivery:** in-app only. The notification page reports what the server's channel endpoint says; if it cannot be read, nothing is claimed.
* **Accounting integration and exchange-rate source:** none approved or connected; rates are entered by people and labelled user-supplied.
* **GST:** a preparation worksheet for intra-state and inter-state supplies in INR; unsupported scenarios are labelled; it is not an official return and files
  nothing.
* **Platform-wide administration:** not implemented. It needs a platform-staff identity and authorization model that has not been decided. The administration
  view is explicitly this workspace only.
* **Email:** password-reset, invitation and verification emails are not delivered; email verification and workspace deletion do not exist in the backend.
* **Not surfaced in the UI although the backend has them:** tag management (`/tags`), the net-worth monthly series endpoint (the net-worth report covers it), and
  the single-rate lookup (`/finance-rates/lookup`). Sorting is offered only where an API provides it; most lists are ordered by the server.
* Each panel keeps its own heading under the shell's page title, so a few pages repeat the title once. Tables scroll horizontally on narrow screens rather than
  reflowing.

## Verification

Automated: `cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` (component tests with a mocked `fetch`; they never claim to exercise the real
backend). The Java end-to-end suite (`tests/e2e`) exercises the gateway contracts the UI relies on. A **manual** real-browser smoke test against the real stack
is in `tests/web-smoke/` (see its README); it is not part of the automated gates.
