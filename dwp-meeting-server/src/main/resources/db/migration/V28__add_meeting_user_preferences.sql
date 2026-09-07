CREATE TABLE vm_meeting_user_preferences (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    display_name VARCHAR(100) NOT NULL DEFAULT '',
    microphone_off BOOLEAN NOT NULL DEFAULT TRUE,
    camera_off BOOLEAN NOT NULL DEFAULT TRUE,
    prejoin_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_minutes INTEGER NOT NULL DEFAULT 10,
    recap_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_vm_user_preference_minutes CHECK (reminder_minutes BETWEEN 0 AND 60),
    CONSTRAINT ck_vm_user_preference_version CHECK (version >= 0)
);
COMMENT ON TABLE vm_meeting_user_preferences IS
    'Account-scoped convenience preferences only. No device IDs, consent, tokens or authority; meeting policies and admission remain authoritative.';
