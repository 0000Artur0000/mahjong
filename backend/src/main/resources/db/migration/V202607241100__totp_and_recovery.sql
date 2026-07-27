CREATE TABLE account_totp (
    account_id UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    encrypted_secret BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'enabled')),
    failed_attempts SMALLINT NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    locked_until TIMESTAMPTZ,
    last_used_counter BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    enabled_at TIMESTAMPTZ
);

CREATE TABLE account_recovery_code (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (account_id, code_hash)
);

CREATE INDEX account_recovery_code_unused_idx
    ON account_recovery_code (account_id)
    WHERE used_at IS NULL;

CREATE TABLE account_security_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES app_user (id),
    action VARCHAR(32) NOT NULL
        CHECK (action IN ('totp_enrollment_started', 'totp_enabled', 'totp_disabled', 'recovery_used')),
    correlation_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX account_security_audit_account_idx
    ON account_security_audit (account_id, created_at DESC);
