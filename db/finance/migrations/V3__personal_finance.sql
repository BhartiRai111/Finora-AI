-- Personal finance (Step 17). Owned by the finance service only.
-- Money: NUMERIC(19,4), never floating point. Every amount row carries its ISO 4217 currency; precision per currency is
-- validated by the service (PD-09 rounding/FX policy is still pending: no conversion, no rounding of stored amounts).
-- Tenancy: every row carries tenant_id (the workspace id) and every reference is a composite (tenant_id, id) foreign key, so a
-- record can only ever point at records of its own tenant (PERM-4). Row-level security is NOT applied yet (tenant-context
-- work remains pending); every query in the service is tenant-scoped and covered by isolation tests.

CREATE TABLE finance_settings (
    tenant_id  uuid        PRIMARY KEY,
    timezone   text        NOT NULL DEFAULT 'UTC',
    updated_at timestamptz NOT NULL
);

CREATE TABLE accounts (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    name             text        NOT NULL,
    type             text        NOT NULL,
    currency         char(3)     NOT NULL,
    opening_balance  numeric(19,4) NOT NULL,
    opening_date     date        NOT NULL,
    archived_at      timestamptz,
    version          integer     NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT accounts_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT accounts_name_chk CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT accounts_type_chk CHECK (type IN ('CASH', 'BANK', 'WALLET', 'INVESTMENT', 'OTHER_ASSET', 'CREDIT_CARD', 'LOAN', 'OTHER_LIABILITY')),
    CONSTRAINT accounts_currency_chk CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE UNIQUE INDEX accounts_active_name_idx ON accounts (tenant_id, lower(name)) WHERE archived_at IS NULL;

CREATE TABLE categories (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    parent_id    uuid,
    name         text        NOT NULL,
    kind         text        NOT NULL,
    archived_at  timestamptz,
    version      integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT categories_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT categories_parent_fk FOREIGN KEY (tenant_id, parent_id) REFERENCES categories (tenant_id, id),
    CONSTRAINT categories_name_chk CHECK (char_length(name) BETWEEN 1 AND 60),
    CONSTRAINT categories_kind_chk CHECK (kind IN ('INCOME', 'EXPENSE')),
    CONSTRAINT categories_not_own_parent_chk CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX categories_active_name_idx ON categories (tenant_id, kind, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name)) WHERE archived_at IS NULL;

CREATE TABLE tags (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT tags_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT tags_tenant_name_key UNIQUE (tenant_id, name),
    CONSTRAINT tags_name_chk CHECK (char_length(name) BETWEEN 1 AND 30 AND name = lower(name))
);

CREATE TABLE recurring_rules (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    kind           text        NOT NULL,
    account_id     uuid        NOT NULL,
    category_id    uuid,
    amount         numeric(19,4) NOT NULL,
    currency       char(3)     NOT NULL,
    payee          text,
    note           text,
    frequency      text        NOT NULL,
    interval_count integer     NOT NULL DEFAULT 1,
    start_date     date        NOT NULL,
    end_date       date,
    next_index     integer     NOT NULL DEFAULT 0,
    auto_post      boolean     NOT NULL DEFAULT false,
    active         boolean     NOT NULL DEFAULT true,
    version        integer     NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT recurring_rules_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT recurring_rules_account_fk FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id),
    CONSTRAINT recurring_rules_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES categories (tenant_id, id),
    CONSTRAINT recurring_rules_kind_chk CHECK (kind IN ('INCOME', 'EXPENSE')),
    CONSTRAINT recurring_rules_frequency_chk CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT recurring_rules_interval_chk CHECK (interval_count BETWEEN 1 AND 366),
    CONSTRAINT recurring_rules_amount_chk CHECK (amount > 0),
    CONSTRAINT recurring_rules_dates_chk CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT recurring_rules_index_chk CHECK (next_index >= 0)
);
CREATE INDEX recurring_rules_active_idx ON recurring_rules (tenant_id) WHERE active;

