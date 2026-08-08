ALTER TABLE sys_auth_sessions
    ADD COLUMN session_family_id UUID,
    ADD COLUMN session_started_at TIMESTAMPTZ,
    ADD COLUMN issued_at TIMESTAMPTZ,
    ADD COLUMN last_seen_at TIMESTAMPTZ,
    ADD COLUMN idle_expires_at TIMESTAMPTZ,
    ADD COLUMN superseded_at TIMESTAMPTZ,
    ADD COLUMN superseded_expires_at TIMESTAMPTZ;

UPDATE sys_auth_sessions
SET session_family_id = session_id,
    session_started_at = COALESCE(created_at, CURRENT_TIMESTAMP),
    issued_at = COALESCE(created_at, CURRENT_TIMESTAMP),
    last_seen_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP),
    idle_expires_at = LEAST(
        expires_at,
        COALESCE(updated_at, created_at, CURRENT_TIMESTAMP) + INTERVAL '30 minutes'
    );

ALTER TABLE sys_auth_sessions
    ALTER COLUMN session_family_id SET NOT NULL,
    ALTER COLUMN session_started_at SET NOT NULL,
    ALTER COLUMN issued_at SET NOT NULL,
    ALTER COLUMN last_seen_at SET NOT NULL,
    ALTER COLUMN idle_expires_at SET NOT NULL;

CREATE INDEX idx_sys_auth_sessions_family
    ON sys_auth_sessions(session_family_id);

CREATE INDEX idx_sys_auth_sessions_activity
    ON sys_auth_sessions(tenant_id, user_id, last_seen_at DESC);

CREATE UNIQUE INDEX uq_sys_auth_sessions_current_family
    ON sys_auth_sessions(session_family_id)
    WHERE superseded_at IS NULL;
