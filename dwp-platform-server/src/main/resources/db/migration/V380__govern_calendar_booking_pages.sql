CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE cal_booking_pages (
    page_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    owner_person_public_id UUID NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_group_ref UUID,
    calendar_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    time_zone VARCHAR(80) NOT NULL,
    duration_minutes INTEGER NOT NULL,
    buffer_before_minutes INTEGER NOT NULL DEFAULT 0,
    buffer_after_minutes INTEGER NOT NULL DEFAULT 0,
    daily_limit INTEGER NOT NULL,
    minimum_notice_minutes INTEGER NOT NULL DEFAULT 0,
    maximum_advance_days INTEGER NOT NULL,
    meeting_mode VARCHAR(16) NOT NULL,
    location VARCHAR(240),
    conference_url VARCHAR(1000),
    dial_in_instructions VARCHAR(500),
    availability_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    questions JSONB NOT NULL DEFAULT '[]'::jsonb,
    public_token_hash VARCHAR(64),
    public_token_version BIGINT NOT NULL DEFAULT 0,
    public_token_rotated_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_cal_booking_pages_tenant_page UNIQUE (tenant_id, page_id),
    CONSTRAINT uk_cal_booking_pages_host_scope UNIQUE (
        tenant_id, page_id, calendar_id),
    CONSTRAINT fk_cal_booking_pages_owner FOREIGN KEY (
        tenant_id, owner_user_id, owner_person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id),
    CONSTRAINT fk_cal_booking_pages_calendar FOREIGN KEY (tenant_id, calendar_id)
        REFERENCES cal_calendars (tenant_id, calendar_id),
    CONSTRAINT ck_cal_booking_pages_owner CHECK (
        (owner_type = 'PERSONAL' AND owner_group_ref IS NULL)
        OR (owner_type = 'TEAM' AND owner_group_ref IS NOT NULL)),
    CONSTRAINT ck_cal_booking_pages_state CHECK (
        state IN ('DRAFT', 'PUBLISHED', 'PAUSED', 'ARCHIVED')),
    CONSTRAINT ck_cal_booking_pages_schedule CHECK (
        duration_minutes BETWEEN 5 AND 480
        AND buffer_before_minutes BETWEEN 0 AND 180
        AND buffer_after_minutes BETWEEN 0 AND 180
        AND daily_limit BETWEEN 1 AND 100
        AND minimum_notice_minutes BETWEEN 0 AND 10080
        AND maximum_advance_days BETWEEN 1 AND 365),
    CONSTRAINT ck_cal_booking_pages_meeting CHECK (
        meeting_mode IN ('IN_PERSON', 'VIDEO', 'PHONE')
        AND (
            (meeting_mode = 'IN_PERSON'
                AND NULLIF(BTRIM(location), '') IS NOT NULL
                AND conference_url IS NULL AND dial_in_instructions IS NULL)
            OR (meeting_mode = 'VIDEO'
                AND location IS NULL AND dial_in_instructions IS NULL
                AND NULLIF(BTRIM(conference_url), '') IS NOT NULL
                AND conference_url !~ '@'
                AND conference_url ~* '^https://(meet[.]google[.]com|teams[.]microsoft[.]com|teams[.]live[.]com|([a-z0-9-]+[.])*zoom[.]us|([a-z0-9-]+[.])*webex[.]com)(/|$)')
            OR (meeting_mode = 'PHONE'
                AND location IS NULL AND conference_url IS NULL
                AND NULLIF(BTRIM(dial_in_instructions), '') IS NOT NULL
                AND dial_in_instructions = BTRIM(dial_in_instructions))
        )),
    CONSTRAINT ck_cal_booking_pages_json CHECK (
        jsonb_typeof(availability_rules) = 'array'
        AND jsonb_array_length(availability_rules) BETWEEN 1 AND 28
        AND jsonb_typeof(questions) = 'array'
        AND jsonb_array_length(questions) BETWEEN 0 AND 20),
    CONSTRAINT ck_cal_booking_pages_token CHECK (
        public_token_hash IS NULL
        OR public_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_booking_pages_archive CHECK (
        (state = 'ARCHIVED' AND archived_at IS NOT NULL)
        OR (state <> 'ARCHIVED' AND archived_at IS NULL)),
    CONSTRAINT ck_cal_booking_pages_retention CHECK (
        NOT legal_hold OR purge_after IS NULL)
);

CREATE UNIQUE INDEX uk_cal_booking_pages_public_token
    ON cal_booking_pages (public_token_hash)
    WHERE public_token_hash IS NOT NULL;

CREATE INDEX idx_cal_booking_pages_owner
    ON cal_booking_pages (tenant_id, owner_person_public_id, state, updated_at DESC);

CREATE INDEX idx_cal_booking_pages_group
    ON cal_booking_pages (tenant_id, owner_group_ref, state, updated_at DESC)
    WHERE owner_group_ref IS NOT NULL;

CREATE INDEX idx_cal_booking_pages_retention
    ON cal_booking_pages (purge_after, page_id)
    WHERE purge_after IS NOT NULL AND legal_hold = FALSE;

CREATE TABLE cal_booking_holds (
    hold_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    page_id UUID NOT NULL,
    host_calendar_id UUID NOT NULL,
    booker_user_id BIGINT NOT NULL,
    booker_person_public_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'HELD',
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    blocked_starts_at TIMESTAMPTZ NOT NULL,
    blocked_ends_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    fencing_token BIGINT GENERATED ALWAYS AS IDENTITY,
    page_version BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_cal_booking_holds_tenant_hold UNIQUE (tenant_id, hold_id),
    CONSTRAINT uk_cal_booking_holds_fencing UNIQUE (fencing_token),
    CONSTRAINT uk_cal_booking_holds_idempotency UNIQUE (
        tenant_id, page_id, booker_user_id, idempotency_key),
    CONSTRAINT fk_cal_booking_holds_page_host FOREIGN KEY (
        tenant_id, page_id, host_calendar_id)
        REFERENCES cal_booking_pages (tenant_id, page_id, calendar_id),
    CONSTRAINT fk_cal_booking_holds_booker FOREIGN KEY (
        tenant_id, booker_user_id, booker_person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id),
    CONSTRAINT ck_cal_booking_holds_state CHECK (
        state IN ('HELD', 'CONSUMED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_cal_booking_holds_period CHECK (
        ends_at > starts_at
        AND blocked_starts_at <= starts_at
        AND blocked_ends_at >= ends_at
        AND blocked_ends_at > blocked_starts_at),
    CONSTRAINT ck_cal_booking_holds_ttl CHECK (
        expires_at > created_at
        AND expires_at <= created_at + INTERVAL '10 minutes'),
    CONSTRAINT ck_cal_booking_holds_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$')
);

ALTER TABLE cal_booking_holds
    ADD CONSTRAINT ex_cal_booking_holds_active_period
    EXCLUDE USING gist (
        tenant_id WITH =,
        host_calendar_id WITH =,
        tstzrange(blocked_starts_at, blocked_ends_at, '[)') WITH &&)
    WHERE (state = 'HELD');

CREATE INDEX idx_cal_booking_holds_expiry
    ON cal_booking_holds (tenant_id, host_calendar_id, expires_at, fencing_token)
    WHERE state = 'HELD';

CREATE INDEX idx_cal_booking_holds_booker
    ON cal_booking_holds (tenant_id, booker_person_public_id, created_at DESC);

CREATE TABLE cal_booking_appointments (
    appointment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    page_id UUID NOT NULL,
    hold_id UUID NOT NULL,
    event_id UUID NOT NULL,
    booker_user_id BIGINT NOT NULL,
    booker_person_public_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    supersedes_appointment_id UUID,
    answers JSONB NOT NULL DEFAULT '[]'::jsonb,
    management_token_hash VARCHAR(64),
    management_token_version BIGINT NOT NULL DEFAULT 1,
    idempotency_key UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    cancelled_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_cal_booking_appointments_tenant_appointment UNIQUE (
        tenant_id, appointment_id),
    CONSTRAINT uk_cal_booking_appointments_hold UNIQUE (tenant_id, hold_id),
    CONSTRAINT uk_cal_booking_appointments_idempotency UNIQUE (
        tenant_id, page_id, booker_user_id, idempotency_key),
    CONSTRAINT fk_cal_booking_appointments_page FOREIGN KEY (tenant_id, page_id)
        REFERENCES cal_booking_pages (tenant_id, page_id),
    CONSTRAINT fk_cal_booking_appointments_hold FOREIGN KEY (tenant_id, hold_id)
        REFERENCES cal_booking_holds (tenant_id, hold_id),
    CONSTRAINT fk_cal_booking_appointments_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id),
    CONSTRAINT fk_cal_booking_appointments_booker FOREIGN KEY (
        tenant_id, booker_user_id, booker_person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id),
    CONSTRAINT fk_cal_booking_appointments_supersedes FOREIGN KEY (
        tenant_id, supersedes_appointment_id)
        REFERENCES cal_booking_appointments (tenant_id, appointment_id),
    CONSTRAINT ck_cal_booking_appointments_state CHECK (
        state IN ('CONFIRMED', 'RESCHEDULED', 'CANCELLED')),
    CONSTRAINT ck_cal_booking_appointments_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_cal_booking_appointments_answers CHECK (
        jsonb_typeof(answers) = 'array' AND jsonb_array_length(answers) BETWEEN 0 AND 20),
    CONSTRAINT ck_cal_booking_appointments_token CHECK (
        management_token_hash IS NULL
        OR management_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_booking_appointments_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_booking_appointments_cancel CHECK (
        (state = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (state <> 'CANCELLED' AND cancelled_at IS NULL)),
    CONSTRAINT ck_cal_booking_appointments_retention CHECK (
        NOT legal_hold OR purge_after IS NULL)
);

CREATE UNIQUE INDEX uk_cal_booking_appointments_active_event
    ON cal_booking_appointments (tenant_id, event_id)
    WHERE state = 'CONFIRMED';

CREATE UNIQUE INDEX uk_cal_booking_appointments_management_token
    ON cal_booking_appointments (management_token_hash)
    WHERE management_token_hash IS NOT NULL;

CREATE INDEX idx_cal_booking_appointments_booker
    ON cal_booking_appointments (
        tenant_id, booker_person_public_id, starts_at DESC, appointment_id);

CREATE INDEX idx_cal_booking_appointments_page_day
    ON cal_booking_appointments (tenant_id, page_id, starts_at, state);

CREATE INDEX idx_cal_booking_appointments_retention
    ON cal_booking_appointments (purge_after, appointment_id)
    WHERE purge_after IS NOT NULL AND legal_hold = FALSE;

CREATE OR REPLACE FUNCTION cal_booking_reject_legal_hold_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.legal_hold THEN
        RAISE EXCEPTION 'Calendar booking evidence is under legal hold'
            USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER trg_cal_booking_pages_legal_hold_delete
    BEFORE DELETE ON cal_booking_pages
    FOR EACH ROW EXECUTE FUNCTION cal_booking_reject_legal_hold_delete();

CREATE TRIGGER trg_cal_booking_appointments_legal_hold_delete
    BEFORE DELETE ON cal_booking_appointments
    FOR EACH ROW EXECUTE FUNCTION cal_booking_reject_legal_hold_delete();

COMMENT ON COLUMN cal_booking_pages.public_token_hash IS
    'SHA-256 hex digest only. Raw public capability material is returned once and never stored.';
COMMENT ON COLUMN cal_booking_appointments.management_token_hash IS
    'SHA-256 hex digest only. Raw management capability material is returned once and never stored.';
COMMENT ON COLUMN cal_booking_holds.fencing_token IS
    'Database-generated globally monotonic fencing value required for consume or reschedule.';
