# Multi-currency, regional settings, India GST and accounting integrations (Step 21)

**What is real today:** user-supplied exchange rates with traceable, per-period report conversion; regional display settings and fiscal periods; GST-aware
details and a preparation worksheet for a narrow, documented set of supply types; and the safe foundations of an accounting integration.
**What is not:** there is **no approved exchange-rate source**, **no approved accounting provider**, no GST rate table, and no filing of anything.
This is not GST-compliance software; nothing here has been validated by a qualified professional.

## 1. Currency model

* Every record keeps its **original amount and currency** (`NUMERIC(19,4)`, ISO 4217 code). Nothing in this step writes to those columns. Conversion is derived
  at report time and never stored, so there is no stored converted amount that could drift or be converted twice.
* Currency metadata is the existing explicit list in `Currencies` (INR, USD, EUR, GBP, AED, SGD, AUD, CAD, CHF, NZD, HKD, SAR, ZAR, JPY, KRW, KWD, BHD, OMR) with
  ISO 4217 minor-unit exponents (JPY/KRW 0, KWD/BHD/OMR 3, others 2). Unlisted currencies are refused, not guessed.
* A conversion is `original x rate`, rounded **once**, `HALF_EVEN`, to the reporting currency's minor unit, always from the original amount (never from an
  earlier conversion).
* Currencies are never summed without a valid rate. If any period of a currency has no usable rate, that currency is `UNAVAILABLE` (with the reason) and no
  combined total is shown.

### Exchange rates (`finance-rates`)

| Endpoint | Purpose |
|---|---|
| `GET /v1/spaces/{space}/finance-rates/status` | states that no automatic source is configured; whether the caller may enter rates |
| `GET /finance-rates?base&quote&include_voided&limit&offset` | list entered rates |
| `POST /finance-rates` `{base_currency, quote_currency, rate, rate_date, source_note?}` | enter a rate (source is always `USER_SUPPLIED`) |
| `POST /finance-rates/{id}/void` `{reason}` | void a wrong rate (rows are never edited or deleted) |
| `GET /finance-rates/lookup?base&quote&date` | the rate that applies on a date: `APPLIED`, `STALE`, `MISSING` or `SAME` |

Rules: direct pairs only (inverse and cross rates are never derived); rate `> 0`, at most 10 decimals, at most 1,000,000,000; `rate_date` not in the future; one
active rate per pair and date; a conversion uses the latest active rate dated **on or before** the date (a later rate is never used for an earlier date); a rate
older than the workspace's `rate_stale_after_days` (default 7) is `STALE` and is applied **only** if the caller passes `allow_stale=true`, in which case every
use is flagged. Writing rates needs full company-settings update rights (Owner, Admin); reading needs reports or company-settings read.

### Reports in a reporting currency

`GET /finance-reports/{name}/converted?reporting_currency=XXX[&allow_stale=true]` plus the usual report parameters returns `{report, conversion}`: the original
report **unchanged**, and a conversion block. Each period is converted at the rate in force on the period's last day; for each period the rate, its date, its age and
its source are listed. Converted totals of period reports (cash flow, business performance) are the **sum of the converted periods**; other reports convert their
totals once at the rate for the report's end date. Only money columns are converted (counts and percentages are left alone). Covered: cash flow, budget versus
actual, net worth, business performance, receivables, receivables by client, project profitability. The CSV export accepts `reporting_currency` (and `allow_stale`)
and appends a separate, clearly headed converted block (`converted_to, source_currency, period, status, rate, rate_date, rate_source, rate_age_days, ...`);
without the parameter the export is exactly as before. All cells are formula-protected.
**Forecasts, what-if scenarios and comparisons are never converted**: each currency is forecast and compared on its own.

## 2. Regional settings (`finance-regional`)

`GET/PUT /v1/spaces/{space}/finance-regional` and `GET .../finance-regional/periods`. Stored in the existing `finance_settings` (personal) and
`business_settings` (business) rows, so a workspace's settings can never affect another's. Fields:

