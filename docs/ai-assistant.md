# AI financial intelligence (Step 20)

**Status: the assistant is live and fully deterministic. No AI provider is approved, configured or tested.** Provider and model selection (decision
DL-01, AI-1..AI-5) and the data-processing terms are still **pending owner approval**. Nothing in this document claims a working AI integration. What
exists is a provider-neutral interface, a truthful "not configured" default, a deterministic test adapter used only by tests, and every safeguard a real
provider would sit behind.

## What a person gets today

Ask a question in the **Assistant** tab (personal and business workspaces). Every figure in the answer is computed by the finance service from the
workspace's own records, with exact decimals and the same definitions as the Step 17/18/19 reports. With no provider configured the answer says so
("AI explanations: not configured") and is complete without one.

| Question type | Tool | Source of the numbers |
|---|---|---|
| "How much did I spend last month?" (spend, income, cash flow, invoiced, received) | `period_summary` | `ReportService` (`cash-flow` / `business-performance`) |
| "What are my top spending categories this year?" | `top_categories` | category breakdown query; the rows add up to the reported net spending |
| "Why did my expenses rise compared with the previous period?" | `expense_change` | report comparison + per-category drivers over an equivalent earlier window |
| "How did my cash flow change over the last 3 months?" | `cash_flow_trend` | monthly report series |
| "Show unusual transactions last month" | `anomalies` | deterministic rules (below) |
| "Forecast my next 3 months" | `forecast` | the Step 19 `ForecastService` unchanged |
| "What if my expenses increase by 10%?" | `what_if` | `ForecastService` baseline + explicit user percentages |
| "Which invoices are overdue?" (business) | `overdue_invoices` | `receivables` and `receivables-by-client` reports |
| "Which projects have the lowest profit?" (business) | `project_profitability` | `project-profitability` report |

Endpoints (all under `/v1/spaces/{space}/`, served by the finance service; gateway segment `finance-assistant`):
`GET finance-assistant` (state, supported questions, limits), `POST finance-assistant/ask` `{question}`, `POST finance-assistant/what-if`,
`GET finance-assistant/anomalies?from&to&currency`, `GET finance-assistant/expense-change?from&to&currency`.

### Answer contract

