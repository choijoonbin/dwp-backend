ALTER TABLE wp_tenant_policies
    ADD COLUMN booking_retention_days INTEGER NOT NULL DEFAULT 365,
    ADD CONSTRAINT ck_wp_policy_booking_retention
        CHECK (booking_retention_days BETWEEN 30 AND 3650);

ALTER TABLE wp_policy_overrides
    DROP CONSTRAINT ck_wp_policy_overrides_patch,
    ADD CONSTRAINT ck_wp_policy_overrides_patch CHECK (
        jsonb_typeof(policy_patch) = 'object'
        AND policy_patch - ARRAY[
            'bookingWindowDays', 'maximumActiveBookings', 'minimumBookingMinutes',
            'maximumBookingMinutes', 'maximumConsecutiveDays', 'workingDayStart',
            'workingDayEnd', 'allowRecurring', 'requireCheckIn',
            'checkInLeadMinutes', 'autoReleaseMinutes',
            'allowAssignedDeskLending', 'showColleagueNames',
            'bookingRetentionDays']::TEXT[] = '{}'::jsonb);

UPDATE wp_policy_overrides override
   SET policy_patch = jsonb_set(
           override.policy_patch,
           '{bookingRetentionDays}',
           to_jsonb(policy.booking_retention_days),
           TRUE),
       updated_at = CURRENT_TIMESTAMP
  FROM wp_tenant_policies policy
 WHERE override.tenant_id = policy.tenant_id
   AND override.scope_type = 'TENANT';

ALTER TABLE wp_bookings
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN personal_data_expires_at TIMESTAMPTZ,
    ADD COLUMN anonymized_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_wp_bookings_anonymized CHECK (
        anonymized_at IS NULL
        OR (person_public_id IS NULL
            AND user_id = 0
            AND visible_to_colleagues = FALSE));

UPDATE wp_bookings booking
   SET personal_data_expires_at = booking.ends_at
           + (policy.booking_retention_days * INTERVAL '1 day')
  FROM wp_tenant_policies policy
 WHERE policy.tenant_id = booking.tenant_id
   AND booking.personal_data_expires_at IS NULL;

ALTER TABLE wp_bookings
    ALTER COLUMN personal_data_expires_at SET NOT NULL;

CREATE INDEX idx_wp_bookings_privacy_retention
    ON wp_bookings (personal_data_expires_at, tenant_id, booking_id)
    WHERE anonymized_at IS NULL AND legal_hold = FALSE
      AND booking_status IN ('COMPLETED', 'NO_SHOW', 'RELEASED', 'CANCELLED');

CREATE OR REPLACE FUNCTION wp_apply_booking_privacy_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    retention_days INTEGER;
BEGIN
    IF NEW.anonymized_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    SELECT policy.booking_retention_days
      INTO retention_days
      FROM wp_tenant_policies policy
     WHERE policy.tenant_id = NEW.tenant_id;
    NEW.personal_data_expires_at := NEW.ends_at
            + (COALESCE(retention_days, 365) * INTERVAL '1 day');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_bookings_privacy_expiry
BEFORE INSERT OR UPDATE OF ends_at, tenant_id ON wp_bookings
FOR EACH ROW EXECUTE FUNCTION wp_apply_booking_privacy_expiry();

DROP TRIGGER IF EXISTS trg_wp_audit_events_immutable ON wp_audit_events;
DROP FUNCTION IF EXISTS sys_reject_wp_audit_mutation();

CREATE OR REPLACE FUNCTION wp_reject_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('dwp.audit_retention_bypass', TRUE) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'wp_audit_events is append-only';
END;
$$;

CREATE TRIGGER trg_wp_audit_events_immutable
BEFORE UPDATE OR DELETE ON wp_audit_events
FOR EACH ROW EXECUTE FUNCTION wp_reject_audit_event_mutation();

CREATE OR REPLACE FUNCTION wp_project_audit_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO sys_platform_audit_events (
        audit_event_id, tenant_id, actor_type, actor_id, action,
        target_type, target_id, outcome, correlation_id,
        before_snapshot, after_snapshot, occurred_at)
    VALUES (
        NEW.audit_event_id,
        NEW.tenant_id,
        CASE WHEN NEW.actor_user_id = 0 THEN 'SERVICE' ELSE 'USER' END,
        NULLIF(NEW.actor_user_id, 0),
        NEW.action,
        NEW.aggregate_type,
        COALESCE(NEW.aggregate_id::TEXT, NEW.aggregate_type),
        'SUCCESS',
        left(NEW.correlation_id, 128),
        NULL,
        NEW.snapshot::TEXT,
        NEW.occurred_at)
    ON CONFLICT (audit_event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_audit_event_projection
AFTER INSERT ON wp_audit_events
FOR EACH ROW EXECUTE FUNCTION wp_project_audit_event();

INSERT INTO sys_platform_audit_events (
    audit_event_id, tenant_id, actor_type, actor_id, action,
    target_type, target_id, outcome, correlation_id,
    before_snapshot, after_snapshot, occurred_at)
SELECT event.audit_event_id,
       event.tenant_id,
       CASE WHEN event.actor_user_id = 0 THEN 'SERVICE' ELSE 'USER' END,
       NULLIF(event.actor_user_id, 0),
       event.action,
       event.aggregate_type,
       COALESCE(event.aggregate_id::TEXT, event.aggregate_type),
       'SUCCESS',
       left(event.correlation_id, 128),
       NULL,
       event.snapshot::TEXT,
       event.occurred_at
  FROM wp_audit_events event
ON CONFLICT (audit_event_id) DO NOTHING;

COMMENT ON COLUMN wp_tenant_policies.booking_retention_days IS
    'Days after a reservation ends before its direct personal data is eligible for anonymization.';
COMMENT ON COLUMN wp_bookings.legal_hold IS
    'Prevents privacy maintenance from anonymizing this reservation while an authorized investigation is active.';
COMMENT ON TRIGGER trg_wp_audit_event_projection ON wp_audit_events IS
    'Projects Workplace evidence into the central immutable audit and delivery outbox pipeline.';
