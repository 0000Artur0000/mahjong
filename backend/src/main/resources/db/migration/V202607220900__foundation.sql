CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL CHECK (status IN ('active', 'deactivated', 'anonymized')),
    nickname VARCHAR(64) NOT NULL,
    nickname_normalized VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ruleset (
    key VARCHAR(64) NOT NULL,
    version VARCHAR(32) NOT NULL,
    checksum CHAR(64) NOT NULL CHECK (length(checksum) = 64),
    parameters JSONB NOT NULL,
    certification_status VARCHAR(32) NOT NULL
        CHECK (certification_status IN ('draft', 'certified', 'deprecated')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (key, version)
);

CREATE TABLE game_table (
    id UUID PRIMARY KEY,
    format VARCHAR(32) NOT NULL
        CHECK (format IN ('yonma_hanchan', 'yonma_tonpusen', 'sanma_hanchan', 'sanma_tonpusen', 'custom')),
    state VARCHAR(32) NOT NULL
        CHECK (state IN ('draft', 'lobby', 'active', 'hand_capture', 'hand_review', 'hand_confirmed', 'finished', 'pending_appeal', 'final', 'disputed', 'void', 'cancelled')),
    aggregate_version BIGINT NOT NULL DEFAULT 0 CHECK (aggregate_version >= 0),
    ruleset_key VARCHAR(64) NOT NULL,
    ruleset_version VARCHAR(32) NOT NULL,
    ruleset_checksum CHAR(64) NOT NULL CHECK (length(ruleset_checksum) = 64),
    ruleset_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (ruleset_key, ruleset_version) REFERENCES ruleset (key, version)
);

CREATE TABLE table_event (
    aggregate_id UUID NOT NULL REFERENCES game_table (id),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    type VARCHAR(64) NOT NULL,
    actor_id UUID REFERENCES app_user (id),
    source VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (aggregate_id, sequence)
);

CREATE TABLE idempotency_record (
    actor_id UUID NOT NULL REFERENCES app_user (id),
    scope VARCHAR(128) NOT NULL,
    key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (length(request_hash) = 64),
    status VARCHAR(32) NOT NULL CHECK (status IN ('processing', 'completed', 'failed_retryable')),
    response_status INTEGER,
    response_body JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_id, scope, key)
);

CREATE INDEX table_event_created_at_idx ON table_event (created_at);
CREATE INDEX idempotency_record_expiry_idx ON idempotency_record (expires_at);