| Field | Values | Default (preserves earlier behaviour) |
|---|---|---|
| `timezone` | IANA name | `UTC` personal, `Asia/Kolkata` business |
| `display_currency` | supported currency or none | none |
| `locale` | `en-IN en-US en-GB en-AU en-CA en-SG hi-IN de-DE fr-FR es-ES ja-JP pt-BR` or none | none |
| `date_format` | `ISO`, `DMY`, `MDY` or none | none |
| `fiscal_year_start_month` | 1..12 | 1 (calendar year) |
| `reporting_period` | `MONTH`, `QUARTER`, `FISCAL_YEAR` | `MONTH` |
| `rate_stale_after_days` | 0..365 | 7 |

These are **display and boundary** preferences. Stored amounts, currencies and dates are never changed; changing the time zone only changes what "today" and a period
boundary mean from then on (records keep their calendar dates; tested). The fiscal-year start is a documented, reversible default of January; an Indian business
typically sets April, but that is the owner's choice and nothing is assumed. `periods` returns this/last fiscal year and quarter for today. The assistant understands
"this/last fiscal (or financial) year/quarter". Change rights: personal Owner; business Owner/Admin. **User-level display preferences are not implemented**: finance
settings are workspace-level in the current authorization model (future work). The web app applies locale and date format to the new currency views; the older
screens keep their existing formatting.

## 3. India GST (`finance-gst`)

**Supported (verified structure only):**

* GSTIN format and check character (Luhn mod-36, cross-checked against the published sample numbers `27AAPFU0939F1ZV` and `29AAGCB7383J1Z4`) and the two-digit state/UT
  code list (from public GST code lists; to be validated by a qualified professional). A valid check character does not prove the GSTIN is registered or active.
* **Intra-state** supply (supplier state = place of supply): CGST + SGST, each half of the line tax; **inter-state**: IGST. The split uses the tax already computed
  on the invoice lines (the percentage the user typed). CGST is half rounded down to the paisa, SGST is the remainder, so CGST + SGST = tax exactly (this product's
  rule, not an official one).
* INR only. Place of supply is **required and never assumed**. The workspace must be marked GST-registered with a valid GSTIN first.
* Per-invoice details are a **snapshot** beside the invoice (`invoice_gst_details`, `invoice_gst_breakdown`): supplier/recipient GSTIN and state, place of supply, supply
  type, tax per rate, rule version. A draft's snapshot can be recalculated; an **issued invoice's snapshot is frozen** (409 `INVOICE_IMMUTABLE`); a later change to the
  client, the workspace GSTIN or settings never rewrites it. If a draft changes after its snapshot, the snapshot is flagged stale and excluded from summaries.
* Per-bill details for expenses (`expense_gst_details`) are entered from the vendor bill and validated: taxable + CGST + SGST + IGST = expense amount; intra-state needs
  equal CGST and SGST and no IGST; inter-state needs IGST only; tax with no valid vendor GSTIN is refused.

**Endpoints:** `GET/PUT finance-gst/registration`; `PUT finance-gst/invoices/{id}[?preview=true]` `{place_of_supply}`, `GET finance-gst/invoices/{id}`;
`PUT/GET finance-gst/expenses/{id}`; `GET finance-gst/summary?from&to`; `GET finance-gst/transactions?from&to`; `GET finance-gst/export?from&to`;
`GET finance-gst/reference`. Permissions follow the matrix: details need invoice/transaction update rights; summary needs reports read (Admin sees aggregates only);
transaction rows need full reports read; export needs the export right; registration change needs company-settings update. The summary shows the GSTIN masked.

**Not supported (refused or left out, never guessed):** UTGST and union territories without a legislature (intra-state supply in 04, 25, 26, 31, 35, 38, 97, 99 is
refused), exports and SEZ, reverse charge, composition scheme, input-tax-credit eligibility, credit/debit notes, e-invoicing and e-way bills, HSN/SAC and rate
tables (the rate is what was typed), exemption/nil-rated classification (a zero-tax invoice gets a warning, not a classification), non-INR documents, time-of-supply
rules (sales use the invoice date, purchases the expense date), tax returns in any official format, and any submission.

