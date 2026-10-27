-- Step 20: usage ledger for the finance assistant. It exists only for rate limits and cost control: it stores WHO asked WHAT KIND of thing and
-- how it ended, never the question text, never prompts, never answers and never financial figures. Append-only for the application role.

CREATE TABLE finance_ai_usage (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    kind       text        NOT NULL,
    intent     text,
    provider   text,
    outcome    text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT finance_ai_usage_kind_chk CHECK (kind IN ('QUESTION', 'PROVIDER_CALL'))
);
CREATE INDEX finance_ai_usage_user_idx ON finance_ai_usage (tenant_id, user_id, kind, created_at DESC);
CREATE INDEX finance_ai_usage_tenant_idx ON finance_ai_usage (tenant_id, kind, created_at DESC);
REVOKE UPDATE, DELETE, TRUNCATE ON finance_ai_usage FROM ${app_role};
