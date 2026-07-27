ALTER TABLE app_user
    ADD COLUMN city VARCHAR(128),
    ADD COLUMN avatar_media_id UUID,
    ADD COLUMN show_city BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN show_clubs BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE account_nickname_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    nickname VARCHAR(64) NOT NULL,
    nickname_normalized VARCHAR(64) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ
);

CREATE INDEX account_nickname_history_account_idx
    ON account_nickname_history (account_id, valid_from DESC);

INSERT INTO account_nickname_history
    (account_id, nickname, nickname_normalized, valid_from)
SELECT id, nickname, nickname_normalized, created_at
FROM app_user;

CREATE FUNCTION record_account_nickname_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO account_nickname_history
            (account_id, nickname, nickname_normalized, valid_from)
        VALUES (NEW.id, NEW.nickname, NEW.nickname_normalized, NEW.created_at);
    ELSIF NEW.nickname_normalized IS DISTINCT FROM OLD.nickname_normalized THEN
        UPDATE account_nickname_history
        SET valid_to = NEW.updated_at
        WHERE account_id = OLD.id AND valid_to IS NULL;

        INSERT INTO account_nickname_history
            (account_id, nickname, nickname_normalized, valid_from)
        VALUES (NEW.id, NEW.nickname, NEW.nickname_normalized, NEW.updated_at);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER app_user_nickname_history
AFTER INSERT OR UPDATE OF nickname, nickname_normalized ON app_user
FOR EACH ROW EXECUTE FUNCTION record_account_nickname_change();

CREATE TABLE account_cleanup_job (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL UNIQUE REFERENCES app_user (id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'running', 'completed', 'failed')),
    report JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX account_cleanup_job_pending_idx
    ON account_cleanup_job (created_at)
    WHERE status IN ('pending', 'running');
