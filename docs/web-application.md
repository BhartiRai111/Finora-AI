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

## Design system and premium redesign

A single token set in `web/src/app/globals.css` drives every screen: surfaces (`--bg`, `--surface-1..3`), text (`--text`, `--text-muted`, `--text-faint`), one-pixel borders (`--border`, `--border-strong`), a purple primary and electric-blue secondary accent (`--accent*`), status colours used only for meaning (`--success`, `--warning`, `--danger`), chart series colours (`--series-1..6`), radii (`--r-sm/md/lg`), spacing, shadows and focus ring. **Dark is the default theme**; a light variant exists under `[data-theme="light"]`, chosen from the account menu and remembered in the browser (`lib/theme.ts`, applied before first paint by a one-line script in the root layout). All text and status colours were chosen for AA contrast on their surfaces; reduced motion is respected.

* **Top bar** (`components/Header.tsx`): brand, main areas with an active indicator, workspace switcher, quick search (`shell/QuickSearch.tsx`; Ctrl or Cmd + K; it searches the app's own sections, never financial records), an **Add expense** button (`finance/AddExpenseDialog.tsx`, saves through `POST /transactions` with an idempotency key, then every open screen refetches via `lib/dataEvents.ts`), a notifications dropdown (`shell/NotificationsMenu.tsx`: unread count and latest items from the server; roles that may not read notifications get an explanation, not a number), a settings link and the account menu (name and email from the session, account, settings, theme, sign out).
* **Sidebar** (`shell/AppShell.tsx`): icon-and-label sections grouped for the workspace type, a refined active state, hover motion, a **collapsible icon-only state** that is remembered, and a **drawer** on screens narrower than 900 px (closes with Escape, the backdrop or a link). Every item is a real route.
* **Personal sections** now read: Overview, Calendar, Transactions, Budgets, Analytics & Reports, Savings goals, Recurring payments, AI Insights (Main); Accounts, Bills, Subscriptions, Categories (Money); Imports, Documents, Reconcile (Data); Currency & region, Notifications, Alerts, Plan & billing, Integrations (Settings). `/finance/calendar` is new.
* **Dashboard** (`finance/Dashboard.tsx`, the Overview section): five summary cards (total balance, monthly income, monthly expenses, savings, budget remaining) for one currency at a time (currencies are never added together), with the comparison taken from the server's own report comparison (`compare=previous`, showing its amount or percentage and its period; nothing is derived); income-versus-expenses bars, an expense-by-category donut, and spending and savings trends (dependency-free SVG charts in `ui/BarChart.tsx`, with legends, hover tooltips, text descriptions and exact labels); the calendar; budget overview with progress bars and states in words; upcoming bills; recent transactions; savings goals; and an insight card that shows the server's deterministic spending-change explanation, labelled as calculated by Finora and not by an AI model unless the server reports one. Blocks load and fail independently and a new workspace gets an honest empty state.
* **Calendar** (`finance/CalendarPlanner.tsx`): month grid and agenda views, previous, next and Today, day totals per currency, dots for income and for payments due, a selected-day panel with that day's transactions and a button that opens the add-expense form dated to it. Everything is read from the server (`/transactions` for the month's range, `/bills`, `/subscriptions`, `/recurring-rules`).
* **Budget planner**: month navigation (a pre-existing defect where changing the month never refetched is fixed), per-currency totals, per-budget progress with an accessible state text, create, change limit and remove as before.
* **Transactions**: payee shown, sort by date or amount over the rows loaded (the API offers no server-side sort), filters laid out as labelled columns, a confirmation dialog for voiding, and refresh when something is saved elsewhere.
* **Sign-in, registration and landing** use the same surfaces; business workspaces, administration, imports, reconciliation and the other screens take the new look from the shared tokens.

Known limits of the redesign: transactions have no payment-method field in the data model, so none is shown; there is no weekly calendar view; the category breakdown and day totals are exact sums of the transactions the server lists for the month (the API has no category-spend endpoint), and say when a month is larger than what was read; sorting is within loaded rows; the calendar marks only the next due date the server holds for each bill, subscription and recurring rule; the business area has no calendar or chart dashboard (its summary is restyled, not rebuilt); search covers sections only.

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
* **Imports:** CSV only (the backend has no Excel reader); the browser's file checks mirror but never replace the server's. There is no way to edit a bad row in place: fix the file and import again, or download the error report.
* **Not surfaced in the UI although the backend has them:** tag management (`/tags`), the net-worth monthly series endpoint (the net-worth report covers it), and
  the single-rate lookup (`/finance-rates/lookup`). Sorting is offered only where an API provides it; most lists are ordered by the server.
* Each panel keeps its own heading under the shell's page title, so a few pages repeat the title once. Tables scroll horizontally on narrow screens rather than
  reflowing.

## AI assistant screen (Step 24)

