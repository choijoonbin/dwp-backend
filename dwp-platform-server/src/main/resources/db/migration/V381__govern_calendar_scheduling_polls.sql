CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE cal_scheduling_polls (
    poll_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    organizer_user_id BIGINT NOT NULL,
    organizer_person_public_id UUID NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(240) NOT NULL,
    description VARCHAR(2000),
    time_zone VARCHAR(80) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_candidate_id UUID,
    confirmed_event_id UUID,
    create_idempotency_key UUID NOT NULL,
    create_fingerprint CHAR(64) NOT NULL,
    purge_after TIMESTAMPTZ,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_cal_scheduling_polls_tenant_poll
        UNIQUE (tenant_id, poll_id),
    CONSTRAINT uk_cal_scheduling_polls_create_idempotency
        UNIQUE (tenant_id, organizer_user_id, create_idempotency_key),
    CONSTRAINT fk_cal_scheduling_polls_organizer FOREIGN KEY (
        tenant_id, organizer_user_id, organizer_person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id),
    CONSTRAINT ck_cal_scheduling_polls_state CHECK (
        state IN ('DRAFT', 'OPEN', 'AGREEMENT_REACHED',
                  'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_cal_scheduling_polls_title CHECK (
        length(btrim(title)) BETWEEN 1 AND 240 AND title = btrim(title)),
    CONSTRAINT ck_cal_scheduling_polls_zone CHECK (
        length(time_zone) BETWEEN 1 AND 80 AND time_zone = btrim(time_zone)),
    CONSTRAINT ck_cal_scheduling_polls_fingerprint CHECK (
        create_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_scheduling_polls_confirmation CHECK (
        (state = 'CONFIRMED'
            AND confirmed_candidate_id IS NOT NULL
            AND confirmed_event_id IS NOT NULL)
        OR (state <> 'CONFIRMED'
            AND confirmed_candidate_id IS NULL
            AND confirmed_event_id IS NULL)),
    CONSTRAINT ck_cal_scheduling_polls_retention CHECK (
        (purge_after IS NULL
            OR state IN ('CONFIRMED', 'CANCELLED', 'EXPIRED'))
        AND (NOT legal_hold OR purge_after IS NULL)),
    CONSTRAINT ck_cal_scheduling_polls_version CHECK (version >= 0)
);

CREATE TABLE cal_scheduling_poll_candidates (
    tenant_id BIGINT NOT NULL,
    poll_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    ordinal SMALLINT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    resource_id UUID,
    availability_state VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
    availability_reason VARCHAR(96),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, poll_id, candidate_id),
    CONSTRAINT uk_cal_scheduling_poll_candidate_ordinal
        UNIQUE (tenant_id, poll_id, ordinal),
    CONSTRAINT fk_cal_scheduling_poll_candidate_poll
        FOREIGN KEY (tenant_id, poll_id)
        REFERENCES cal_scheduling_polls (tenant_id, poll_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_scheduling_poll_candidate_resource
        FOREIGN KEY (tenant_id, resource_id)
        REFERENCES cal_resources (tenant_id, resource_id),
    CONSTRAINT ck_cal_scheduling_poll_candidate_ordinal
        CHECK (ordinal BETWEEN 1 AND 20),
    CONSTRAINT ck_cal_scheduling_poll_candidate_period
        CHECK (ends_at > starts_at
            AND ends_at <= starts_at + INTERVAL '8 hours'),
    CONSTRAINT ck_cal_scheduling_poll_candidate_availability CHECK (
        availability_state IN ('AVAILABLE', 'ROOM_UNAVAILABLE', 'HOLD_EXPIRED')
        AND ((availability_state = 'AVAILABLE' AND availability_reason IS NULL)
            OR (availability_state <> 'AVAILABLE'
                AND availability_reason ~ '^[A-Z0-9_]{1,96}$'))),
    CONSTRAINT ck_cal_scheduling_poll_candidate_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_cal_scheduling_poll_candidate_range
    ON cal_scheduling_poll_candidates (
        tenant_id, poll_id, starts_at, ends_at,
        COALESCE(resource_id, '00000000-0000-0000-0000-000000000000'::uuid));

ALTER TABLE cal_scheduling_polls
    ADD CONSTRAINT fk_cal_scheduling_polls_confirmed_candidate
        FOREIGN KEY (tenant_id, poll_id, confirmed_candidate_id)
        REFERENCES cal_scheduling_poll_candidates (
            tenant_id, poll_id, candidate_id),
    ADD CONSTRAINT fk_cal_scheduling_polls_confirmed_event
        FOREIGN KEY (tenant_id, confirmed_event_id)
        REFERENCES cal_events (tenant_id, event_id);

CREATE TABLE cal_scheduling_poll_participants (
    tenant_id BIGINT NOT NULL,
    poll_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    participant_user_id BIGINT,
    participant_person_public_id UUID,
    external_email VARCHAR(255),
    display_name VARCHAR(160) NOT NULL,
    required BOOLEAN NOT NULL,
    external_voting_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, poll_id, participant_id),
    CONSTRAINT fk_cal_scheduling_poll_participant_poll
        FOREIGN KEY (tenant_id, poll_id)
        REFERENCES cal_scheduling_polls (tenant_id, poll_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_scheduling_poll_participant_identity FOREIGN KEY (
        tenant_id, participant_user_id, participant_person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id),
    CONSTRAINT ck_cal_scheduling_poll_participant_identity CHECK (
        (participant_user_id IS NOT NULL
            AND participant_person_public_id IS NOT NULL
            AND external_email IS NULL
            AND NOT external_voting_enabled)
        OR (participant_user_id IS NULL
            AND participant_person_public_id IS NULL
            AND NULLIF(btrim(external_email), '') IS NOT NULL)),
    CONSTRAINT ck_cal_scheduling_poll_participant_name CHECK (
        length(btrim(display_name)) BETWEEN 1 AND 160
        AND display_name = btrim(display_name))
);

CREATE UNIQUE INDEX uk_cal_scheduling_poll_participant_person
    ON cal_scheduling_poll_participants (
        tenant_id, poll_id, participant_person_public_id)
    WHERE participant_person_public_id IS NOT NULL;

CREATE UNIQUE INDEX uk_cal_scheduling_poll_participant_email
    ON cal_scheduling_poll_participants (
        tenant_id, poll_id, lower(external_email))
    WHERE external_email IS NOT NULL;

CREATE TABLE cal_scheduling_poll_votes (
    tenant_id BIGINT NOT NULL,
    poll_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    choice VARCHAR(16) NOT NULL,
    comment VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, poll_id, participant_id, candidate_id),
    CONSTRAINT fk_cal_scheduling_poll_vote_participant
        FOREIGN KEY (tenant_id, poll_id, participant_id)
        REFERENCES cal_scheduling_poll_participants (
            tenant_id, poll_id, participant_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_scheduling_poll_vote_candidate
        FOREIGN KEY (tenant_id, poll_id, candidate_id)
        REFERENCES cal_scheduling_poll_candidates (
            tenant_id, poll_id, candidate_id) ON DELETE CASCADE,
    CONSTRAINT ck_cal_scheduling_poll_vote_choice CHECK (
        choice IN ('YES', 'IF_NEEDED', 'NO')),
    CONSTRAINT ck_cal_scheduling_poll_vote_version CHECK (version >= 0)
);

CREATE TABLE cal_scheduling_poll_command_receipts (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_person_public_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    poll_id UUID NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resulting_state VARCHAR(24) NOT NULL,
    resulting_version BIGINT NOT NULL,
    resulting_event_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_cal_scheduling_poll_receipt_actor FOREIGN KEY (
        tenant_id, actor_user_id, actor_person_public_id)
        REFERENCES cal_identity_links (tenant_id, user_id, person_public_id),
    CONSTRAINT fk_cal_scheduling_poll_receipt_poll
        FOREIGN KEY (tenant_id, poll_id)
        REFERENCES cal_scheduling_polls (tenant_id, poll_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_scheduling_poll_receipt_event
        FOREIGN KEY (tenant_id, resulting_event_id)
        REFERENCES cal_events (tenant_id, event_id),
    CONSTRAINT ck_cal_scheduling_poll_receipt_type CHECK (
        command_type IN ('VOTE', 'CONFIRM')),
    CONSTRAINT ck_cal_scheduling_poll_receipt_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_scheduling_poll_receipt_state CHECK (
        resulting_state IN ('DRAFT', 'OPEN', 'AGREEMENT_REACHED',
                            'CONFIRMED', 'CANCELLED', 'EXPIRED')
        AND resulting_version >= 0),
    CONSTRAINT ck_cal_scheduling_poll_receipt_event CHECK (
        (command_type = 'CONFIRM' AND resulting_event_id IS NOT NULL)
        OR (command_type = 'VOTE' AND resulting_event_id IS NULL))
);

CREATE TABLE cal_scheduling_poll_holds (
    hold_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    poll_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    participant_id UUID,
    kind VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    person_public_id UUID,
    resource_id UUID,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    fencing_token BIGINT GENERATED ALWAYS AS IDENTITY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_cal_scheduling_poll_holds_tenant_hold
        UNIQUE (tenant_id, hold_id),
    CONSTRAINT uk_cal_scheduling_poll_holds_fencing
        UNIQUE (fencing_token),
    CONSTRAINT fk_cal_scheduling_poll_hold_candidate
        FOREIGN KEY (tenant_id, poll_id, candidate_id)
        REFERENCES cal_scheduling_poll_candidates (
            tenant_id, poll_id, candidate_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_scheduling_poll_hold_participant
        FOREIGN KEY (tenant_id, poll_id, participant_id)
        REFERENCES cal_scheduling_poll_participants (
            tenant_id, poll_id, participant_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_scheduling_poll_hold_person
        FOREIGN KEY (tenant_id, person_public_id)
        REFERENCES cal_identity_links (tenant_id, person_public_id),
    CONSTRAINT fk_cal_scheduling_poll_hold_resource
        FOREIGN KEY (tenant_id, resource_id)
        REFERENCES cal_resources (tenant_id, resource_id),
    CONSTRAINT ck_cal_scheduling_poll_hold_kind CHECK (
        (kind = 'PEOPLE_SOFT'
            AND participant_id IS NOT NULL
            AND person_public_id IS NOT NULL
            AND resource_id IS NULL)
        OR (kind = 'ROOM_HARD'
            AND participant_id IS NULL
            AND person_public_id IS NULL
            AND resource_id IS NOT NULL)),
    CONSTRAINT ck_cal_scheduling_poll_hold_state CHECK (
        state IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_cal_scheduling_poll_hold_period CHECK (
        ends_at > starts_at AND expires_at > created_at),
    CONSTRAINT ck_cal_scheduling_poll_hold_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_cal_scheduling_poll_people_hold_active
    ON cal_scheduling_poll_holds (
        tenant_id, poll_id, candidate_id, participant_id)
    WHERE kind = 'PEOPLE_SOFT' AND state = 'ACTIVE';

CREATE UNIQUE INDEX uk_cal_scheduling_poll_room_hold_active
    ON cal_scheduling_poll_holds (
        tenant_id, poll_id, candidate_id, resource_id)
    WHERE kind = 'ROOM_HARD' AND state = 'ACTIVE';

ALTER TABLE cal_scheduling_poll_holds
    ADD CONSTRAINT ex_cal_scheduling_poll_room_hold_period
    EXCLUDE USING gist (
        tenant_id WITH =,
        resource_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&)
    WHERE (kind = 'ROOM_HARD' AND state = 'ACTIVE');

CREATE OR REPLACE FUNCTION cal_scheduling_poll_lock_room(
    value_tenant_id BIGINT,
    value_resource_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        value_tenant_id::text || ':calendar-room:' || value_resource_id::text, 0));
    PERFORM 1
      FROM cal_resources
     WHERE tenant_id = value_tenant_id
       AND resource_id = value_resource_id
     FOR UPDATE;
END
$$;

CREATE OR REPLACE FUNCTION cal_scheduling_poll_guard_room_hold()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_starts_at TIMESTAMPTZ;
    expected_ends_at TIMESTAMPTZ;
    expected_resource_id UUID;
    expected_person_public_id UUID;
BEGIN
    SELECT starts_at, ends_at, resource_id
      INTO expected_starts_at, expected_ends_at, expected_resource_id
      FROM cal_scheduling_poll_candidates
     WHERE tenant_id = NEW.tenant_id
       AND poll_id = NEW.poll_id
       AND candidate_id = NEW.candidate_id;

    IF NOT FOUND
       OR NEW.starts_at <> expected_starts_at
       OR NEW.ends_at <> expected_ends_at
       OR (NEW.kind = 'ROOM_HARD' AND NEW.resource_id IS DISTINCT FROM expected_resource_id)
    THEN
        RAISE EXCEPTION 'Calendar poll hold does not match its candidate'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.kind = 'PEOPLE_SOFT' THEN
        SELECT participant_person_public_id
          INTO expected_person_public_id
          FROM cal_scheduling_poll_participants
         WHERE tenant_id = NEW.tenant_id
           AND poll_id = NEW.poll_id
           AND participant_id = NEW.participant_id;
        IF NOT FOUND OR NEW.person_public_id IS DISTINCT FROM expected_person_public_id THEN
            RAISE EXCEPTION 'Calendar poll people hold does not match its participant'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.kind = 'ROOM_HARD' AND NEW.state = 'ACTIVE' THEN
        PERFORM cal_scheduling_poll_lock_room(NEW.tenant_id, NEW.resource_id);
        IF EXISTS (
            SELECT 1
              FROM cal_resource_bookings booking
             WHERE booking.tenant_id = NEW.tenant_id
               AND booking.resource_id = NEW.resource_id
               AND booking.booking_status IN ('PENDING', 'CONFIRMED')
               AND tstzrange(booking.starts_at, booking.ends_at, '[)')
                   && tstzrange(NEW.starts_at, NEW.ends_at, '[)'))
        THEN
            RAISE EXCEPTION 'Calendar poll room hold conflicts with an active booking'
                USING ERRCODE = '23P01';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_cal_scheduling_poll_guard_room_hold
    BEFORE INSERT OR UPDATE OF state, starts_at, ends_at, resource_id
    ON cal_scheduling_poll_holds
    FOR EACH ROW EXECUTE FUNCTION cal_scheduling_poll_guard_room_hold();

CREATE OR REPLACE FUNCTION cal_resource_booking_guard_poll_hold()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.booking_status IN ('PENDING', 'CONFIRMED') THEN
        PERFORM cal_scheduling_poll_lock_room(NEW.tenant_id, NEW.resource_id);
        IF EXISTS (
            SELECT 1
              FROM cal_scheduling_poll_holds hold_row
             WHERE hold_row.tenant_id = NEW.tenant_id
               AND hold_row.resource_id = NEW.resource_id
               AND hold_row.kind = 'ROOM_HARD'
               AND hold_row.state = 'ACTIVE'
               AND hold_row.expires_at > CURRENT_TIMESTAMP
               AND tstzrange(hold_row.starts_at, hold_row.ends_at, '[)')
                   && tstzrange(NEW.starts_at, NEW.ends_at, '[)'))
        THEN
            RAISE EXCEPTION 'Calendar resource booking conflicts with an active poll hold'
                USING ERRCODE = '23P01';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_cal_resource_booking_guard_poll_hold
    BEFORE INSERT OR UPDATE OF booking_status, starts_at, ends_at, resource_id
    ON cal_resource_bookings
    FOR EACH ROW EXECUTE FUNCTION cal_resource_booking_guard_poll_hold();

CREATE TABLE cal_scheduling_poll_audit_events (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    poll_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(80) NOT NULL,
    before_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(160),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cal_scheduling_poll_audit_poll
        FOREIGN KEY (tenant_id, poll_id)
        REFERENCES cal_scheduling_polls (tenant_id, poll_id) ON DELETE CASCADE,
    CONSTRAINT ck_cal_scheduling_poll_audit_action CHECK (
        action ~ '^calendar[.]poll[.][a-z0-9.]{2,60}$'),
    CONSTRAINT ck_cal_scheduling_poll_audit_snapshots CHECK (
        jsonb_typeof(before_snapshot) = 'object'
        AND jsonb_typeof(after_snapshot) = 'object')
);

CREATE INDEX idx_cal_scheduling_polls_organizer
    ON cal_scheduling_polls (
        tenant_id, organizer_person_public_id, updated_at DESC, poll_id);

CREATE INDEX idx_cal_scheduling_polls_retention
    ON cal_scheduling_polls (purge_after, poll_id)
    WHERE purge_after IS NOT NULL AND legal_hold = FALSE;

CREATE INDEX idx_cal_scheduling_poll_participants_person
    ON cal_scheduling_poll_participants (
        tenant_id, participant_person_public_id, poll_id)
    WHERE participant_person_public_id IS NOT NULL;

CREATE INDEX idx_cal_scheduling_poll_votes_candidate
    ON cal_scheduling_poll_votes (
        tenant_id, poll_id, candidate_id, choice, participant_id);

CREATE INDEX idx_cal_scheduling_poll_holds_expiry
    ON cal_scheduling_poll_holds (
        expires_at, hold_id, tenant_id)
    WHERE state = 'ACTIVE';

CREATE INDEX idx_cal_scheduling_poll_audit_time
    ON cal_scheduling_poll_audit_events (
        tenant_id, poll_id, occurred_at DESC, audit_id);

CREATE OR REPLACE FUNCTION cal_scheduling_poll_reject_legal_hold_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.legal_hold THEN
        RAISE EXCEPTION 'Calendar scheduling poll is under legal hold'
            USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
END
$$;

CREATE TRIGGER trg_cal_scheduling_poll_legal_hold_delete
    BEFORE DELETE ON cal_scheduling_polls
    FOR EACH ROW EXECUTE FUNCTION cal_scheduling_poll_reject_legal_hold_delete();

COMMENT ON TABLE cal_scheduling_polls IS
    'Tenant-scoped scheduling polls with optimistic state, retention, and confirmation evidence.';
COMMENT ON TABLE cal_scheduling_poll_holds IS
    'Soft people evidence and database-fenced hard room holds for scheduling poll candidates.';
COMMENT ON COLUMN cal_scheduling_poll_command_receipts.request_fingerprint IS
    'SHA-256 canonical command digest; raw capability or transport data is never stored.';
