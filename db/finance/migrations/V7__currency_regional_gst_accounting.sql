-- Step 21: multi-currency (user-supplied exchange rates), regional settings, GST-aware details and accounting-integration foundations.
-- Backward compatible: only new nullable/defaulted columns and new tables; no existing row or constraint changes meaning. Original amounts and
-- currencies on transactions, invoices, payments and expenses are never touched: a conversion is always derived at report time from the
-- stored original amount and a stored rate, and is never written back. No tax rate table exists: a rate is whatever the user typed on an
-- invoice line (V4). No exchange-rate provider and no accounting provider is approved: rates are entered by an authorised person and every
-- row says so ('USER_SUPPLIED'). Same tenancy conventions as V3-V6 (tenant_id on every row, composite foreign keys, no row-level security yet).

-- ---------------------------------------------------------------- regional settings (display preferences only)
ALTER TABLE finance_settings
    ADD COLUMN display_currency        char(3),
    ADD COLUMN locale                  text,
    ADD COLUMN date_format             text,
    ADD COLUMN fiscal_year_start_month integer NOT NULL DEFAULT 1,
    ADD COLUMN reporting_period        text    NOT NULL DEFAULT 'MONTH',
    ADD COLUMN rate_stale_after_days   integer NOT NULL DEFAULT 7,
    ADD CONSTRAINT finance_settings_display_currency_chk CHECK (display_currency IS NULL OR display_currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT finance_settings_fiscal_chk CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    ADD CONSTRAINT finance_settings_period_chk CHECK (reporting_period IN ('MONTH', 'QUARTER', 'FISCAL_YEAR')),
    ADD CONSTRAINT finance_settings_date_format_chk CHECK (date_format IS NULL OR date_format IN ('ISO', 'DMY', 'MDY')),
    ADD CONSTRAINT finance_settings_stale_chk CHECK (rate_stale_after_days BETWEEN 0 AND 365);

ALTER TABLE business_settings
    ADD COLUMN display_currency        char(3),
    ADD COLUMN locale                  text,
    ADD COLUMN date_format             text,
    ADD COLUMN fiscal_year_start_month integer NOT NULL DEFAULT 1,
    ADD COLUMN reporting_period        text    NOT NULL DEFAULT 'MONTH',
    ADD COLUMN rate_stale_after_days   integer NOT NULL DEFAULT 7,
    ADD COLUMN gst_registered          boolean NOT NULL DEFAULT false,
    ADD COLUMN gstin                   text,
    ADD CONSTRAINT business_settings_display_currency_chk CHECK (display_currency IS NULL OR display_currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT business_settings_fiscal_chk CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
    ADD CONSTRAINT business_settings_period_chk CHECK (reporting_period IN ('MONTH', 'QUARTER', 'FISCAL_YEAR')),
    ADD CONSTRAINT business_settings_date_format_chk CHECK (date_format IS NULL OR date_format IN ('ISO', 'DMY', 'MDY')),
    ADD CONSTRAINT business_settings_stale_chk CHECK (rate_stale_after_days BETWEEN 0 AND 365),
    ADD CONSTRAINT business_settings_gstin_chk CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
    ADD CONSTRAINT business_settings_gst_registered_chk CHECK (NOT gst_registered OR gstin IS NOT NULL);

-- ---------------------------------------------------------------- exchange rates
-- One row = "1 base_currency = rate quote_currency on rate_date", entered by a person. Rows are immutable; a wrong rate is voided (with a
-- reason) and a new one entered. Only direct pairs are stored and used: inverse and cross rates are never derived.
CREATE TABLE exchange_rates (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    base_currency   char(3)     NOT NULL,
    quote_currency  char(3)     NOT NULL,
    rate            numeric(20,10) NOT NULL,
    rate_date       date        NOT NULL,
    source          text        NOT NULL DEFAULT 'USER_SUPPLIED',
    source_note     text,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    void_reason     text,
    voided_at       timestamptz,
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT exchange_rates_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT exchange_rates_pair_chk CHECK (base_currency <> quote_currency AND base_currency ~ '^[A-Z]{3}$' AND quote_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT exchange_rates_rate_chk CHECK (rate > 0 AND rate <= 1000000000),
    CONSTRAINT exchange_rates_source_chk CHECK (source IN ('USER_SUPPLIED')),
    CONSTRAINT exchange_rates_status_chk CHECK (status IN ('ACTIVE', 'VOIDED')),
    CONSTRAINT exchange_rates_void_chk CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)),
    CONSTRAINT exchange_rates_note_chk CHECK (source_note IS NULL OR char_length(source_note) <= 200)
);
CREATE UNIQUE INDEX exchange_rates_active_idx ON exchange_rates (tenant_id, base_currency, quote_currency, rate_date) WHERE status = 'ACTIVE';
CREATE INDEX exchange_rates_lookup_idx ON exchange_rates (tenant_id, base_currency, quote_currency, rate_date DESC) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------- GST details (a snapshot beside the invoice or expense, never inside it)
CREATE TABLE invoice_gst_details (
    tenant_id                 uuid        NOT NULL,
    invoice_id                uuid        NOT NULL,
    supplier_gstin            text        NOT NULL,
    supplier_state_code       char(2)     NOT NULL,
    recipient_gstin           text,
    recipient_state_code      char(2),
    place_of_supply_state     char(2)     NOT NULL,
    supply_type               text        NOT NULL,
    invoice_total             numeric(19,4) NOT NULL,
    invoice_tax_total         numeric(19,4) NOT NULL,
    rule_version              text        NOT NULL,
    calculated_at             timestamptz NOT NULL,
    calculated_by             uuid        NOT NULL,
    PRIMARY KEY (tenant_id, invoice_id),
    CONSTRAINT invoice_gst_details_invoice_fk FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id),
    CONSTRAINT invoice_gst_details_supply_chk CHECK (supply_type IN ('INTRA_STATE', 'INTER_STATE'))
);

