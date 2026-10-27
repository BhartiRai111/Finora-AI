# Business Finance (Step 18)

Business finance lives in the `finance` service (PD-01 Option A: invoice issue, numbering, payments and audit must be atomic, so they share one
database). It serves **BUSINESS workspaces only**; personal finance (Step 17, `docs/personal-finance.md`) serves PERSONAL workspaces only. Both sit
on the same gateway routes and the same internal-token chain. Everything below is an implemented default, not a tax, accounting or legal claim.

Migration: `db/finance/migrations/V4__business_finance.sql` (V1–V3 untouched). Web: the **Finance** tab of a business workspace.

## Access model

* The gateway mints a workspace-scoped internal token with scope `workspace:business` (from the membership). The service requires it:
  anonymous 401; token workspace ≠ path 404; personal/unknown scope 409 (and the personal API refuses a business workspace the same way).
* The workspace id, user id and role come from the verified token only; ids and tenant fields in bodies, queries and headers are never trusted.
  Every table carries `tenant_id` with composite `(tenant_id, id)` foreign keys, and every query filters by it. A foreign id answers exactly like a
  missing one (404/422). No row-level security yet.
* Permissions come from the shared `PermissionMatrix` (resource mapping: clients/vendors → `CLIENTS_VENDORS`, projects → `PROJECTS`,
  expenses and expense categories → `TRANSACTIONS`, invoices → `INVOICES` (issue = `ISSUE`, cancel = `ARCHIVE`), payments → `PAYMENTS` (void =
  `ARCHIVE`), summaries → `REPORTS`, settings → `COMPANY_SETTINGS`). Resulting behaviour: Owner and Accountant read and write; Admin and Auditor read
  (Admin may also change settings); Contributor may create, see and edit only **their own** expenses (matrix scope OWN); Manager and Viewer have no
  access. Matrix scopes this service cannot evaluate (ASSIGNED, IF_GRANTED, LIMITED) fail closed because no assignment or viewer-grant model exists
  yet; Admin's LIMITED report view is allowed for the aggregate summaries only.
* Writes append to the local `finance_audit_events` table (ids, field names and amounts, never notes, references or secrets). It is not the PD-15
  audit architecture and makes no tamper-evidence claim. Logs contain no names, amounts, notes or references (e2e-tested).

## Data model (summary)

`business_settings` (time zone default Asia/Kolkata, base currency default INR, invoice prefix/padding/next number, default terms), `clients`,
`vendors`, `projects` (one currency, optional client and cost budget, status), `expense_categories`, `business_expenses`, `invoices`,
`invoice_lines`, `payments`, `payment_allocations`. Money is `NUMERIC(19,4)`, an ISO currency on every amount, strings in JSON; the service
validates each amount against the currency's decimals and refuses extra precision instead of rounding. Nothing is ever converted between currencies:
a project, its invoices and expenses share one currency; a payment can only be applied to an invoice of its own currency and client.

## Invoice lifecycle

`DRAFT` (editable, replace lines, no number) → `ISSUED` (number assigned, content frozen: `INVOICE_IMMUTABLE`) → `CANCELLED` (terminal). A draft can
also be cancelled (discarded, never numbered). Issue needs `confirm: true`, an `expected_total` that equals the stored total (`TOTAL_MISMATCH`), a
non-zero total, an active client and an open project, and an `Idempotency-Key`. Cancel needs `confirm` and a reason; an issued invoice with payments
that still stand cannot be cancelled (`ALLOCATION_EXISTS`: void the payments first). There are no credit/debit notes or refunds yet.

**Numbering**: `prefix + zero-padded sequence` (prefix up to 12 of `A-Za-z0-9/_-`, padding 1–10). The number is taken at issue with one atomic
`UPDATE … RETURNING` on the settings row, so concurrent issues queue and get unique numbers; a failed issue rolls the increment back, so numbers are
never skipped, and the sequence can only move forward. A unique index backs this. No provisional numbers on drafts (DEC-34 default). The format is
configurable; no legal numbering rule (series, fiscal year, length limits) is claimed (EXT-01).

