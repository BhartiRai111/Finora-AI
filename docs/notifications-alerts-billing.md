# Integrations, notifications, alerts, subscriptions, billing and workspace administration (Step 22)

**What is real today:** persisted in-app notifications with per-user read state; deterministic, explainable financial alerts evaluated on request; an integration
status registry derived from real configuration and records; subscription plans with backend-enforced entitlements and a recorded lifecycle; the data model and
verified-event processor for billing; and a workspace-level administration overview.
**What is not:** there is **no payment provider**, **no external notification delivery** (no email, SMS or push), **no accounting provider**, **no platform-wide
administration plane**, and **no commercial plan, price, trial or grace-period policy**. Nothing here is a tax invoice, receipt or compliance claim.

All of it is served by the **finance service** (it owns the data; the `platform`, `audit` and `admin-plane` services are still empty skeletons). This is a
reversible architecture choice flagged for owner confirmation. Gateway segments: `finance-notifications`, `finance-alerts`, `finance-subscription`,
`finance-billing`, `finance-integrations`, `finance-admin`. Migration: `db/finance/migrations/V8__notifications_alerts_subscriptions_billing.sql` (additive).

## 1. Notifications

* Stored in `notifications`: category (`IMPORT`, `RECONCILIATION`, `ALERT`, `SUBSCRIPTION`, `BILLING`, `INTEGRATION`, `SECURITY`, `OPERATIONS`), severity
  (`INFO`/`WARNING`/`CRITICAL`), title, short body, safe string metadata, timestamp, optional recipient user, and an **audience**.
* Notifications belong to a workspace. **Audience** decides who sees a notification that has no single recipient: `REPORTS` (roles that may read reports),
  `SETTINGS` (roles that may read company settings) or `OWNER`. A notification with a recipient is visible to that user only (and still only while their role
  allows its audience). Read state is per user (`notification_reads`), so one person reading it does not mark it read for another.
* Idempotent: `(tenant_id, source_key)` is unique, so an event that is processed twice produces one notification.
* Raised today by: import completion/failure (to the importing user), accounting sync failure/partial result and recovery, alert generation, and subscription
  changes. Payloads hold counts, identifiers and fixed text only, never amounts of individual records, file names, cell values, tax identifiers or tokens.
* Not raised today: security-account events (they originate in identity-access and need a cross-service contract that is not yet approved).
* API: `GET /finance-notifications?unread_only&category&limit&offset` (limit capped at 100), `GET /unread-count`, `GET /channels`, `POST /read-all`,
  `POST /{id}/read` (404 when the notification is not visible to the caller). Rows cannot be updated or deleted by the application database role.
* `GET /finance-notifications/channels` states plainly that `in_app` is available and external delivery is **NOT_CONFIGURED**.
* User-level notification preferences are **not implemented** (workspace-level alert settings only).

## 2. Alerts

Alerts are produced by **fixed backend rules over the workspace's own records**. They are evaluated only when someone calls `POST /finance-alerts/evaluate`
(the "Check for alerts now" button); nothing is monitored between checks and the UI says so. Evaluation requires the `alerts` entitlement. Alerts only inform:
evaluation never writes to financial records, pays, files or sends anything. An evaluation is safe to repeat.

| Type | Trigger (defaults) | Severity |
|---|---|---|
| `BUDGET_THRESHOLD` | a monthly budget reaches 80% of its limit (setting `threshold` %); at or over 100% is "over budget" | WARNING; CRITICAL when over |
| `SPENDING_CHANGE` | last complete month's expenses rose at least 30% (`threshold` %) over the prior month, both months having activity | WARNING |
| `UNUSUAL_EXPENSE` | large-amount or possible-duplicate expenses in the last 7 days (the Step 20 detector) | WARNING |
| `LOW_CASH` | cash balance below an **explicit** threshold in an explicit currency; **disabled until both are set**, nothing is assumed | CRITICAL |
| `RECEIVABLES_OVERDUE` | issued invoices past due and unpaid | WARNING |
| `RECEIVABLES_DUE_SOON` | issued invoices falling due within 7 days | INFO |
| `IMPORT_ISSUE` | failed or partially failed imports in the last 14 days | WARNING |
| `RECONCILIATION_UNRESOLVED` | unmatched bank lines older than 14 days | WARNING |
| `INTEGRATION_FAILURE` | failed or partial accounting sync runs in the last 7 days | WARNING |

