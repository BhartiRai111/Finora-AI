-- Authentication and sessions (Step 15). Owned by identity-access only.
-- Secrets are never stored in the clear: passwords are Argon2id hashes; session and reset tokens are SHA-256 hashes
-- of 256-bit random values (high-entropy, so a fast hash is appropriate and lookup by hash is possible).

CREATE TABLE users (
    id                 uuid        PRIMARY KEY,
    email              text        NOT NULL,
    email_normalized   text        NOT NULL,
    display_name       text        NOT NULL,
    password_hash      text        NOT NULL,
    status             text        NOT NULL DEFAULT 'ACTIVE',
    email_verified_at  timestamptz,
    password_changed_at timestamptz NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT users_email_normalized_key UNIQUE (email_normalized),
    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT users_email_lower_chk CHECK (email_normalized = lower(email_normalized)),
    CONSTRAINT users_display_name_chk CHECK (char_length(display_name) BETWEEN 1 AND 100)
);

CREATE TABLE sessions (
    id                   uuid        PRIMARY KEY,
    user_id              uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash           bytea       NOT NULL,
    csrf_token           text        NOT NULL,
    created_at           timestamptz NOT NULL,
    last_seen_at         timestamptz NOT NULL,
    absolute_expires_at  timestamptz NOT NULL,
    revoked_at           timestamptz,
    revoke_reason        text,
    CONSTRAINT sessions_token_hash_key UNIQUE (token_hash),
    CONSTRAINT sessions_token_hash_len_chk CHECK (octet_length(token_hash) = 32),
    CONSTRAINT sessions_revoked_chk CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX sessions_user_active_idx ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX sessions_expiry_idx ON sessions (absolute_expires_at);

CREATE TABLE password_reset_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    CONSTRAINT password_reset_tokens_hash_key UNIQUE (token_hash),
    CONSTRAINT password_reset_tokens_hash_len_chk CHECK (octet_length(token_hash) = 32),
    CONSTRAINT password_reset_tokens_expiry_chk CHECK (expires_at > created_at)
);
CREATE INDEX password_reset_tokens_user_idx ON password_reset_tokens (user_id, created_at DESC);

-- Failed-login throttle keyed by a hash of the normalized email, whether or not the account exists
-- (so a lock reveals nothing about which accounts exist).
CREATE TABLE auth_throttle (
    key_hash        text        PRIMARY KEY,
    failure_count   integer     NOT NULL,
    last_failure_at timestamptz NOT NULL,
    locked_until    timestamptz,
    CONSTRAINT auth_throttle_count_chk CHECK (failure_count >= 0)
);

-- Local security event log. Holds no emails, passwords or tokens. Not the audit architecture (PD-15 is still open).
CREATE TABLE auth_events (
    id          uuid        PRIMARY KEY,
    event_type  text        NOT NULL,
    user_id     uuid,
    request_id  text,
    created_at  timestamptz NOT NULL
);
CREATE INDEX auth_events_user_idx ON auth_events (user_id, created_at DESC);
