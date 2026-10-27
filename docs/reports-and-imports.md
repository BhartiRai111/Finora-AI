# Reports, forecasts, imports, documents and reconciliation (Step 19)

These features serve **both** personal profiles and business workspaces from the `finance` service, which owns the data they read and write.
Nothing here is a tax, accounting or regulatory compliance claim.

**Placement (an implementation choice, flagged in the decision register).** The architecture reserves `reporting`, `document` and
`ingestion-integration` services, but they are empty skeletons and there is no approved internal service-to-service contract (the README
requires one before any service calls another). Reading and writing finance data from them would mean querying the finance tables
directly (forbidden) or inventing that contract. So these features live in the finance service, behind new gateway segments
(`finance-reports`, `finance-forecast`, `finance-imports`, `finance-documents`, `finance-reconciliation`) that do not touch the reserved
prefixes. Moving them later is a refactor of routes and packages, not of the data model.

Migration: `db/finance/migrations/V5__reports_imports_documents_reconciliation.sql` (V1–V4 untouched). Reports and forecasts add no tables.
Web: tabs **Reports**, **Imports**, **Documents**, **Reconcile** in the personal finance page and in the business Finance tab.

## Access

Identity, workspace and role come only from the verified token. Personal workspaces belong to their Owner. Business roles follow the shared
permission matrix: **reports and forecasts** need REPORTS read (Owner, Accountant, Auditor in full; Admin the aggregate view); **CSV export**
needs REPORTS export (Owner, Accountant, Auditor; not Admin); **imports and reconciliation** use IMPORTS_RECONCILIATION (Owner and Accountant
read and write, Auditor reads); **documents** use DOCUMENTS (Owner and Accountant everything, Contributor uploads and sees only their own, Auditor
reads; extraction, confirmation and deletion need update/archive rights). Manager and Viewer have no access. Matrix scopes this service cannot
evaluate (ASSIGNED, IF_GRANTED, LIMITED except the Admin report view) fail closed. A foreign id answers exactly like a missing one.

## Reports

Every report is per currency (nothing is converted), computed with exact decimals, excludes drafts, cancelled invoices, voided payments, voided
expenses and voided transactions, and returns a valid empty result when there is no data. Periods are inclusive dates in the workspace time
zone (default range: the last six months to today; at most 10 years, at most 120 periods); granularity is month or week (ISO weeks, Monday start).
Weeks or months without activity are flagged `activity: false`; currencies with no data in the range are simply absent.

| Report | Workspace | Definition |
|---|---|---|
| `cash-flow` | personal | income = POSTED INCOME; expense = POSTED EXPENSE; refunds = POSTED REFUND; net_expense = expense − refunds; net = income − net_expense. Transfers and adjustments are not income or expense (same as `/summaries/cashflow`) |
| `budget-vs-actual` | personal | the month's budgets with spent/remaining/percent used/status (same as `/budgets`); parent budgets include children, so rows are not totalled |
| `net-worth` | personal | assets, liabilities, net worth at each month end (same as `/net-worth/series`) |
| `business-performance` | business | invoiced_net = Σ(line amounts − discounts) of ISSUED invoices by invoice date, excluding tax; received = payments that stand by payment date (including unallocated parts); expenses = POSTED expenses; operating_result = invoiced_net − expenses (accrual); net_cash = received − expenses (cash) |
| `receivables` | business | outstanding = invoice total − allocations of payments that stand and are dated on or before `as_of`, for ISSUED invoices dated on or before it; overdue = due date before `as_of`; aging buckets by days past due. An invoice cancelled since `as_of` is not shown |
| `receivables-by-client` | business | the same outstanding and overdue, per client (up to 500 rows) |
| `project-profitability` | business | revenue_net − costs per project in the period; margin undefined (blank) when revenue is zero |

**Comparison**: `compare=previous` (the same number of days immediately before) or `compare_from`/`compare_to`, for the two time-series reports.
Change percentage = change / |earlier value| × 100 (HALF_EVEN, 2 places), blank when the earlier value is zero. **Export**: `GET .../{report}/export`
returns CSV (UTF-8, CRLF) with a generated file name; text cells beginning with `=`, `+`, `-`, `@`, tab or CR are prefixed with an apostrophe so no
spreadsheet runs them as formulas, plain decimals are left numeric, and every export is audited (report and range only). No other file format is offered.

