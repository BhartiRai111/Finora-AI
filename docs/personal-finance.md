# Personal Finance Service (Step 17)

The `finance` service owns personal finance data. It serves **personal workspaces only**; business-workspace finance is a later step.
Everything below is an implemented default, not a compliance, accounting or tax claim.

## Scope

Accounts (cash, bank, wallet, investment, other asset, credit card, loan, other liability), income/expense/transfer/refund transactions,
categories (one level of nesting), tags, notes, editing, voiding (no hard delete), recurring rules, monthly budgets with budget-vs-actual,
savings goals with contributions, bills and subscriptions, net worth and cash-flow summaries. Screens: `/finance` in the web app.

Database migration: `db/finance/migrations/V3__personal_finance.sql` (V1/V2 untouched).

## Access model

* The gateway issues a workspace-scoped internal token whose scope is `workspace:<type>` (from the membership). Finance requires
  `workspace:personal`; a business or unknown scope gets 409, an anonymous caller 401, a path/token workspace mismatch 404, a non-owner 403.
* Every table carries `tenant_id` (the personal workspace id) with composite `(tenant_id, id)` foreign keys; every query filters by it.
  There is no row-level security yet (pending tenant-context work).
* Authorization uses the shared `PermissionMatrix`; all finance mutations are written to `finance_audit_events`
  (local, append-only: UPDATE/DELETE/TRUNCATE are revoked). Audit rows hold ids and field names, not amounts, notes or secrets.

## Financial correctness

* Money is `NUMERIC(19,4)` in the database, `BigDecimal` in Java and a **string** in JSON/the browser; nothing is parsed as a float.
* Each account has one currency; the currency exponent table (`Currencies`) rejects over-precise input (e.g. JPY has 0 decimals, KWD 3).
* Balances = opening balance + exact sum of POSTED legs as of a date. Liabilities report the amount owed (money paid lowers it).
* Transfers are never income or expense. Cross-currency transfers require both amounts; no FX rate is invented (FX policy PD-09 is pending).
* Net worth is computed per currency and never summed across currencies.
* "Today", month boundaries and recurring schedules use the user's IANA time zone (`PUT /finance-settings`, default UTC).
* Writes that create things take an `Idempotency-Key`; the same key with the same body replays the stored response, with a different body
  returns a conflict. The web client keeps one key per user intent until it succeeds.
* Edits use optimistic `version` checks. Voided transactions are excluded from every total and can be restored.

## Defaults chosen (not owner-approved)

* Budgets: monthly, a parent category includes its children; UNDER below 80%, WARNING 80%–100% inclusive, OVER above 100%.
* Recurring: occurrences are computed from the start date (so the 31st clamps to month end without drifting), created as **DRAFT** unless
  `auto_post` is set, at most one per `(rule, occurrence_index)`, catch-up limited to 366 per run, resume skips the paused period.
  The in-process scheduler can be disabled with `FINORA_FINANCE_RECURRING_SCHEDULER_ENABLED=false`; "Process due now" runs the same code.
* Bills/subscriptions: annualised cost uses WEEKLY 52, MONTHLY 12, QUARTERLY 4, YEARLY 1; the monthly equivalent rounds HALF_EVEN.
* Goals: required monthly saving is the remaining amount divided by whole months left, rounded up.

## Known limitations

No FX conversion; no attachments, split transactions, duplicate detection, bulk edit, saved filters, alerts or notifications (no email provider),
credit-limit utilisation, CSV export or hard delete; no RLS; local audit is not the PD-15 audit architecture; the scheduler is in-process
(single instance); business finance, AI features and document ingestion are not part of this step.
