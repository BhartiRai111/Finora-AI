-- Step 22: in-app notifications, financial alerts, subscription plans/entitlements and billing records. Additive only: no existing table or column changes.
-- Placement: the platform, audit and admin-plane services are empty skeletons without an approved internal contract or staff identity, so these workspace-scoped
-- features live beside the data that triggers them (reversible, flagged in the decision register). Same tenancy conventions as V3-V7.

-- ---------------------------------------------------------------- subscription plans and entitlements
-- Plan definitions are changed by migrations (or a future, authenticated platform admin plane), never by the application role. Values here are PROVISIONAL engineering
-- defaults, not an approved commercial offer: no price exists, and no plan may require payment until a billing provider and policy are approved.
CREATE TABLE subscription_plans (
    plan_key         text        PRIMARY KEY,
    name             text        NOT NULL,
    description      text        NOT NULL,
    status           text        NOT NULL DEFAULT 'AVAILABLE',
    provisional      boolean     NOT NULL DEFAULT true,
    requires_payment boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT subscription_plans_key_chk CHECK (plan_key ~ '^[a-z][a-z0-9_]{1,30}$'),
    CONSTRAINT subscription_plans_status_chk CHECK (status IN ('AVAILABLE', 'RETIRED'))
);

CREATE TABLE plan_entitlements (
    plan_key        text    NOT NULL REFERENCES subscription_plans (plan_key),
    entitlement_key text    NOT NULL,
    enabled         boolean NOT NULL,
    limit_value     bigint,
    PRIMARY KEY (plan_key, entitlement_key),
    CONSTRAINT plan_entitlements_key_chk CHECK (entitlement_key IN ('assistant', 'alerts', 'gst', 'documents')),
    CONSTRAINT plan_entitlements_limit_chk CHECK (limit_value IS NULL OR limit_value >= 0)
);

INSERT INTO subscription_plans (plan_key, name, description, provisional, requires_payment) VALUES
    ('standard', 'Standard (provisional)', 'Everything the application offered before plans existed. Provisional: not an approved commercial plan.', true, false),
    ('limited', 'Limited (provisional)', 'A provisional reduced plan used to exercise entitlement enforcement. Not a commercial offer.', true, false);
INSERT INTO plan_entitlements (plan_key, entitlement_key, enabled, limit_value) VALUES
    ('standard', 'assistant', true, NULL), ('standard', 'alerts', true, NULL), ('standard', 'gst', true, NULL), ('standard', 'documents', true, NULL),
    ('limited', 'assistant', false, NULL), ('limited', 'alerts', true, NULL), ('limited', 'gst', false, NULL), ('limited', 'documents', true, 25);

