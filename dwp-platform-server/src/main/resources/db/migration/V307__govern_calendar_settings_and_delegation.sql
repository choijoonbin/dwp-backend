-- Calendar user settings and scoped delegation contract.
ALTER TABLE cal_identity_links
    ADD CONSTRAINT uk_cal_identity_user_person
    UNIQUE (tenant_id, user_id, person_public_id);

CREATE TABLE cal_user_settings (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    person_public_id UUID NOT NULL,
    working_days_mask SMALLINT NOT NULL DEFAULT 31,
    working_day_start TIME NOT NULL DEFAULT TIME '09:00',
    working_day_end TIME NOT NULL DEFAULT TIME '18:00',
    time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    week_start SMALLINT NOT NULL DEFAULT 1,
    default_event_minutes INTEGER NOT NULL DEFAULT 30,
    speedy_meeting_mode VARCHAR(20) NOT NULL DEFAULT 'FIVE_TEN',
    default_buffer_minutes INTEGER NOT NULL DEFAULT 5,
    default_visibility VARCHAR(20) NOT NULL DEFAULT 'FREE_BUSY',
    default_reminder_minutes INTEGER NOT NULL DEFAULT 10,
    settings_origin VARCHAR(20) NOT NULL DEFAULT 'TENANT_POLICY',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT fk_cal_user_settings_identity
        FOREIGN KEY (tenant_id, user_id, person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_cal_user_settings_person
        UNIQUE (tenant_id, person_public_id),
    CONSTRAINT ck_cal_user_settings_days
        CHECK (working_days_mask BETWEEN 1 AND 127),
    CONSTRAINT ck_cal_user_settings_hours
        CHECK (working_day_end > working_day_start),
    CONSTRAINT ck_cal_user_settings_zone
        CHECK (btrim(time_zone) = time_zone AND length(time_zone) BETWEEN 1 AND 80),
    CONSTRAINT ck_cal_user_settings_week_start
        CHECK (week_start BETWEEN 1 AND 7),
    CONSTRAINT ck_cal_user_settings_duration
        CHECK (default_event_minutes BETWEEN 5 AND 1440),
    CONSTRAINT ck_cal_user_settings_speedy
        CHECK (speedy_meeting_mode IN ('STANDARD', 'FIVE_TEN')),
    CONSTRAINT ck_cal_user_settings_buffer
        CHECK (default_buffer_minutes BETWEEN 0 AND 120),
    CONSTRAINT ck_cal_user_settings_visibility
        CHECK (default_visibility IN ('FREE_BUSY', 'DETAILS', 'PRIVATE')),
    CONSTRAINT ck_cal_user_settings_reminder
        CHECK (default_reminder_minutes BETWEEN 0 AND 10080),
    CONSTRAINT ck_cal_user_settings_origin
        CHECK (settings_origin IN ('USER', 'TENANT_POLICY')),
    CONSTRAINT ck_cal_user_settings_version
        CHECK (version >= 0)
);

CREATE TABLE cal_calendar_delegations (
    delegation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    owner_person_public_id UUID NOT NULL,
    delegate_person_public_id UUID NOT NULL,
    can_respond BOOLEAN NOT NULL DEFAULT FALSE,
    can_edit_schedule BOOLEAN NOT NULL DEFAULT FALSE,
    can_create BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT fk_cal_delegation_owner
        FOREIGN KEY (tenant_id, owner_user_id, owner_person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_cal_delegation_delegate
        FOREIGN KEY (tenant_id, delegate_person_public_id)
        REFERENCES cal_identity_links (tenant_id, person_public_id),
    CONSTRAINT ck_cal_delegation_not_self
        CHECK (owner_person_public_id <> delegate_person_public_id),
    CONSTRAINT ck_cal_delegation_scope
        CHECK (can_respond OR can_edit_schedule OR can_create),
    CONSTRAINT ck_cal_delegation_period
        CHECK (valid_until > valid_from),
    CONSTRAINT ck_cal_delegation_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_cal_delegation_revoke_evidence
        CHECK ((status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL)),
    CONSTRAINT ck_cal_delegation_version
        CHECK (version >= 0)
);

CREATE INDEX idx_cal_user_settings_person
    ON cal_user_settings (tenant_id, person_public_id);

CREATE INDEX idx_cal_delegation_owner_time
    ON cal_calendar_delegations (
        tenant_id, owner_user_id, owner_person_public_id, status,
        valid_from, valid_until, updated_at DESC);

CREATE INDEX idx_cal_delegation_delegate_time
    ON cal_calendar_delegations (
        tenant_id, delegate_person_public_id, status, valid_from, valid_until);

COMMENT ON TABLE cal_user_settings IS
    'Versioned Calendar-owned execution defaults for one verified tenant user and person identity.';
COMMENT ON COLUMN cal_user_settings.working_days_mask IS
    'ISO weekday bit mask where Monday is bit 0 and Sunday is bit 6.';
COMMENT ON COLUMN cal_user_settings.speedy_meeting_mode IS
    'STANDARD keeps the selected duration; FIVE_TEN ends 30-minute meetings five minutes and 60-minute meetings ten minutes early.';
COMMENT ON COLUMN cal_user_settings.settings_origin IS
    'USER for an explicit override; TENANT_POLICY after initial materialization or a versioned reset.';
COMMENT ON TABLE cal_calendar_delegations IS
    'Calendar on-behalf-of authority ledger, intentionally separate from calendar visibility and sharing grants.';
COMMENT ON COLUMN cal_calendar_delegations.status IS
    'Command lifecycle state. Scheduled and expired states are derived from the validity interval at read time.';
