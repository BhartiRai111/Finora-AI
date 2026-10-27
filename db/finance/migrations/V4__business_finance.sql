-- Business finance (Step 18). Owned by the finance service only (PD-01 Option A: invoice issue, numbering, payments and audit
-- stay in one database so they are atomic). Same conventions as V3: NUMERIC(19,4) money, ISO currency per amount, composite
-- (tenant_id, id) foreign keys so a row can only reference rows of its own business workspace, no hard deletes.
-- No tax rules are stored: GST/tax policy (DEC-35/36, PD-09, EXT-01) is not approved; a line carries only the percentage the
-- user typed and the service does the arithmetic, with no compliance claim. Row-level security is not applied yet.

CREATE TABLE business_settings (
    tenant_id           uuid        PRIMARY KEY,
    timezone            text        NOT NULL DEFAULT 'Asia/Kolkata',
    base_currency       char(3)     NOT NULL DEFAULT 'INR',
    company_name        text,
    invoice_prefix      text        NOT NULL DEFAULT 'INV-',
    invoice_padding     integer     NOT NULL DEFAULT 4,
    invoice_next_number bigint      NOT NULL DEFAULT 1,
    default_terms_days  integer     NOT NULL DEFAULT 30,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT business_settings_prefix_chk CHECK (invoice_prefix ~ '^[A-Za-z0-9/_-]{0,12}$'),
    CONSTRAINT business_settings_padding_chk CHECK (invoice_padding BETWEEN 1 AND 10),
    CONSTRAINT business_settings_next_chk CHECK (invoice_next_number >= 1),
    CONSTRAINT business_settings_terms_chk CHECK (default_terms_days BETWEEN 0 AND 365),
    CONSTRAINT business_settings_currency_chk CHECK (base_currency ~ '^[A-Z]{3}$')
);