**Derived status** (never stored; computed on every read from payments that stand):
`outstanding = total − Σ allocations of RECORDED payments` (0 unless ISSUED); `PAID` when outstanding is 0; `OVERDUE` when outstanding > 0 and
`due_date < today` in the business time zone (the due date itself is not overdue; it flips at the business's midnight); else `PARTIALLY_PAID` (paid > 0)
or `UNPAID`. Due date = explicit date, else invoice date + terms (days given, else the client's terms, else the workspace default) and never before the
invoice date.

## Line arithmetic

`gross = round(qty × unit price)`, `net = gross − discount`, `tax = round(net × percent / 100)`, `line total = net + tax`, each rounded **once** to the
currency's precision, HALF_UP, at line level; invoice totals are exact sums of the line values. Quantity: up to 4 decimals, > 0. Discount is an
amount per line (0 ≤ discount ≤ gross). The tax percentage is whatever the user types (0–100, 2 decimals): **no GST rules, rates, place-of-supply or
HSN logic exist**, so this is not GST compliance. HALF_UP line-level rounding is a working proposal (PD-09; EXT-01 not done).

## Payments and allocation

Payments are money **received** from a client (no payables workflow). A payment may carry explicit allocations, `auto_allocate` (oldest due date
first), or none (an advance). Rules: each allocation ≤ the invoice's outstanding amount and the sum ≤ the payment amount (`ALLOCATION_EXCEEDS`);
only ISSUED invoices of the same client and currency; an invoice appears once per request. Invoices are locked `FOR UPDATE` in id order before the
check, so concurrent payments cannot overpay. Whatever is not allocated stays on the payment as an explicit unallocated balance and can be applied
later (`POST /payments/{id}/allocations`) but never twice. `void` reverses every allocation at once (irreversible; the reason is recorded). Duplicate
protection: `Idempotency-Key` is mandatory (replay returns the first result), and the same client cannot use the same reference twice while the first
payment stands (partial unique index). Payment dates cannot be in the future (business time zone).

## Metric definitions (all per currency, never merged)

* **Invoiced (net)** = Σ (line amounts − discounts) of ISSUED invoices dated in the period, excluding tax; tax is reported separately as entered.
  Drafts and cancelled invoices are never counted.
* **Received** = payments that still stand dated in the period (including their unallocated part). **Expenses** = POSTED expenses dated in the period.
* **Net cash** = received − expenses. **Operating result** = invoiced net − expenses (accrual view, excluding tax).
* **Receivables** = outstanding of issued invoices as of today; **overdue** = the part past its due date; aging buckets by days past due
  (not yet due, 1–30, 31–60, 61–90, over 90); **unallocated payments** = credit not yet applied.
* **Project profit** = invoiced net revenue − expenses posted to the project (same period filter on invoice date and expense date); margin =
  profit / revenue (null, not zero, when revenue is 0); budget used = costs / cost budget; exceeding the budget is reported, not blocked.
* Periods are inclusive dates in the business time zone; default = month to date; at most 10 years.

## Defaults chosen (not owner-approved)

Time zone Asia/Kolkata and base currency INR (India-first, OA-03) until changed; terms 30 days; list paging 20 per page (limit ≤ 100, offset);
GSTIN is checked for format only; duplicate active names are refused; closed (completed/cancelled) or archived projects accept no new invoices or
expenses; archiving a client with unpaid invoices is refused; expense dates cannot be in the future; editing needs the current `version`.

## Known limitations

No GST/tax engine or compliance (DEC-35/36, PD-09, EXT-01 pending); no credit notes, refunds, recurring invoices, quotes, PDFs, e-mail sending or
reminders (no provider); no vendor bills/payables or vendor bank details; no approvals workflow; no FX or multi-currency totals; no client
statements; no attachments; no period locks; no row-level security; local audit only (PD-15); Managers' assignment scope and Viewer grants are not
modelled, so those roles have no business-finance access; list pages use limit/offset (a concurrent insert can shift a page).