## Forecast

`GET /finance-forecast?metric=&lookback_months=3..12&horizon_months=1..6`. Metrics: personal `income`, `net_expense`, `net`; business
`received`, `expenses`, `net_cash`, `invoiced_net`. Method: the **mean of the months that had activity** (for that metric's own source records) in
the last N complete months (the current month is excluded), shown flat for the next months, with the median as an alternative and a range of mean ± one
population standard deviation (floored at zero for amounts that cannot be negative). Rules: at least **3 months with activity** per currency, else
`INSUFFICIENT_HISTORY` and no number; months without activity are listed as missing and left out (not treated as zero); months more than 2 standard
deviations away are flagged and kept; confidence is `LOW` when coverage is below 0.67, an outlier exists or the spread exceeds half the mean, else
`MODERATE` (never higher); arithmetic is exact decimal, rounding HALF_EVEN to the currency. It models no trend, seasonality, growth or known future
invoices/bills, says so in its assumptions, and is labelled an estimate. `ai_explanation` is `UNAVAILABLE` when no AI provider is configured (the default) and `IN_ASSISTANT` when one is (explanations live in the Assistant, see `docs/ai-assistant.md`); no AI
output is ever used for the numbers.

## Imports (CSV only)

Excel workbooks are **not supported** (parsing them needs a new dependency and a larger attack surface; the upload is refused with a clear
message). Files: UTF-8 CSV (BOM allowed), comma, semicolon or tab, quoted fields, up to 2 MB, 5,000 rows, 40 columns, 1,000 characters per cell;
binary data, invalid UTF-8, spreadsheet packages, empty files, header-only files, duplicate or blank headers and unclosed quotes are rejected.

Kinds: `TRANSACTIONS` (personal, into a chosen account: income or expense by sign or a type column), `EXPENSES` (business), `STATEMENT` (both: bank
statement lines for reconciliation; **not** financial records). Fields — TRANSACTIONS: date, amount (required), type, description, category, reference,
external_id, currency; EXPENSES: date, amount, description (required), vendor, category, reference, external_id, currency; STATEMENT: date and either amount
(signed) or debit/credit, description, reference, external_id, currency. Options: `date_format` ISO/DMY/MDY, `decimal_separator` `.` or `,`. Vendors and
categories are matched by name and must already exist (imports never create them); amounts must fit the currency's decimals; the currency of a personal
file must equal the account's.

Workflow and states: upload (`NEEDS_MAPPING`; the job stores every row, nothing else) → `PUT .../mapping` (`READY`: every row classified `VALID`, `INVALID`,
`DUPLICATE` or `POSSIBLE_DUPLICATE` with per-field messages and the original values; still nothing written) → `POST .../commit` with `confirm: true` and a
required `possible_duplicates: SKIP|IMPORT` → `COMMITTING` → `COMPLETED` (no rejected rows), `PARTIAL` (some created, some rejected), `FAILED` (none created, some
rejected) or `CANCELLED`. Records are created **only through the existing domain services**, one row per transaction, so a row the domain refuses (a future date, a
closed project, an archived vendor) is `REJECTED` with the domain's message and the others continue. `GET .../error-report` downloads a CSV of the rows that
did not make it, with their original values (formula-protected).

Duplicates: a stable `external_id` is unique per workspace and kind (a database unique index written in the same transaction as the record), so a retry or
a concurrent commit can never create it twice and such rows are always skipped as `DUPLICATE`. Rows without an id are matched only by content (date, amount,
currency, description, reference, plus their ordinal among identical rows in the file) against earlier imports and, for transactions and expenses, existing
records of the same date and amount: those are only flagged `POSSIBLE_DUPLICATE` and imported or skipped only by the user's explicit choice. Two identical
rows in one file are both legitimate. Retries and interruptions: the commit is claimed by one conditional update with a 5-minute lease, so only one worker
runs it and several instances are safe; a retry of a finished commit returns the same job; a crashed commit is resumed after the lease expires and never
repeats a committed row; `cancel` stops a running commit at its next batch (rows already created stay) or closes an abandoned one. There is no bulk rollback of
created records: void them individually.

## Documents and OCR

PDF, PNG and JPEG only, up to 10 MB, recognised by their **content** (never by file name or browser type); a PDF with scripts, launch actions, embedded files or a
damaged ending, and PNG dimensions above 20,000 px or 100 megapixels, are refused. Files are stored under generated `workspace-id/file-id` keys in a
directory outside any served path (`finora.finance.documents.storage-dir`, default under the system temp directory: **set it explicitly in any real
deployment**); the key is re-validated on every access so a tampered value cannot leave the directory. The uploaded name is only a sanitised display
label. Downloads are attachments with the sniffed type, a generated file name, `nosniff` and `no-store`. The same file is stored once per workspace.

Retention and deletion: there is no automatic retention or legal-hold engine (the reserved `retention-policies` and `legal-holds` belong to the future
document service). Deleting is a user action (needs the archive right) that removes the file immediately and keeps a metadata row and audit entry; a document
that supports a recorded expense or transaction cannot be deleted.

OCR: no engine is approved or configured. `finora.finance.ocr.provider` (`FINORA_FINANCE_OCR_PROVIDER`) is `none` (default: documents report `UNAVAILABLE`
and the user enters values by hand) or `test` (a deterministic adapter for tests and development that reads an `OCRTEXT:` marker; it is not OCR). Unknown values
stop the service at start-up. States: `UPLOADED`, `PROCESSING`, `EXTRACTED`, `FAILED`, `UNAVAILABLE`, `CONFIRMED`; one run claims a document at a time. Extracted text
is **untrusted**: only known fields survive, each checked for shape and length, control characters removed, and the text itself is never stored, logged or
acted on. Extraction never creates a record. `POST .../confirm` (idempotency key required, `confirm: true`) creates ONE expense (business) or transaction (personal)
through the domain service from the values **in the request** (what the user reviewed or typed) and links the document; the stored candidates are not used.

## Reconciliation

Statement lines (from `STATEMENT` imports) are reviewed against existing records: personal transactions of the line's account (income and refunds for money in,
expenses for money out; transfers are not offered), business payments received (money in) and business expenses (money out); voided records never qualify. A record is a
candidate only when currency, amount and direction are **exactly** equal and the date is within 7 days. Score = 50 + date (same day 30, 1 day 20, ≤3 days 10) +
reference equal 20 + name in the description 10, at most 100, with the reasons listed. The hint is `STRONG` (one candidate scoring 80 or more), `WEAK`, `AMBIGUOUS` (the
top two within 20 points) or `NONE`. **Nothing is matched automatically.** `POST .../lines/{id}/match` with `confirm: true` records one match (the same confirmation
again changes nothing); a line matches at most one record and a record at most one line (database-enforced while the match stands); `undo` needs a reason and
keeps the match as history; lines can be ignored (with a reason) and restored. The views show whether a matched record has since been voided and list records
inside the statement period that have no statement line. Documents appear as a signal through the record they support. There is no live bank feed.