`status` is `ANSWERED`, `NO_DATA`, `NEEDS_CLARIFICATION` or `UNSUPPORTED`. Facts are in `sections`, each with a `kind`: **VERIFIED** (arithmetic on
records), **ESTIMATE** (the Step 19 baseline), **HYPOTHETICAL** (a what-if on the user's stated assumptions) or **OBSERVATION** (a *potential* anomaly).
Each answer carries `period` (and `comparison_*` windows), `assumptions`, `limitations`, `sources` (report names, date ranges, record ids) and
`notices`. Each currency is its own section; nothing is ever converted or summed across currencies. Empty data gives `NO_DATA`
("Nothing was estimated"), never a zero-filled answer. The question's period is resolved by fixed rules in the workspace time zone and echoed; with no
period the last complete month is used and the answer says so. Future periods and unplaceable phrases are asked back, not guessed.

### Spending explanations

`expense_change` compares equivalent windows and states them: the same whole months before, the same days of the previous month for a month-to-date
period, otherwise the same number of days before. It reports the total change (amount and percent), the categories that moved most in the direction of
the change with their share of it, and the largest individual records (full-detail roles only). It always states that this shows *what* changed, not
*why*; flags small samples (fewer than 5 records in a window) and unequal window lengths; and says so when spending fell although the question assumed
a rise. No merchants, events or causes are inferred.

### Anomalies (deterministic, explainable, limited)

Each finding names its rule and the figures behind it, and is worded "Potential anomaly"; it says nothing about error or fraud.

- `LARGE_AMOUNT`: a record above the baseline average + 3 population standard deviations **and** at least twice the median, where the baseline is at least
  6 earlier records (up to 6 months back) in the same category and currency.
- `CATEGORY_SPIKE`: for periods of at least 7 days, a category total above the mean + 2 standard deviations **and** at least 1.5 times the mean of the 3
  previous equal-length periods, all three having spending.
- `POSSIBLE_DUPLICATE`: same payee/vendor, amount and currency within 3 days.

Limitations: only expense records are examined; sparse history produces no finding and the answer says why; one-off purchases and seasonality look like
anomalies; records without a payee are not checked for duplicates; at most 20 findings are shown; the baseline is capped at 20,000 records. The
assistant never modifies, hides or reports a transaction.

### Forecasts and what-if

The forecast is the Step 19 flat baseline (mean of months with activity, mean ± 1 standard deviation, minimum 3 months, no trend or seasonality) and is
labelled an estimate with its assumptions and warnings. The what-if applies the user's percentages (−100 to 1000, at most 2 decimals) to the baseline
income/revenue and spending per month with `HALF_EVEN` rounding to the currency's minor unit and reports baseline vs scenario net. It is labelled
hypothetical, is never saved, and changes no budget, invoice, payment, subscription or record. A currency without an estimate for both sides gets no scenario.

## Architecture

```
question ─▶ QuestionRouter (fixed rules) ─▶ ToolRegistry (allowlist, argument validation) ─▶ AssistantTools ─▶ ReportService / ForecastService / read-only queries
                                │ unknown + provider configured                                      │
                                └─▶ AiGateway.plan (a proposal only, validated like any call)         ▼
                                                                         verified sections ─▶ AiGateway.explain (optional, validated, labelled) ─▶ Answer
```

* **Provider interface** (`AiProvider`): `name()`, `configured()`, `external()`, `complete(Request)`; it gets no tools, no database access and no identity.
  Selected by `finora.finance.ai.provider` = `none` (default) or `test` (deterministic adapter for development and tests; **not** a language model).
  Any other value stops the service at startup ("Real providers are not approved yet"). Adding a real provider later means one class implementing the
  interface plus the owner's approvals; the gateway, limits, validation and tests already surround it.
* **Placement:** the assistant lives in the finance service (the owner of the data), like Step 18/19 features, behind the new gateway segment
  `finance-assistant`. The reserved `ai` prefix of the `intelligence` service is unchanged; moving the assistant there needs an approved internal
  contract (decision register, Step 20).
* **Read-only tools** (allowlist of ten: the nine in the table plus `gst_summary`, see `docs/multi-currency-regional-gst.md`). Rejected before running: unknown names, tools of the other kind of workspace, unknown or
  identity-like arguments (`workspace_id`, `tenant_id`, `user_id`, `sql`, `url`, ...), non-scalar or over-long values. Workspace, user and role come only
  from the verified internal token; roles are enforced by the same permission matrix as reports (personal: Owner; business: reports read, with the Admin
  aggregate-only view; record-level tools need full reports access).

## Configuration

| Variable (property) | Default | Meaning |
|---|---|---|
| `FINORA_FINANCE_AI_PROVIDER` (`finora.finance.ai.provider`) | `none` | `none` or `test`; nothing else is accepted |
| `FINORA_FINANCE_AI_TIMEOUT_MS` | `8000` | per provider call, minimum 100 |
| `FINORA_FINANCE_AI_MAX_RETRIES` | `1` | retries of timeouts/unavailability only, clamped to 0..3; auth, rate-limit, malformed and service errors are never retried |
| `FINORA_FINANCE_AI_QUESTIONS_PER_HOUR` | `60` | per user; over the limit is HTTP 429 `RATE_LIMITED` |
| `FINORA_FINANCE_AI_PROVIDER_CALLS_PER_DAY` | `200` | per workspace; over the cap the answer is still given, the AI part reports `LIMIT_REACHED` |
| `FINORA_FINANCE_AI_MAX_OUTPUT_CHARS` | `1500` | provider answers longer than this are discarded (200..4000) |
| `FINORA_FINANCE_AI_EXTERNAL_DATA_APPROVED` | `false` | a provider that sends data outside this deployment is never called unless this is true; no such provider exists yet |

Provider credentials will be environment variables read by a future adapter and are never stored in source, the frontend or logs; none exist today.

## Honest states

`ai.status` is one of `NOT_CONFIGURED` (no provider chosen), `CONFIGURED` (state endpoint only, no request made), `EXECUTED` (a provider answered **and**
the answer passed validation), `UNAVAILABLE` (timeout or unreachable), `FAILED` (rejected credentials, rate limit, malformed or rejected output, daily cap,
service error), `SKIPPED` (nothing to explain) and `BLOCKED` (the provider would send data out and the terms are not approved). A failure never changes
or removes the figures.

## Privacy, prompt injection and access control

* **What a provider would see:** aggregates only (tables of category/project/client rows as `item N`, amounts, dates, currencies, fixed assumption
  text). Never raw descriptions, payees, vendors, notes, imported or OCR text, document contents, identifiers, tokens or secrets. Sections that carry
  record text (anomalies, individual records) are never sent. If provider or data-processing terms are not approved nothing real is sent.
* **Untrusted text:** transaction, category, vendor, client, project, import and OCR text is data. It is cleaned for display, replaced by placeholders in
  prompts, and instruction-like text is counted and logged as `AI_INJECTION_SUSPECTED` (no content) while being shown as data.
* **Model output is untrusted:** it must be one JSON object with one short string; no links, markup or control characters; every number must appear in
  the facts it was given (exact string match after removing commas); only `item N` placeholders it was given. Otherwise it is discarded
  (`OUTPUT_REJECTED`). A model plan is validated like any call; unsafe plans are rejected and logged as `AI_TOOL_REJECTED`.
* **Refused outright (no tool, no provider):** requests for credentials, session tokens, system prompts, other users' or workspaces' data, shell/SQL, and
  anything that writes (delete, pay, cancel, file, transfer, edit). Logged as `AI_REQUEST_REFUSED` without the question.
* **Logs and ledgers:** `finance_ai_usage` (migration V6, append-only for the app role) holds who/kind/intent/provider/outcome for rate limits; audit
  events hold tool, status and AI state. Neither holds question text, prompts, answers or figures; logs hold the provider name and failure kind only.
* **Read-only:** the assistant writes nothing but the ledger and audit rows; a test asserts row counts of every finance table are unchanged.

## Pending decisions (not approved by this step)

AI provider and model (DL-01, AI-1..AI-5), data-processing terms and residency for AI requests (OA-23 residency choice R-A is recorded, but the
provider-specific terms are not), whether a real adapter may receive real customer aggregates, the retention of any provider-side logs, and the cost
ceiling per workspace. Until approved, live provider behaviour is untested and no claim about it is made.
