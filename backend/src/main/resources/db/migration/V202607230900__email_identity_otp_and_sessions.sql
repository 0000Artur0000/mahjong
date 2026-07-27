CREATE TABLE account_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user (id),
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, subject)
);

CREATE TABLE email_login_code (
    id UUID PRIMARY KEY,
    email_normalized VARCHAR(320) NOT NULL,
    code_hash VARCHAR(100) NOT NULL,
    attempts_remaining SMALLINT NOT NULL CHECK (attempts_remaining BETWEEN 0 AND 5),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX email_login_code_active_idx
    ON email_login_code (email_normalized)
    WHERE consumed_at IS NULL;
CREATE INDEX email_login_code_created_idx
    ON email_login_code (email_normalized, created_at DESC);
CREATE INDEX email_login_code_expiry_idx ON email_login_code (expires_at);

CREATE TABLE spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk
        FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