CREATE TABLE commitments (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    kind           text        NOT NULL,
    name           text        NOT NULL,
    amount         numeric(19,4) NOT NULL,
    currency       char(3)     NOT NULL,
    frequency      text        NOT NULL,
    anchor_date    date        NOT NULL,
    due_index      integer     NOT NULL DEFAULT 0,
    status         text        NOT NULL DEFAULT 'ACTIVE',
    category_id    uuid,
    account_id     uuid,
    note           text,
    version        integer     NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT commitments_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT commitments_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES categories (tenant_id, id),
    CONSTRAINT commitments_account_fk FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id),
    CONSTRAINT commitments_kind_chk CHECK (kind IN ('BILL', 'SUBSCRIPTION')),
    CONSTRAINT commitments_name_chk CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT commitments_frequency_chk CHECK (frequency IN ('ONCE', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    CONSTRAINT commitments_status_chk CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'PAID')),
    CONSTRAINT commitments_amount_chk CHECK (amount > 0),
    CONSTRAINT commitments_subscription_recurs_chk CHECK (kind <> 'SUBSCRIPTION' OR frequency <> 'ONCE'),
    CONSTRAINT commitments_index_chk CHECK (due_index >= 0)
);

CREATE TABLE transactions (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    kind              text        NOT NULL,
    status            text        NOT NULL,
    account_id        uuid        NOT NULL,
    to_account_id     uuid,
    amount            numeric(19,4) NOT NULL,
    currency          char(3)     NOT NULL,
    to_amount         numeric(19,4),
    to_currency       char(3),
    direction         text,
    category_id       uuid,
    refund_of         uuid,
    occurred_on       date        NOT NULL,
    payee             text,
    note              text,
    recurring_rule_id uuid,
    commitment_id     uuid,
    occurrence_index  integer,
    version           integer     NOT NULL DEFAULT 0,
    created_by        uuid        NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    voided_at         timestamptz,
    void_reason       text,
    CONSTRAINT transactions_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT transactions_account_fk FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id),
    CONSTRAINT transactions_to_account_fk FOREIGN KEY (tenant_id, to_account_id) REFERENCES accounts (tenant_id, id),
    CONSTRAINT transactions_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES categories (tenant_id, id),
    CONSTRAINT transactions_refund_fk FOREIGN KEY (tenant_id, refund_of) REFERENCES transactions (tenant_id, id),
    CONSTRAINT transactions_rule_fk FOREIGN KEY (tenant_id, recurring_rule_id) REFERENCES recurring_rules (tenant_id, id),
    CONSTRAINT transactions_commitment_fk FOREIGN KEY (tenant_id, commitment_id) REFERENCES commitments (tenant_id, id),
    CONSTRAINT transactions_kind_chk CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER', 'REFUND', 'ADJUSTMENT')),
    CONSTRAINT transactions_status_chk CHECK (status IN ('DRAFT', 'POSTED', 'VOIDED')),
    CONSTRAINT transactions_amount_chk CHECK (amount > 0),
    CONSTRAINT transactions_currency_chk CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT transactions_note_chk CHECK (note IS NULL OR char_length(note) <= 1000),
    CONSTRAINT transactions_payee_chk CHECK (payee IS NULL OR char_length(payee) <= 120),
    CONSTRAINT transactions_void_chk CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)),
    -- Kind-specific shape: transfers have a distinct destination, only adjustments carry a direction, only refunds link back.
    CONSTRAINT transactions_transfer_shape_chk CHECK ((kind = 'TRANSFER') = (to_account_id IS NOT NULL AND to_amount IS NOT NULL AND to_currency IS NOT NULL)),
    CONSTRAINT transactions_transfer_distinct_chk CHECK (to_account_id IS NULL OR to_account_id <> account_id),
    CONSTRAINT transactions_transfer_amount_chk CHECK (to_amount IS NULL OR to_amount > 0),
    CONSTRAINT transactions_transfer_no_category_chk CHECK (kind NOT IN ('TRANSFER', 'ADJUSTMENT') OR category_id IS NULL),
    CONSTRAINT transactions_direction_chk CHECK ((kind = 'ADJUSTMENT') = (direction IS NOT NULL) AND (direction IS NULL OR direction IN ('INCREASE', 'DECREASE'))),
    CONSTRAINT transactions_refund_chk CHECK ((kind = 'REFUND') = (refund_of IS NOT NULL)),
    CONSTRAINT transactions_source_chk CHECK (recurring_rule_id IS NULL OR commitment_id IS NULL),
    CONSTRAINT transactions_occurrence_chk CHECK ((recurring_rule_id IS NOT NULL OR commitment_id IS NOT NULL) = (occurrence_index IS NOT NULL))
);
-- Generation and payment are exactly-once per occurrence, enforced by the database.
CREATE UNIQUE INDEX transactions_rule_occurrence_idx ON transactions (recurring_rule_id, occurrence_index) WHERE recurring_rule_id IS NOT NULL;
CREATE UNIQUE INDEX transactions_commitment_occurrence_idx ON transactions (commitment_id, occurrence_index) WHERE commitment_id IS NOT NULL;
CREATE INDEX transactions_tenant_date_idx ON transactions (tenant_id, occurred_on DESC, id DESC);
CREATE INDEX transactions_account_idx ON transactions (tenant_id, account_id, occurred_on);
CREATE INDEX transactions_to_account_idx ON transactions (tenant_id, to_account_id) WHERE to_account_id IS NOT NULL;
CREATE INDEX transactions_category_idx ON transactions (tenant_id, category_id, occurred_on) WHERE category_id IS NOT NULL;
CREATE INDEX transactions_refund_idx ON transactions (tenant_id, refund_of) WHERE refund_of IS NOT NULL;