* **Dedupe:** each alert has a `dedupe_key` encoding its subject and window (for example budget + month); `(tenant_id, dedupe_key)` is unique, so repeated
  evaluation creates an alert once.
* **Lifecycle:** `OPEN` → `ACKNOWLEDGED` → `RESOLVED`, with `generated_at`, `acknowledged_at/by`, `resolved_at/by` and an optional reason. Condition alerts
  resolve automatically ("The condition no longer holds") when a later evaluation no longer finds the condition. Alerts are never deleted.
* **Settings** (`GET/PUT /finance-alerts/settings`): enable/disable, threshold, days, currency. Viewing needs regional-read rights; changing needs company-settings
  update rights (Owner/Admin). Each rule also requires the same read access its data needs (for example imports, full reports); a rule the caller may not read is
  reported as skipped, not evaluated.
* Every acknowledge, resolve, settings change and evaluation is recorded in the finance audit log (local, append-only; PD-15 pending).
* **Not implemented:** an AI explanation of alerts. The assistant (Step 20) is not wired to alerts; when it is, it may only explain these backend results and only
  under the existing AI controls.
* Rules compare figures within one currency only (budgets, per-currency receivables, low cash in its explicit currency); no exchange-rate conversion is used,
  so no stale rate can feed an alert.

## 3. Integration registry

`GET /finance-integrations` lists accounting, AI, OCR, exchange rates, notification delivery and payments, each with a **state** derived from real configuration
and rows: `NOT_CONFIGURED`, `CONFIGURED` (set up but not checked), `CONNECTED` (a connection row exists and is active), `FAILED`, `UNAVAILABLE`, `DISCONNECTED`.
Today every external integration is `NOT_CONFIGURED`. Health shows the last successful operation and latest status only for accounting, from the Step 21
`accounting_sync_runs`; `GET /finance-integrations/accounting/errors` lists failed and partial runs with redacted messages (Step 21 redaction). No duplicate
tables or token store were added, and no provider is simulated. Each entry lists the owner decisions still pending. Requires company-settings read rights.

## 4. Subscriptions and entitlements

* Plans (`subscription_plans`, `plan_entitlements`) are seeded **provisional** with no price: `standard` and `limited`. Neither requires payment.
  Entitlement keys: `assistant`, `alerts`, `gst` (on/off) and `documents` (a numeric limit on stored documents).
* A workspace with no subscription row has an **implicit `standard` ACTIVE** subscription, so existing behaviour does not change.
* **Enforced on the backend** by `Entitlements`: the assistant (ask and scenario runs), GST features, document uploads (limit) and alert evaluation refuse with
  `403` and a plain message ("Your …"). The UI only reflects this.
* A subscription that is not `ACTIVE` switches off the premium features only. **Reading, exporting and entering financial data is never blocked, and financial
  data is never deleted or hidden** by a plan or subscription change.
* Lifecycle states: `ACTIVE`, `PAST_DUE`, `CANCELLED`, `EXPIRED`. Allowed transitions:
  * the **Owner** may switch between plans that do not require payment, cancel (`ACTIVE`/`PAST_DUE` → `CANCELLED`) and reactivate (`CANCELLED` → `ACTIVE`);
  * `PAST_DUE`, `EXPIRED` and any paid plan can only result from a **verified billing event**; the frontend can never mark anything paid.
  Other transitions are refused. Every transition is stored in `subscription_events` (source `OWNER` or `BILLING_EVENT`, reason, actor) and audited, and raises a
  `SUBSCRIPTION` notification. Plan changes by Admin or any other role are refused.
* No trial or grace period exists: that is a commercial decision.
* API: `GET /finance-subscription`, `/plans`, `/history`, `POST /change`, `/cancel`, `/reactivate`. Reading needs regional-read rights.

## 5. Billing (data model and verified-event processing only)