-- A workspace without a row is on the default plan 'standard' and ACTIVE, which preserves the behaviour of every existing workspace.
CREATE TABLE workspace_subscriptions (
    tenant_id          uuid        PRIMARY KEY,
    plan_key           text        NOT NULL REFERENCES subscription_plans (plan_key),
    status             text        NOT NULL,
    started_at         timestamptz NOT NULL,
    current_period_end date,
    cancel_reason      text,
    version            integer     NOT NULL DEFAULT 0,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT workspace_subscriptions_status_chk CHECK (status IN ('ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT workspace_subscriptions_reason_chk CHECK (cancel_reason IS NULL OR char_length(cancel_reason) <= 200)
);

CREATE TABLE subscription_events (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    from_status text,
    to_status   text        NOT NULL,
    from_plan   text,
    to_plan     text        NOT NULL,
    source      text        NOT NULL,
    actor_id    uuid,
    reason      text,
    created_at  timestamptz NOT NULL,
    CONSTRAINT subscription_events_source_chk CHECK (source IN ('OWNER', 'BILLING_EVENT', 'SYSTEM'))
);
CREATE INDEX subscription_events_idx ON subscription_events (tenant_id, created_at DESC);

-- ---------------------------------------------------------------- billing (data model only: no payment provider is approved)
CREATE TABLE billing_accounts (
    tenant_id     uuid        PRIMARY KEY,
    contact_name  text,
    contact_email text,
    updated_by    uuid        NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT billing_accounts_name_chk CHECK (contact_name IS NULL OR char_length(contact_name) BETWEEN 1 AND 120),
    CONSTRAINT billing_accounts_email_chk CHECK (contact_email IS NULL OR (char_length(contact_email) <= 254 AND contact_email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'))
);

-- An application-generated billing record: NOT a legally valid tax invoice and NOT a payment receipt. It can only be PAID by a verified provider event, which must
-- carry a provider name and the provider's own reference (the database refuses PAID without them).
CREATE TABLE billing_records (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    status             text        NOT NULL DEFAULT 'OPEN',
    currency           char(3)     NOT NULL,
    amount             numeric(19,4) NOT NULL,
    description        text        NOT NULL,
    period_start       date,
    period_end         date,
    provider           text,
    provider_reference text,
    paid_at            timestamptz,
    last_event_at      timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT billing_records_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT billing_records_status_chk CHECK (status IN ('OPEN', 'PAID', 'PAYMENT_FAILED', 'VOID')),
    CONSTRAINT billing_records_amount_chk CHECK (amount >= 0),
    CONSTRAINT billing_records_currency_chk CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT billing_records_paid_chk CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),
    CONSTRAINT billing_records_paid_provider_chk CHECK (status <> 'PAID' OR (provider IS NOT NULL AND provider_reference IS NOT NULL AND char_length(provider_reference) > 0)),
    CONSTRAINT billing_records_description_chk CHECK (char_length(description) BETWEEN 1 AND 200)
);
CREATE INDEX billing_records_idx ON billing_records (tenant_id, created_at DESC, id);

CREATE TABLE payment_attempts (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    billing_record_id  uuid        NOT NULL,
    status             text        NOT NULL,
    provider           text        NOT NULL,
    provider_reference text,
    failure_code       text,
    event_at           timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT payment_attempts_record_fk FOREIGN KEY (tenant_id, billing_record_id) REFERENCES billing_records (tenant_id, id),
    CONSTRAINT payment_attempts_status_chk CHECK (status IN ('SUCCEEDED', 'FAILED'))
);
CREATE INDEX payment_attempts_idx ON payment_attempts (tenant_id, billing_record_id, event_at DESC);

CREATE TABLE billing_events (
    provider   text        NOT NULL,
    event_id   text        NOT NULL,
    tenant_id  uuid        NOT NULL,
    event_type text        NOT NULL,
    event_at   timestamptz NOT NULL,
    outcome    text        NOT NULL,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (provider, event_id)
);

-- ---------------------------------------------------------------- notifications (in-app only: no delivery provider is approved)
-- A notification belongs to a workspace. recipient_user_id, when set, restricts it to that user; otherwise it is visible to the members whose role matches
-- 'audience' (REPORTS, SETTINGS or OWNER). Read state is per user in notification_reads, so one member reading it never marks it read for another.
CREATE TABLE notifications (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    recipient_user_id uuid,
    audience          text        NOT NULL,
    category          text        NOT NULL,
    severity          text        NOT NULL,
    title             text        NOT NULL,
    body              text,
    metadata          text,
    source_key        text        NOT NULL,
    created_at        timestamptz NOT NULL,
    CONSTRAINT notifications_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT notifications_source_key UNIQUE (tenant_id, source_key),
    CONSTRAINT notifications_audience_chk CHECK (audience IN ('REPORTS', 'SETTINGS', 'OWNER')),
    CONSTRAINT notifications_category_chk CHECK (category IN ('IMPORT', 'RECONCILIATION', 'ALERT', 'SUBSCRIPTION', 'BILLING', 'INTEGRATION', 'SECURITY', 'OPERATIONS')),
    CONSTRAINT notifications_severity_chk CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT notifications_title_chk CHECK (char_length(title) BETWEEN 1 AND 160),
    CONSTRAINT notifications_body_chk CHECK (body IS NULL OR char_length(body) <= 500),
    CONSTRAINT notifications_metadata_chk CHECK (metadata IS NULL OR char_length(metadata) <= 1000)
);
CREATE INDEX notifications_list_idx ON notifications (tenant_id, created_at DESC, id);

CREATE TABLE notification_reads (
    tenant_id       uuid        NOT NULL,
    notification_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    read_at         timestamptz NOT NULL,
    PRIMARY KEY (notification_id, user_id),
    CONSTRAINT notification_reads_fk FOREIGN KEY (tenant_id, notification_id) REFERENCES notifications (tenant_id, id)
);
CREATE INDEX notification_reads_user_idx ON notification_reads (tenant_id, user_id);

-- ---------------------------------------------------------------- financial alerts
CREATE TABLE alert_settings (
    tenant_id  uuid        NOT NULL,
    alert_type text        NOT NULL,
    enabled    boolean     NOT NULL,
    threshold  numeric(19,4),
    days       integer,
    currency   char(3),
    updated_by uuid        NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, alert_type),
    CONSTRAINT alert_settings_type_chk CHECK (alert_type IN ('BUDGET_THRESHOLD', 'SPENDING_CHANGE', 'UNUSUAL_EXPENSE', 'LOW_CASH', 'RECEIVABLES_OVERDUE', 'RECEIVABLES_DUE_SOON',
        'IMPORT_ISSUE', 'RECONCILIATION_UNRESOLVED', 'INTEGRATION_FAILURE')),
    CONSTRAINT alert_settings_threshold_chk CHECK (threshold IS NULL OR threshold >= 0),
    CONSTRAINT alert_settings_days_chk CHECK (days IS NULL OR days BETWEEN 1 AND 365)
);

-- One alert per (workspace, dedupe_key): the key encodes the event or evaluation window, so repeated evaluation never repeats an alert.
CREATE TABLE alerts (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    alert_type      text        NOT NULL,
    severity        text        NOT NULL,
    dedupe_key      text        NOT NULL,
    title           text        NOT NULL,
    details         text        NOT NULL,
    window_start    date,
    window_end      date,
    status          text        NOT NULL DEFAULT 'OPEN',
    generated_at    timestamptz NOT NULL,
    acknowledged_by uuid,
    acknowledged_at timestamptz,
    resolved_at     timestamptz,
    resolved_by     uuid,
    resolved_reason text,
    CONSTRAINT alerts_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT alerts_dedupe_key UNIQUE (tenant_id, dedupe_key),
    CONSTRAINT alerts_severity_chk CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT alerts_status_chk CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT alerts_resolved_chk CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT alerts_ack_chk CHECK (status = 'OPEN' OR acknowledged_at IS NOT NULL OR status = 'RESOLVED')
);
CREATE INDEX alerts_list_idx ON alerts (tenant_id, generated_at DESC, id);
CREATE INDEX alerts_status_idx ON alerts (tenant_id, status, alert_type);

REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON subscription_plans, plan_entitlements FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON notifications, subscription_events, billing_events, payment_attempts FROM ${app_role};
-- the only thing that may change on a billing event is the outcome of processing it
GRANT UPDATE (outcome) ON billing_events TO ${app_role};
REVOKE DELETE, TRUNCATE ON alerts, billing_records FROM ${app_role};