CREATE TABLE transaction_tags (
    tenant_id      uuid NOT NULL,
    transaction_id uuid NOT NULL,
    tag_id         uuid NOT NULL,
    PRIMARY KEY (transaction_id, tag_id),
    CONSTRAINT transaction_tags_tx_fk FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT transaction_tags_tag_fk FOREIGN KEY (tenant_id, tag_id) REFERENCES tags (tenant_id, id)
);
CREATE INDEX transaction_tags_tag_idx ON transaction_tags (tenant_id, tag_id);

CREATE TABLE budgets (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    category_id  uuid,
    amount       numeric(19,4) NOT NULL,
    currency     char(3)     NOT NULL,
    archived_at  timestamptz,
    version      integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT budgets_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT budgets_category_fk FOREIGN KEY (tenant_id, category_id) REFERENCES categories (tenant_id, id),
    CONSTRAINT budgets_amount_chk CHECK (amount > 0)
);
-- One live budget per category (or one overall budget) per currency. Budgets are monthly.
CREATE UNIQUE INDEX budgets_live_idx ON budgets (tenant_id, coalesce(category_id, '00000000-0000-0000-0000-000000000000'::uuid), currency) WHERE archived_at IS NULL;

CREATE TABLE goals (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    name          text        NOT NULL,
    target_amount numeric(19,4) NOT NULL,
    currency      char(3)     NOT NULL,
    target_date   date,
    archived_at   timestamptz,
    version       integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT goals_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT goals_name_chk CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT goals_target_chk CHECK (target_amount > 0)
);

CREATE TABLE goal_contributions (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    goal_id        uuid        NOT NULL,
    amount         numeric(19,4) NOT NULL,
    contributed_on date        NOT NULL,
    note           text,
    created_at     timestamptz NOT NULL,
    CONSTRAINT goal_contributions_goal_fk FOREIGN KEY (tenant_id, goal_id) REFERENCES goals (tenant_id, id),
    CONSTRAINT goal_contributions_amount_chk CHECK (amount <> 0),
    CONSTRAINT goal_contributions_note_chk CHECK (note IS NULL OR char_length(note) <= 500)
);
CREATE INDEX goal_contributions_goal_idx ON goal_contributions (tenant_id, goal_id, contributed_on);

-- Local append-only audit log (runtime role: INSERT/SELECT only). Not the approved audit architecture (PD-15 pending).
CREATE TABLE finance_audit_events (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    actor_id    uuid        NOT NULL,
    event_type  text        NOT NULL,
    target_type text        NOT NULL,
    target_id   text,
    detail      text,
    request_id  text,
    created_at  timestamptz NOT NULL
);
CREATE INDEX finance_audit_events_tenant_idx ON finance_audit_events (tenant_id, created_at DESC);
REVOKE UPDATE, DELETE, TRUNCATE ON finance_audit_events FROM ${app_role};