* Tables: `billing_accounts` (contact name and email only), `billing_records`, `payment_attempts`, `billing_events`. **No card or bank data exists anywhere**;
  the UI says never to enter any.
* A billing record is an **application record** (`kind: APPLICATION_RECORD`, `legal_tax_invoice: false`). It is not a tax invoice or a payment receipt, and the
  UI says so. A record is `PAID` only with a provider, a provider reference and a paid-at time (a database `CHECK`); `payment_confirmed` is true only then.
* `BillingEventProcessor` takes a provider-neutral envelope (`event_id`, `type`, `occurred_at`, reference and amount fields) and:
  * **verifies an HMAC-SHA256 signature** over `timestamp.body` with the Step 21 `WebhookVerifier` (constant time, replay window) before reading anything;
  * is **idempotent**: `billing_events (provider, event_id)` is the primary key, so a duplicate or replay returns `DUPLICATE` and changes nothing;
  * is **out-of-order safe**: an event older than the last applied state for its record is stored as `IGNORED_OUT_OF_ORDER`;
  * accepts `billing.record_created`, `billing.payment_succeeded` (PAID only with a provider reference and a matching amount), `billing.payment_failed` and
    `billing.record_voided`; invalid state changes are `IGNORED_INVALID_STATE`; bad signatures and malformed bodies are `REJECTED_SIGNATURE`/`REJECTED_INVALID`;
  * processes each event in its own transaction and records its outcome.
* **There is no HTTP webhook route and no configured secret**, so nothing receives provider events today. The gateway's existing `/v1/webhooks/billing/{provider}`
  reservation is unused. `GET /finance-billing` reports the provider as `NOT_CONFIGURED` and `payments_enabled: false`. No placeholder secret exists in
  `.env.example` because none is wired.
* API: `GET /finance-billing`, `PUT /finance-billing/contact` (Owner only; email validated), `GET /finance-billing/records` (paged).
* Billing history in the UI shows "Not confirmed", "Confirmed by {provider} (reference …)" or "Payment failed (code)" so an open record is never read as paid.

## 6. Workspace administration

`GET /finance-admin/overview` gives workspace administrators (company-settings read) a summary: plan and status, integrations by state, open alerts by severity,
the caller's unread notifications and billing status. It is **explicitly scoped to this workspace** (`scope: WORKSPACE`) and is **not platform administration**.

**Platform administration is blocked, not implemented.** It needs a platform-staff identity model separate from workspace roles (authentication, MFA, roles,
least privilege, audit, no universal bypass of workspace permissions) that has not been decided; the `admin-plane` service has no database or route. The audit
architecture (PD-15) is also unresolved and **is not claimed as resolved**: workspace actions are recorded in the finance service's own append-only audit table.

## 7. Authorization summary

| Capability | Who |
|---|---|
| Notifications (own) | roles matching the notification's audience; read state is per user |
| View alerts, settings | regional-read (reports or company settings) |
| Evaluate / acknowledge / resolve alerts | regional-read, subject to each rule's own read rights; needs the `alerts` entitlement to evaluate |
| Change alert settings | company-settings update (Owner, Admin) |
| View plan and history | regional-read |
| Change plan, cancel, reactivate, billing contact | Owner |
| View billing, integrations, workspace overview | company-settings read |
| Non-members | 404 (workspace existence is not revealed); personal and business workspaces are separate domains |

## 8. Data handling and limits

* Retention: notifications, alerts, subscription events and billing events are append-only or soft-state and are not deleted by the application. No retention
  policy is approved.
* Privacy: notification and alert payloads carry fixed text, identifiers and aggregate figures; logs never contain amounts, file names or tokens (checked by the
  end-to-end log test).
* Configuration: no new environment variables are required. Existing Step 20/21 variables are unchanged.

## 9. Pending decisions (none approved by this step)

Payment provider and its contract; commercial plans, prices, trial and grace policy; external notification delivery provider and templates; accounting
provider; platform-staff identity and the admin plane; PD-15 audit architecture; cross-service security-event notifications; user-level notification
preferences; retention periods; AI explanation of alerts.
