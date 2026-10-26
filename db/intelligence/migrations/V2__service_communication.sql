-- Service communication tables (Step 14): idempotency keys, transactional outbox, consumer inbox.
-- Persistence only: no relay, broker or transport exists yet (PD-04 is open).
-- Tenant-scoped row-level security is NOT applied here; it arrives with the tenant-context work (kept pending).

CREATE TABLE idempotency_keys (
    scope           text        NOT NULL,
    idem_key        text        NOT NULL,
    request_hash    text        NOT NULL,
    response_status integer,
    response_body   text,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    PRIMARY KEY (scope, idem_key)
);
CREATE INDEX idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);

CREATE TABLE outbox_events (
    id             uuid        PRIMARY KEY,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    correlation_id text,
    created_at     timestamptz NOT NULL,
    published_at   timestamptz
);
CREATE INDEX outbox_events_unpublished_idx ON outbox_events (created_at, id) WHERE published_at IS NULL;

CREATE TABLE inbox_events (
    consumer_name text        NOT NULL,
    event_id      uuid        NOT NULL,
    received_at   timestamptz NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);
