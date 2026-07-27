CREATE TABLE external_login_attempt (
    id UUID PRIMARY KEY,
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('telegram', 'vk')),
    state_hash CHAR(64) NOT NULL UNIQUE,
    session_id_hash CHAR(64) NOT NULL,
    code_verifier VARCHAR(128) NOT NULL,
    nonce_hash CHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE INDEX external_login_attempt_expiry_idx ON external_login_attempt (expires_at);