**Summary and worksheet:** sales and purchases per period with CGST/SGST/IGST totals, month breakdown, counts of excluded records with reasons
(`EXCLUDED_NO_GST_DETAILS`, `EXCLUDED_STALE_SNAPSHOT`, `EXCLUDED_NON_INR`), and a reconciliation block that must tie out (`sales_reconciled`,
`purchases_reconciled`). The CSV worksheet (`FINORA_GST_PREPARATION_WORKSHEET_V1`, one row per invoice/rate or bill, flagged rows included with their status) is
**Finora's own format, not an official GST return**, and is formula-injection protected. Nothing is ever filed, submitted or declared; no net-payable or credit figure
is computed.

## 4. Accounting integrations (`finance-accounting`)

**No accounting provider is approved.** `finora.finance.accounting.provider` accepts only `none`; any other value stops the service at startup. `GET finance-accounting`
reports `NOT_CONFIGURED`, no capabilities, whether the token vault key is configured, existing connections (status only, never tokens) and the pending decisions;
`GET finance-accounting/sync-runs` lists the sync ledger. There is **no connect, OAuth callback, webhook or sync route**.

Foundations that exist and are tested without credentials: `TokenVault` (AES-256-GCM, workspace and provider bound into the ciphertext, key from
`FINORA_FINANCE_ACCOUNTING_TOKEN_KEY_PATH`, never printed), `WebhookVerifier` (HMAC-SHA256 over `timestamp.body`, constant-time compare, 5-minute replay window),
a duplicate-event store (`accounting_webhook_events`, atomic first-delivery), a sync ledger (`accounting_sync_runs`) and error redaction (tokens and secrets never stored
or shown). Before a provider can be added the owner must decide: the provider and its API contract, scopes and rate limits; OAuth client registration per environment;
production key management for the token vault; the provider's webhook scheme; and what may be exported or imported and the conflict policy.

## 5. AI assistant

Conversions, GST figures and fiscal periods are computed by the backend; the model supplies none. `period_summary` accepts a reporting currency ("...converted to
INR") and returns the converted sections (kind `CONVERTED`) with every rate, or an explicit "unavailable" section; `gst_summary` (business) returns the GST summary
with its limits. Questions about filing returns or syncing with accounting software are refused (`UNSUPPORTED`) and logged; the assistant states that nothing was
filed or synchronised. Rate and conversion text is included in the facts a provider could see, so an invented rate or total fails the numeric guard.

## 6. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `FINORA_FINANCE_ACCOUNTING_PROVIDER` | `none` | only `none` is accepted |
| `FINORA_FINANCE_ACCOUNTING_TOKEN_KEY_PATH` | unset | file with a base64 256-bit key for the token vault; without it the vault is unavailable |

No exchange-rate or accounting credentials exist or are needed today.

## 7. Migration `V7__currency_regional_gst_accounting.sql`

Additive: new columns with defaults on `finance_settings` and `business_settings` (display currency, locale, date format, fiscal start, reporting period, stale days, GST
registration), and new tables `exchange_rates`, `invoice_gst_details`, `invoice_gst_breakdown`, `expense_gst_details`, `accounting_connections`,
`accounting_webhook_events`, `accounting_sync_runs`. Existing rows keep their meaning. Exchange rates can only be voided (column-level `UPDATE` grant), webhook events are
append-only.

## 8. Known limitations and audit

* Rates are manual; there is no rate history import, no automatic refresh and no inverse/cross derivation.
* Per-period conversion at the period's last-day rate is a documented simplification of per-transaction conversion.
* Audit events (`EXCHANGE_RATE_ADDED/VOIDED`, `REGIONAL_SETTINGS_UPDATED`, `GST_*`, `INVOICE_GST_RECORDED`, `EXPENSE_GST_RECORDED`) go to the existing local
  append-only table; the approved audit architecture (PD-15) is still pending and no tamper-evidence claim is made. GSTINs are never written to audit details.
* Existing invoice, expense and report screens were not redesigned; only the new tabs use the workspace locale and date format.

## 9. Pending decisions (not approved by this step)

Exchange-rate source and its terms; rounding and conversion policy (PD-09: the per-period half-even rule here is a default); GST scope beyond the listed cases and
validation by a qualified professional (DEC-35/36, EXT-01); GST filing format and any submission workflow; accounting provider(s) and scopes; token-vault key
management; user-level display preferences; the default fiscal-year start for India-based businesses.