## Setup and configuration

`.env.example` lists `FINORA_FINANCE_DOCUMENTS_STORAGE_DIR` and `FINORA_FINANCE_OCR_PROVIDER`. No secret or provider credential exists or is needed.

## Security summary

Authenticated, workspace-scoped and permission-checked on every route; state-changing routes need the gateway's CSRF check; uploads are bounded (gateway 25 MB,
service 2 MB / 10 MB) and validated by content; CSV output is formula-protected; extracted text and file names are treated as data; logs and audit entries hold
ids, counts and field names, never names, amounts, references, file names or document text (tested). The audit log is the local append-only table, **not**
the pending PD-15 audit architecture, and there is no row-level security.

## Known limitations and open decisions

No Excel import; no live bank feeds; no OCR engine (PD for the provider and the AI decisions AI-1..AI-5 are pending); no AI explanations; no tax or GST logic;
no revenue-recognition policy (accrual and cash are shown side by side); no scheduled or emailed reports; only CSV export; forecasts are a flat baseline; no
FX conversion (PD-09); no retention policy engine or virus scanning (best-effort content checks only); single-instance local file storage unless an object-store
implementation of `DocumentStorage` is added; an import commit runs inside the request (bounded at 5,000 rows) rather than in a separate worker; transfers are not
reconcilable; list pages use limit and offset.
