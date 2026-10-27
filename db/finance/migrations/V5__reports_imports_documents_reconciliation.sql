-- Step 19: imports, documents/OCR review and reconciliation (reports and forecasts read existing tables and add none).
-- Same conventions as V3/V4: tenant_id on every row (the workspace id), composite (tenant_id, id) keys, NUMERIC(19,4) money, no hard deletes
-- of financial records, no row-level security yet. Statement lines are NOT financial records: they only become reconciled when a user confirms
-- a match with an existing record. Imports create financial records only through the existing domain services, row by row.

CREATE TABLE finance_documents (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    domain                text        NOT NULL,
    kind                  text        NOT NULL,
    original_filename     text        NOT NULL,
    content_type          text        NOT NULL,
    size_bytes            bigint      NOT NULL,
    sha256                char(64)    NOT NULL,
    storage_key           text        NOT NULL,
    status                text        NOT NULL DEFAULT 'UPLOADED',
    failure_reason        text,
    extraction            text,
    processing_started_at timestamptz,
    linked_type           text,
    linked_id             uuid,
    deleted_at            timestamptz,
    deleted_by            uuid,
    version               integer     NOT NULL DEFAULT 0,
    created_by            uuid        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT finance_documents_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT finance_documents_domain_chk CHECK (domain IN ('PERSONAL', 'BUSINESS')),
    CONSTRAINT finance_documents_kind_chk CHECK (kind IN ('RECEIPT', 'INVOICE', 'OTHER')),
    CONSTRAINT finance_documents_status_chk CHECK (status IN ('UPLOADED', 'PROCESSING', 'EXTRACTED', 'FAILED', 'UNAVAILABLE', 'CONFIRMED')),
    CONSTRAINT finance_documents_size_chk CHECK (size_bytes > 0),
    CONSTRAINT finance_documents_confirmed_chk CHECK ((status = 'CONFIRMED') = (linked_id IS NOT NULL))
);
-- The same file is stored once per workspace while it exists.
CREATE UNIQUE INDEX finance_documents_sha_idx ON finance_documents (tenant_id, sha256) WHERE deleted_at IS NULL;
CREATE INDEX finance_documents_list_idx ON finance_documents (tenant_id, created_at DESC, id);
CREATE UNIQUE INDEX finance_documents_link_idx ON finance_documents (tenant_id, linked_type, linked_id) WHERE linked_id IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE finance_imports (
    id                       uuid        PRIMARY KEY,
    tenant_id                uuid        NOT NULL,
    domain                   text        NOT NULL,
    kind                     text        NOT NULL,
    status                   text        NOT NULL DEFAULT 'NEEDS_MAPPING',
    filename                 text        NOT NULL,
    sha256                   char(64)    NOT NULL,
    size_bytes               bigint      NOT NULL,
    account_id               uuid,
    currency                 char(3),
    mapping                  text,
    options                  text,
    total_rows               integer     NOT NULL DEFAULT 0,
    valid_rows               integer     NOT NULL DEFAULT 0,
    invalid_rows             integer     NOT NULL DEFAULT 0,
    duplicate_rows           integer     NOT NULL DEFAULT 0,
    possible_duplicate_rows  integer     NOT NULL DEFAULT 0,
    committed_rows           integer     NOT NULL DEFAULT 0,
    skipped_rows             integer     NOT NULL DEFAULT 0,
    rejected_rows            integer     NOT NULL DEFAULT 0,
    possible_duplicate_policy text,
    lease_until              timestamptz,
    cancel_requested         boolean     NOT NULL DEFAULT false,
    created_by               uuid        NOT NULL,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    completed_at             timestamptz,
    CONSTRAINT finance_imports_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT finance_imports_domain_chk CHECK (domain IN ('PERSONAL', 'BUSINESS')),
    CONSTRAINT finance_imports_kind_chk CHECK (kind IN ('STATEMENT', 'TRANSACTIONS', 'EXPENSES')),
    CONSTRAINT finance_imports_status_chk CHECK (status IN ('NEEDS_MAPPING', 'READY', 'COMMITTING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    CONSTRAINT finance_imports_policy_chk CHECK (possible_duplicate_policy IS NULL OR possible_duplicate_policy IN ('SKIP', 'IMPORT'))
);
CREATE INDEX finance_imports_list_idx ON finance_imports (tenant_id, created_at DESC, id);

CREATE TABLE finance_import_rows (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    import_id             uuid        NOT NULL,
    row_number            integer     NOT NULL,
    raw                   text        NOT NULL,
    status                text        NOT NULL DEFAULT 'PARSED',
    errors                text,
    normalized            text,
    external_id           text,
    dedupe_key            text,
    possible_duplicate_of text,
    record_type           text,
    record_id             uuid,
    CONSTRAINT finance_import_rows_import_fk FOREIGN KEY (tenant_id, import_id) REFERENCES finance_imports (tenant_id, id),
    CONSTRAINT finance_import_rows_key UNIQUE (import_id, row_number),
    CONSTRAINT finance_import_rows_status_chk CHECK (status IN ('PARSED', 'VALID', 'INVALID', 'DUPLICATE', 'POSSIBLE_DUPLICATE', 'COMMITTED', 'SKIPPED', 'REJECTED'))
);
CREATE INDEX finance_import_rows_status_idx ON finance_import_rows (tenant_id, import_id, status, row_number);

-- Every committed import row leaves a ledger entry in the same transaction as the record it created. A stable source identifier (EXTERNAL) is
-- unique per workspace and kind, so a retry or a concurrent commit can never create the same record twice. NATURAL keys (hash of the row
-- content plus its ordinal among identical rows in the file) are only used to WARN about possible duplicates; they never discard a row.
CREATE TABLE finance_import_ledger (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    kind        text        NOT NULL,
    key_type    text        NOT NULL,
    dedupe_key  text        NOT NULL,
    import_id   uuid        NOT NULL,
    row_number  integer     NOT NULL,
    record_type text        NOT NULL,
    record_id   uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT finance_import_ledger_key_chk CHECK (key_type IN ('EXTERNAL', 'NATURAL'))
);
CREATE UNIQUE INDEX finance_import_ledger_external_idx ON finance_import_ledger (tenant_id, kind, dedupe_key) WHERE key_type = 'EXTERNAL';
CREATE INDEX finance_import_ledger_natural_idx ON finance_import_ledger (tenant_id, kind, dedupe_key) WHERE key_type = 'NATURAL';

CREATE TABLE finance_statement_lines (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    domain       text        NOT NULL,
    import_id    uuid        NOT NULL,
    row_number   integer     NOT NULL,
    account_id   uuid,
    line_date    date        NOT NULL,
    amount       numeric(19,4) NOT NULL,
    currency     char(3)     NOT NULL,
    description  text,
    reference    text,
    external_id  text,
    status       text        NOT NULL DEFAULT 'UNMATCHED',
    ignore_reason text,
    version      integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT finance_statement_lines_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT finance_statement_lines_import_fk FOREIGN KEY (tenant_id, import_id) REFERENCES finance_imports (tenant_id, id),
    CONSTRAINT finance_statement_lines_row_key UNIQUE (import_id, row_number),
    CONSTRAINT finance_statement_lines_amount_chk CHECK (amount <> 0),
    CONSTRAINT finance_statement_lines_status_chk CHECK (status IN ('UNMATCHED', 'MATCHED', 'IGNORED')),
    CONSTRAINT finance_statement_lines_domain_chk CHECK (domain IN ('PERSONAL', 'BUSINESS'))
);
CREATE INDEX finance_statement_lines_list_idx ON finance_statement_lines (tenant_id, status, line_date DESC, id);

CREATE TABLE finance_reconciliation_matches (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    line_id      uuid        NOT NULL,
    target_type  text        NOT NULL,
    target_id    uuid        NOT NULL,
    status       text        NOT NULL DEFAULT 'CONFIRMED',
    score        integer     NOT NULL,
    signals      text,
    confirmed_by uuid        NOT NULL,
    confirmed_at timestamptz NOT NULL,
    undone_by    uuid,
    undone_at    timestamptz,
    undo_reason  text,
    CONSTRAINT finance_reconciliation_matches_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT finance_reconciliation_matches_line_fk FOREIGN KEY (tenant_id, line_id) REFERENCES finance_statement_lines (tenant_id, id),
    CONSTRAINT finance_reconciliation_matches_type_chk CHECK (target_type IN ('TRANSACTION', 'PAYMENT', 'EXPENSE')),
    CONSTRAINT finance_reconciliation_matches_status_chk CHECK (status IN ('CONFIRMED', 'UNDONE')),
    CONSTRAINT finance_reconciliation_matches_undo_chk CHECK ((status = 'UNDONE') = (undone_at IS NOT NULL))
);
-- One statement line is matched at most once, and one record is reconciled at most once, while the match stands.
CREATE UNIQUE INDEX finance_reconciliation_line_idx ON finance_reconciliation_matches (tenant_id, line_id) WHERE status = 'CONFIRMED';
CREATE UNIQUE INDEX finance_reconciliation_target_idx ON finance_reconciliation_matches (tenant_id, target_type, target_id) WHERE status = 'CONFIRMED';
