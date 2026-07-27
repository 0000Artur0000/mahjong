ALTER TABLE external_login_attempt
    ADD COLUMN link_account_id UUID REFERENCES app_user (id),
    ADD COLUMN audit_correlation_id VARCHAR(64),
    ADD COLUMN audit_source VARCHAR(32);

CREATE TABLE account_identity_audit (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES app_user (id),
    actor_id UUID NOT NULL REFERENCES app_user (id),
    action VARCHAR(16) NOT NULL CHECK (action IN ('linked', 'unlinked')),
    identity_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    subject_hash CHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX account_identity_audit_account_idx
    ON account_identity_audit (account_id, created_at DESC);
