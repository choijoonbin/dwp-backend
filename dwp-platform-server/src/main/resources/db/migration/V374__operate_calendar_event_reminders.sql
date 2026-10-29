-- Versioned, user-bound Calendar reminders and an exactly-once delivery ledger.
-- The global due index intentionally does not join tenant lifecycle state so reminders
-- already accepted for an inactive tenant can still be drained or invalidated safely.

CREATE TABLE cal_event_reminder_sets (
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    owner_person_public_id UUID NOT NULL,
    event_version BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    reminder_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    invalidated_at TIMESTAMPTZ,
    invalidation_reason VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        tenant_id, event_id, owner_user_id, owner_person_public_id),
    CONSTRAINT uk_cal_event_reminder_set_version UNIQUE (
        tenant_id, event_id, owner_user_id, owner_person_public_id, version),
    CONSTRAINT fk_cal_event_reminder_set_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT ck_cal_event_reminder_set_versions CHECK (
        event_version >= 0 AND version >= 1),
    CONSTRAINT ck_cal_event_reminder_set_state CHECK (
        (reminder_state = 'ACTIVE'
            AND invalidated_at IS NULL AND invalidation_reason IS NULL)
        OR (reminder_state = 'INVALIDATED'
            AND invalidated_at IS NOT NULL
            AND invalidation_reason IN (
                'EVENT_CANCELLED', 'EVENT_RESCHEDULED', 'EVENT_VERSION_CHANGED')))
);

CREATE TABLE cal_event_reminder_definitions (
    reminder_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    owner_person_public_id UUID NOT NULL,
    set_version BIGINT NOT NULL,
    offset_minutes INTEGER NOT NULL,
    channel VARCHAR(20) NOT NULL,
    next_occurrence_starts_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ,
    delivery_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cal_event_reminder_definition_set FOREIGN KEY (
        tenant_id, event_id, owner_user_id, owner_person_public_id, set_version)
        REFERENCES cal_event_reminder_sets (
            tenant_id, event_id, owner_user_id, owner_person_public_id, version)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT uk_cal_event_reminder_definition UNIQUE (
        tenant_id, event_id, owner_user_id, owner_person_public_id,
        channel, offset_minutes),
    CONSTRAINT ck_cal_event_reminder_offset CHECK (
        offset_minutes BETWEEN 0 AND 40320),
    CONSTRAINT ck_cal_event_reminder_channel CHECK (
        channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_cal_event_reminder_schedule CHECK (
        (next_occurrence_starts_at IS NULL AND next_due_at IS NULL)
        OR (next_occurrence_starts_at IS NOT NULL AND next_due_at IS NOT NULL
            AND next_due_at <= next_occurrence_starts_at)),
    CONSTRAINT ck_cal_event_reminder_delivery_sequence CHECK (
        delivery_sequence >= 0)
);

CREATE INDEX idx_cal_event_reminder_global_due
    ON cal_event_reminder_definitions (
        next_due_at, tenant_id, reminder_id)
    WHERE next_due_at IS NOT NULL;

CREATE INDEX idx_cal_event_reminder_owner
    ON cal_event_reminder_definitions (
        tenant_id, owner_user_id, owner_person_public_id, event_id);

CREATE OR REPLACE FUNCTION enforce_calendar_event_reminder_limit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'calendar-reminder-limit:' || NEW.tenant_id || ':' || NEW.event_id
        || ':' || NEW.owner_user_id || ':' || NEW.owner_person_public_id, 0));
    IF (
        SELECT COUNT(*)
          FROM cal_event_reminder_definitions definition
         WHERE definition.tenant_id = NEW.tenant_id
           AND definition.event_id = NEW.event_id
           AND definition.owner_user_id = NEW.owner_user_id
           AND definition.owner_person_public_id = NEW.owner_person_public_id
           AND definition.reminder_id <> NEW.reminder_id
    ) >= 5 THEN
        RAISE EXCEPTION 'A Calendar event supports at most five reminders per user'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cal_event_reminder_limit
BEFORE INSERT OR UPDATE OF tenant_id, event_id, owner_user_id, owner_person_public_id
ON cal_event_reminder_definitions
FOR EACH ROW EXECUTE FUNCTION enforce_calendar_event_reminder_limit();

CREATE INDEX idx_cal_event_reminder_set_retention
    ON cal_event_reminder_sets (invalidated_at, tenant_id, event_id)
    WHERE reminder_state = 'INVALIDATED';