CREATE TABLE invoice_gst_breakdown (
    tenant_id      uuid        NOT NULL,
    invoice_id     uuid        NOT NULL,
    tax_percent    numeric(5,2) NOT NULL,
    taxable_amount numeric(19,4) NOT NULL,
    cgst           numeric(19,4) NOT NULL,
    sgst           numeric(19,4) NOT NULL,
    igst           numeric(19,4) NOT NULL,
    total_tax      numeric(19,4) NOT NULL,
    PRIMARY KEY (tenant_id, invoice_id, tax_percent),
    CONSTRAINT invoice_gst_breakdown_details_fk FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice_gst_details (tenant_id, invoice_id),
    CONSTRAINT invoice_gst_breakdown_amounts_chk CHECK (taxable_amount >= 0 AND cgst >= 0 AND sgst >= 0 AND igst >= 0 AND total_tax = cgst + sgst + igst)
);

CREATE TABLE expense_gst_details (
    tenant_id             uuid        NOT NULL,
    expense_id            uuid        NOT NULL,
    vendor_gstin          text,
    vendor_state_code     char(2),
    place_of_supply_state char(2)     NOT NULL,
    supply_type           text,
    taxable_amount        numeric(19,4) NOT NULL,
    cgst                  numeric(19,4) NOT NULL,
    sgst                  numeric(19,4) NOT NULL,
    igst                  numeric(19,4) NOT NULL,
    total_tax             numeric(19,4) NOT NULL,
    expense_amount        numeric(19,4) NOT NULL,
    rule_version          text        NOT NULL,
    recorded_at           timestamptz NOT NULL,
    recorded_by           uuid        NOT NULL,
    PRIMARY KEY (tenant_id, expense_id),
    CONSTRAINT expense_gst_details_expense_fk FOREIGN KEY (tenant_id, expense_id) REFERENCES business_expenses (tenant_id, id),
    CONSTRAINT expense_gst_details_supply_chk CHECK (supply_type IS NULL OR supply_type IN ('INTRA_STATE', 'INTER_STATE')),
    CONSTRAINT expense_gst_details_amounts_chk CHECK (taxable_amount >= 0 AND cgst >= 0 AND sgst >= 0 AND igst >= 0 AND total_tax = cgst + sgst + igst
        AND taxable_amount + total_tax = expense_amount)
);

-- ---------------------------------------------------------------- accounting integration foundations (no provider is approved)
-- Tokens are stored only as AES-GCM ciphertext bound to the workspace and provider (see TokenVault); nothing here is ever returned by an API.
CREATE TABLE accounting_connections (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    provider         text        NOT NULL,
    status           text        NOT NULL,
    access_token_enc text,
    refresh_token_enc text,
    token_expires_at timestamptz,
    scopes           text,
    created_by       uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT accounting_connections_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT accounting_connections_status_chk CHECK (status IN ('PENDING_CONSENT', 'CONNECTED', 'EXPIRED', 'REVOKED', 'ERROR'))
);
CREATE UNIQUE INDEX accounting_connections_provider_idx ON accounting_connections (tenant_id, provider) WHERE status <> 'REVOKED';

CREATE TABLE accounting_webhook_events (
    provider     text        NOT NULL,
    event_id     text        NOT NULL,
    tenant_id    uuid        NOT NULL,
    payload_hash text        NOT NULL,
    received_at  timestamptz NOT NULL,
    PRIMARY KEY (provider, event_id)
);

CREATE TABLE accounting_sync_runs (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    provider      text        NOT NULL,
    direction     text        NOT NULL,
    status        text        NOT NULL,
    attempts      integer     NOT NULL DEFAULT 1,
    records_ok    integer     NOT NULL DEFAULT 0,
    records_failed integer    NOT NULL DEFAULT 0,
    error_code    text,
    error_message text,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz,
    CONSTRAINT accounting_sync_runs_direction_chk CHECK (direction IN ('EXPORT', 'IMPORT')),
    CONSTRAINT accounting_sync_runs_status_chk CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT accounting_sync_runs_error_chk CHECK (error_message IS NULL OR char_length(error_message) <= 300)
);
CREATE INDEX accounting_sync_runs_idx ON accounting_sync_runs (tenant_id, started_at DESC);

REVOKE UPDATE, DELETE, TRUNCATE ON exchange_rates FROM ${app_role};
GRANT UPDATE (status, void_reason, voided_at) ON exchange_rates TO ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON accounting_webhook_events FROM ${app_role};