CREATE TABLE clients (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    name               text        NOT NULL,
    email              text,
    phone              text,
    gstin              text,
    address            text,
    notes              text,
    payment_terms_days integer,
    archived_at        timestamptz,
    version            integer     NOT NULL DEFAULT 0,
    created_by         uuid        NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT clients_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT clients_name_chk CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT clients_terms_chk CHECK (payment_terms_days IS NULL OR payment_terms_days BETWEEN 0 AND 365)
);
CREATE UNIQUE INDEX clients_active_name_idx ON clients (tenant_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE vendors (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    name        text        NOT NULL,
    email       text,
    phone       text,
    gstin       text,
    address     text,
    notes       text,
    archived_at timestamptz,
    version     integer     NOT NULL DEFAULT 0,
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT vendors_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT vendors_name_chk CHECK (char_length(name) BETWEEN 1 AND 120)
);
CREATE UNIQUE INDEX vendors_active_name_idx ON vendors (tenant_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE projects (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    name          text        NOT NULL,
    code          text,
    client_id     uuid,
    status        text        NOT NULL DEFAULT 'ACTIVE',
    currency      char(3)     NOT NULL,
    budget_amount numeric(19,4),
    start_date    date,
    end_date      date,
    description   text,
    archived_at   timestamptz,
    version       integer     NOT NULL DEFAULT 0,
    created_by    uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT projects_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT projects_client_fk FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id),
    CONSTRAINT projects_name_chk CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT projects_status_chk CHECK (status IN ('PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT projects_currency_chk CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT projects_budget_chk CHECK (budget_amount IS NULL OR budget_amount > 0),
    CONSTRAINT projects_dates_chk CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);
CREATE UNIQUE INDEX projects_active_name_idx ON projects (tenant_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE expense_categories (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    name        text        NOT NULL,
    archived_at timestamptz,
    version     integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT expense_categories_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT expense_categories_name_chk CHECK (char_length(name) BETWEEN 1 AND 80)
);
CREATE UNIQUE INDEX expense_categories_active_name_idx ON expense_categories (tenant_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE business_expenses (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    expense_date date        NOT NULL,
    amount       numeric(19,4) NOT NULL,
    currency     char(3)     NOT NULL,
    vendor_id    uuid,
    project_id   uuid,
    category_id  uuid,
    description  text        NOT NULL,
    reference    text,
    status       text        NOT NULL DEFAULT 'POSTED',
    void_reason  text,
    voided_at    timestamptz,
    version      integer     NOT NULL DEFAULT 0,
    created_by   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT business_expenses_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT business_expenses_vendor_fk FOREIGN KEY (tenant_id, vendor_id) REFERENCES vendors (tenant_id, id),
    CONSTRAINT business_expenses_project_fk FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    CONSTRAINT business_expenses_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES expense_categories (tenant_id, id),
    CONSTRAINT business_expenses_amount_chk CHECK (amount > 0),
    CONSTRAINT business_expenses_status_chk CHECK (status IN ('POSTED', 'VOIDED')),
    CONSTRAINT business_expenses_void_chk CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)),
    CONSTRAINT business_expenses_currency_chk CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT business_expenses_description_chk CHECK (char_length(description) BETWEEN 1 AND 300)
);
-- The same vendor bill reference cannot be posted twice while the first one stands.
CREATE UNIQUE INDEX business_expenses_vendor_ref_idx ON business_expenses (tenant_id, vendor_id, lower(reference))
    WHERE status = 'POSTED' AND vendor_id IS NOT NULL AND reference IS NOT NULL;
CREATE INDEX business_expenses_date_idx ON business_expenses (tenant_id, expense_date DESC, id);
CREATE INDEX business_expenses_project_idx ON business_expenses (tenant_id, project_id) WHERE project_id IS NOT NULL;

CREATE TABLE invoices (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    client_id      uuid        NOT NULL,
    project_id     uuid,
    status         text        NOT NULL DEFAULT 'DRAFT',
    number         text,
    invoice_date   date        NOT NULL,
    due_date       date        NOT NULL,
    currency       char(3)     NOT NULL,
    subtotal       numeric(19,4) NOT NULL,
    discount_total numeric(19,4) NOT NULL,
    tax_total      numeric(19,4) NOT NULL,
    total          numeric(19,4) NOT NULL,
    notes          text,
    issued_at      timestamptz,
    issued_by      uuid,
    cancelled_at   timestamptz,
    cancel_reason  text,
    version        integer     NOT NULL DEFAULT 0,
    created_by     uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT invoices_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT invoices_client_fk FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id),
    CONSTRAINT invoices_project_fk FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
    CONSTRAINT invoices_status_chk CHECK (status IN ('DRAFT', 'ISSUED', 'CANCELLED')),
    CONSTRAINT invoices_number_chk CHECK (status <> 'ISSUED' OR number IS NOT NULL),
    CONSTRAINT invoices_draft_number_chk CHECK (status <> 'DRAFT' OR number IS NULL),
    CONSTRAINT invoices_due_chk CHECK (due_date >= invoice_date),
    CONSTRAINT invoices_amounts_chk CHECK (subtotal >= 0 AND discount_total >= 0 AND tax_total >= 0 AND total >= 0),
    CONSTRAINT invoices_currency_chk CHECK (currency ~ '^[A-Z]{3}$')
);
-- Numbering is unique per business workspace; numbers are only given at issue (no provisional numbers, DEC-34 default).
CREATE UNIQUE INDEX invoices_number_idx ON invoices (tenant_id, number) WHERE number IS NOT NULL;
CREATE INDEX invoices_list_idx ON invoices (tenant_id, invoice_date DESC, id);
CREATE INDEX invoices_client_idx ON invoices (tenant_id, client_id);
CREATE INDEX invoices_project_idx ON invoices (tenant_id, project_id) WHERE project_id IS NOT NULL;

CREATE TABLE invoice_lines (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    invoice_id   uuid        NOT NULL,
    position     integer     NOT NULL,
    description  text        NOT NULL,
    quantity     numeric(19,4) NOT NULL,
    unit_price   numeric(19,4) NOT NULL,
    gross_amount numeric(19,4) NOT NULL,
    discount     numeric(19,4) NOT NULL,
    net_amount   numeric(19,4) NOT NULL,
    tax_percent  numeric(5,2)  NOT NULL DEFAULT 0,
    tax_amount   numeric(19,4) NOT NULL,
    line_total   numeric(19,4) NOT NULL,
    CONSTRAINT invoice_lines_invoice_fk FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id),
    CONSTRAINT invoice_lines_position_key UNIQUE (invoice_id, position),
    CONSTRAINT invoice_lines_quantity_chk CHECK (quantity > 0),
    CONSTRAINT invoice_lines_amounts_chk CHECK (unit_price >= 0 AND discount >= 0 AND discount <= gross_amount AND tax_amount >= 0),
    CONSTRAINT invoice_lines_tax_chk CHECK (tax_percent BETWEEN 0 AND 100)
);
CREATE INDEX invoice_lines_invoice_idx ON invoice_lines (tenant_id, invoice_id);

CREATE TABLE payments (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    client_id    uuid        NOT NULL,
    payment_date date        NOT NULL,
    amount       numeric(19,4) NOT NULL,
    currency     char(3)     NOT NULL,
    method       text        NOT NULL,
    reference    text,
    note         text,
    status       text        NOT NULL DEFAULT 'RECORDED',
    void_reason  text,
    voided_at    timestamptz,
    version      integer     NOT NULL DEFAULT 0,
    created_by   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT payments_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT payments_client_fk FOREIGN KEY (tenant_id, client_id) REFERENCES clients (tenant_id, id),
    CONSTRAINT payments_amount_chk CHECK (amount > 0),
    CONSTRAINT payments_method_chk CHECK (method IN ('BANK_TRANSFER', 'UPI', 'CASH', 'CHEQUE', 'CARD', 'OTHER')),
    CONSTRAINT payments_status_chk CHECK (status IN ('RECORDED', 'VOIDED')),
    CONSTRAINT payments_void_chk CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)),
    CONSTRAINT payments_currency_chk CHECK (currency ~ '^[A-Z]{3}$')
);
-- The same client cannot pay with the same reference twice while the first payment stands (duplicate-payment guard).
CREATE UNIQUE INDEX payments_client_ref_idx ON payments (tenant_id, client_id, lower(reference))
    WHERE status = 'RECORDED' AND reference IS NOT NULL;
CREATE INDEX payments_list_idx ON payments (tenant_id, payment_date DESC, id);

CREATE TABLE payment_allocations (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    payment_id uuid        NOT NULL,
    invoice_id uuid        NOT NULL,
    amount     numeric(19,4) NOT NULL,
    created_by uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT payment_allocations_payment_fk FOREIGN KEY (tenant_id, payment_id) REFERENCES payments (tenant_id, id),
    CONSTRAINT payment_allocations_invoice_fk FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id),
    CONSTRAINT payment_allocations_pair_key UNIQUE (payment_id, invoice_id),
    CONSTRAINT payment_allocations_amount_chk CHECK (amount > 0)
);
CREATE INDEX payment_allocations_invoice_idx ON payment_allocations (tenant_id, invoice_id);