CREATE TABLE cal_event_reminder_delivery_ledger (
    ledger_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    reminder_id UUID NOT NULL,
    set_version BIGINT NOT NULL,
    event_version BIGINT NOT NULL,
    delivery_sequence BIGINT NOT NULL,
    occurrence_starts_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    channel VARCHAR(20) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    domain_event_id UUID,
    claimed_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cal_event_reminder_ledger_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT uk_cal_event_reminder_delivery UNIQUE (
        tenant_id, reminder_id, set_version, occurrence_starts_at),
    CONSTRAINT uk_cal_event_reminder_domain_event UNIQUE (domain_event_id),
    CONSTRAINT ck_cal_event_reminder_ledger_versions CHECK (
        set_version >= 1 AND event_version >= 0 AND delivery_sequence >= 1),
    CONSTRAINT ck_cal_event_reminder_ledger_channel CHECK (
        channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_cal_event_reminder_ledger_decision CHECK (
        decision IN ('CLAIMED', 'EMITTED')),
    CONSTRAINT ck_cal_event_reminder_ledger_state CHECK (
        (decision = 'CLAIMED' AND domain_event_id IS NULL AND completed_at IS NULL)
        OR (decision = 'EMITTED' AND domain_event_id IS NOT NULL
            AND completed_at IS NOT NULL AND completed_at >= claimed_at)),
    CONSTRAINT ck_cal_event_reminder_ledger_due CHECK (
        due_at <= occurrence_starts_at)
);

CREATE INDEX idx_cal_event_reminder_ledger_retention
    ON cal_event_reminder_delivery_ledger (
        completed_at, tenant_id, ledger_id)
    WHERE completed_at IS NOT NULL;

CREATE INDEX idx_cal_event_reminder_ledger_event
    ON cal_event_reminder_delivery_ledger (
        tenant_id, event_id, occurrence_starts_at DESC);

COMMENT ON TABLE cal_event_reminder_sets IS
    'Optimistically versioned, tenant-user-person-bound reminder definitions pinned to one Calendar event version.';
COMMENT ON TABLE cal_event_reminder_definitions IS
    'At most five IN_APP or EMAIL reminders per owner and event, with a recurrence-aware next-due cursor for bounded global claims.';
COMMENT ON TABLE cal_event_reminder_delivery_ledger IS
    'Exactly-once evidence linking each reminder occurrence to one transactional sys_domain_event_outbox event.';

CREATE OR REPLACE FUNCTION invalidate_calendar_event_reminder_sets()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    reason VARCHAR(40);
BEGIN
    IF NEW.version IS NOT DISTINCT FROM OLD.version
       AND NEW.status IS NOT DISTINCT FROM OLD.status
       AND (OLD.starts_at, OLD.ends_at, OLD.time_zone,
            OLD.recurrence_pattern, OLD.recurrence_interval, OLD.recurrence_until)
           IS NOT DISTINCT FROM
           (NEW.starts_at, NEW.ends_at, NEW.time_zone,
            NEW.recurrence_pattern, NEW.recurrence_interval, NEW.recurrence_until) THEN
        RETURN NEW;
    END IF;
    reason := CASE
        WHEN NEW.status = 'CANCELLED' THEN 'EVENT_CANCELLED'
        WHEN (OLD.starts_at, OLD.ends_at, OLD.time_zone,
              OLD.recurrence_pattern, OLD.recurrence_interval, OLD.recurrence_until)
             IS DISTINCT FROM
             (NEW.starts_at, NEW.ends_at, NEW.time_zone,
              NEW.recurrence_pattern, NEW.recurrence_interval, NEW.recurrence_until)
            THEN 'EVENT_RESCHEDULED'
        ELSE 'EVENT_VERSION_CHANGED'
    END;
    UPDATE cal_event_reminder_sets reminder_set
       SET reminder_state = 'INVALIDATED',
           invalidated_at = CURRENT_TIMESTAMP,
           invalidation_reason = reason,
           updated_at = CURRENT_TIMESTAMP
     WHERE reminder_set.tenant_id = NEW.tenant_id
       AND reminder_set.event_id = NEW.event_id
       AND (reminder_set.event_version <> NEW.version
            OR reason IN ('EVENT_CANCELLED', 'EVENT_RESCHEDULED'))
       AND reminder_set.reminder_state = 'ACTIVE';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_cal_event_reminder_invalidation
AFTER UPDATE OF version, status, starts_at, ends_at, time_zone,
                recurrence_pattern, recurrence_interval, recurrence_until
ON cal_events
FOR EACH ROW EXECUTE FUNCTION invalidate_calendar_event_reminder_sets();
