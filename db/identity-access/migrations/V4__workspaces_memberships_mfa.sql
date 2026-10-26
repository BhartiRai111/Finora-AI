-- Workspaces, memberships, invitations, MFA and the local security audit log (Step 16). Owned by identity-access only.
-- Personal profile = a PERSONAL workspace (its own tenant, one per user, sole Owner, never invitable).
-- Tenant-scoped row-level security is still NOT applied (tenant-context work remains pending); every query in the
-- service is scoped by workspace id and covered by isolation tests.

CREATE TABLE workspaces (
    id          uuid        PRIMARY KEY,
    name        text        NOT NULL,
    type        text        NOT NULL,
    status      text        NOT NULL DEFAULT 'ACTIVE',
    created_by  uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT workspaces_type_chk CHECK (type IN ('PERSONAL', 'BUSINESS')),
    CONSTRAINT workspaces_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT workspaces_name_chk CHECK (char_length(name) BETWEEN 1 AND 100)
);
CREATE UNIQUE INDEX workspaces_one_personal_per_user_idx ON workspaces (created_by) WHERE type = 'PERSONAL';

CREATE TABLE memberships (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id       uuid        NOT NULL REFERENCES users (id),
    role          text        NOT NULL,
    status        text        NOT NULL DEFAULT 'ACTIVE',
    invited_by    uuid        REFERENCES users (id),
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    removed_at    timestamptz,
    removed_by    uuid        REFERENCES users (id),
    CONSTRAINT memberships_role_chk CHECK (role IN ('OWNER', 'ADMIN', 'ACCOUNTANT', 'MANAGER', 'CONTRIBUTOR', 'AUDITOR', 'VIEWER')),
    CONSTRAINT memberships_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED')),
    CONSTRAINT memberships_removed_chk CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL))
);
-- One live membership per (workspace, user); removed rows are history and may repeat.
CREATE UNIQUE INDEX memberships_live_idx ON memberships (workspace_id, user_id) WHERE status <> 'REMOVED';
CREATE INDEX memberships_user_active_idx ON memberships (user_id) WHERE status = 'ACTIVE';
CREATE INDEX memberships_workspace_owner_idx ON memberships (workspace_id) WHERE status = 'ACTIVE' AND role = 'OWNER';

CREATE TABLE invitations (
    id               uuid        PRIMARY KEY,
    workspace_id     uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    email_normalized text        NOT NULL,
    role             text        NOT NULL,
    token_hash       bytea       NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING',
    invited_by       uuid        NOT NULL REFERENCES users (id),
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    resolved_at      timestamptz,
    resolved_by      uuid        REFERENCES users (id),
    CONSTRAINT invitations_token_hash_key UNIQUE (token_hash),
    CONSTRAINT invitations_token_hash_len_chk CHECK (octet_length(token_hash) = 32),
    CONSTRAINT invitations_role_chk CHECK (role IN ('OWNER', 'ADMIN', 'ACCOUNTANT', 'MANAGER', 'CONTRIBUTOR', 'AUDITOR', 'VIEWER')),
    CONSTRAINT invitations_status_chk CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT invitations_expiry_chk CHECK (expires_at > created_at),
    CONSTRAINT invitations_email_lower_chk CHECK (email_normalized = lower(email_normalized)),
    CONSTRAINT invitations_resolved_chk CHECK ((status = 'PENDING') = (resolved_at IS NULL))
);
CREATE UNIQUE INDEX invitations_one_pending_idx ON invitations (workspace_id, email_normalized) WHERE status = 'PENDING';
CREATE INDEX invitations_workspace_idx ON invitations (workspace_id, created_at DESC);

-- Backfill a personal profile for every account that already exists (created before this migration).
INSERT INTO workspaces (id, name, type, status, created_by, created_at, updated_at)
SELECT gen_random_uuid(), 'Personal', 'PERSONAL', 'ACTIVE', u.id, now(), now() FROM users u;
INSERT INTO memberships (id, workspace_id, user_id, role, status, created_at, updated_at)
SELECT gen_random_uuid(), w.id, w.created_by, 'OWNER', 'ACTIVE', now(), now() FROM workspaces w WHERE w.type = 'PERSONAL';

-- MFA (TOTP + recovery codes). The TOTP secret is stored only encrypted (AES-256-GCM, key from the environment).
CREATE TABLE mfa_factors (
    user_id            uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    secret_ciphertext  bytea       NOT NULL,
    confirmed_at       timestamptz,
    last_used_step     bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL
);

CREATE TABLE mfa_recovery_codes (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  bytea       NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT mfa_recovery_codes_hash_key UNIQUE (code_hash),
    CONSTRAINT mfa_recovery_codes_hash_len_chk CHECK (octet_length(code_hash) = 32)
);
CREATE INDEX mfa_recovery_codes_user_idx ON mfa_recovery_codes (user_id) WHERE used_at IS NULL;

ALTER TABLE sessions ADD COLUMN mfa_pending     boolean     NOT NULL DEFAULT false;
ALTER TABLE sessions ADD COLUMN mfa_verified_at timestamptz;
ALTER TABLE sessions ADD COLUMN step_up_until   timestamptz;

-- Local, append-only security audit log. The runtime role may INSERT and SELECT but never UPDATE or DELETE.
-- This is NOT the approved audit architecture: PD-15 (central audit service, hash chain) is still pending.
CREATE TABLE security_audit_events (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid,
    actor_user_id uuid,
    event_type    text        NOT NULL,
    target_type   text,
    target_id     text,
    outcome       text        NOT NULL,
    detail        text,
    request_id    text,
    created_at    timestamptz NOT NULL,
    CONSTRAINT security_audit_events_outcome_chk CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE'))
);
CREATE INDEX security_audit_events_workspace_idx ON security_audit_events (workspace_id, created_at DESC);
CREATE INDEX security_audit_events_actor_idx ON security_audit_events (actor_user_id, created_at DESC);
REVOKE UPDATE, DELETE, TRUNCATE ON security_audit_events FROM ${app_role};