The screen shows what the backend returns and nothing else. It states the read-only guarantee, the per-person question limit and, when no provider is configured (today), that no AI model wrote the answers. Each answer shows how the question was understood, the period, each table with its kind (verified, estimate, hypothetical, potential anomaly, converted) and currency, and the assumptions, limits and sources. Supported requests: free questions (spending, categories, expense change, cash flow, unusual transactions, forecast, what-if, overdue invoices, GST summary, project profitability), the explain-a-change and anomaly requests (optionally for one currency), and what-if scenarios with percentage assumptions checked locally before sending.

States: loading; **no data**, **needs clarification** and **unsupported** (shown as information, never as an error or an invented number); **rate limited** and **refused** (role, plan or entitlement) in the server's own words; provider unavailable, failed or blocked (the verified figures stay visible and the AI text is absent); network and server errors with a retry. A reply that does not have the expected shape is never rendered: it becomes a plain "cannot read" error. A second request is not sent while one is running (button, example questions and Enter), so a double click cannot ask twice or use up the limit.

## Import, document and reconciliation screens (Step 24)

* **File rules (as the server enforces them):** imports accept CSV only, up to 2 MB, 5,000 rows, 40 columns and 1,000 characters per cell. The browser refuses spreadsheets (`.xlsx`, `.xls`, `.xlsm`, `.ods`), any non-CSV name, empty and oversized files before sending; the server's own refusals (too large, too many rows, not readable text, unknown kind) are shown in its words. **Excel is not supported** by the backend. Documents accept PDF, PNG or JPEG up to 10 MB, judged by content on the server.
* **Journey:** choose file (and account for personal imports) → upload (one import per file; a retry after a failure reuses the same idempotency key, a double click sends one request, and a same-named earlier file is mentioned but not blocked) → the file's first rows are shown beside the column mapping → "Check the file" validates every row (the row list is fetched again after checking, so it never shows rows from before) → preview with ready, problem, duplicate and possible-duplicate counts, row filter and paging → an explicit choice for possible duplicates and an explicit confirmation → the result is shown only as the server reports it (completed, partial with rejected rows, failed, cancelled). A running import is checked every 3 seconds and is shown as finished only when the server says so. Rejected rows download as an error report. History is paged by the server.
* **Not done silently:** invalid rows are never committed; duplicates need an explicit choice; reconciliation suggestions are matched only after "Confirm match" (and can be undone with a reason); a document creates a record only after the person confirms checked values.
* **OCR:** no OCR engine is configured; documents say text reading is unavailable and the values are typed by the person.

## Workspace administration (Step 24)

The Administration section (Owner and Admin in the navigation; the server allows any role with company-settings read, which also includes Accountants, and answers 403 to the others) shows, for this workspace only: the overview counts, plan and entitlements (provisional plans are labelled), integration health for every external service from real configuration and records, up to five open alerts (titles only, no figures) with links, notifications count, and billing history paged by the server (application records, never confirmed payments without a verified reference). Each part loads and fails on its own with a retry. It states, once, that platform-wide administration is **not available** and why: it needs an approved platform-staff identity and authorization model (sign-in, roles, limits, audit and separation from workspace roles). There are no disabled or pretend controls for it, no staff roles and no bypass; forged role or tenant headers are ignored by the server (verified in the end-to-end suite).

## Tests: what each layer uses

| Layer | Command | Uses | Covers |
|---|---|---|---|
| Component and unit | `cd web && pnpm test` | **mocked `fetch`**, jsdom, no backend, no browser | screens, states, validation, accessibility interactions |
| Lint, types, build | `cd web && pnpm lint && pnpm typecheck && pnpm build` | none | static checks |
| Backend end-to-end | `./mvnw -B verify -pl tests/e2e,services/api-gateway,services/identity-access,services/finance -am -Dtest='*EndToEndTest,RouteTableTest' -Dsurefire.failIfNoSpecifiedTests=false` (needs dev PostgreSQL) | **real** gateway, identity-access, finance, PostgreSQL over HTTP; no browser | sessions, CSRF, roles, isolation, imports, assistant, administration, unconfigured providers (`Step24EndToEndTest` is new) |
| Full backend | `./mvnw -B verify` | real services and database | everything above plus unit and service tests |
| Browser smoke | `tests/web-smoke/run.sh` (manual, see its README) | **real browser and real stack**, three labelled stubs | complete journeys in the app |

No test uses a real AI, OCR, payment, accounting or delivery provider, and none needs third-party credentials: none of those exists in this repository. Stubs and mocks are confined to the component tests and the three labelled browser stubs.

## Verification

Automated: `cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build` (component tests with a mocked `fetch`; they never claim to exercise the real
backend). The Java end-to-end suite (`tests/e2e`) exercises the gateway contracts the UI relies on. A **manual** real-browser smoke test against the real stack
is in `tests/web-smoke/` (see its README); it is not part of the automated gates.
