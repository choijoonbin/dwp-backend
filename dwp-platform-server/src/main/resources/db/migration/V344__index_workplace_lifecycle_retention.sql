-- Support bounded Workplace lifecycle, history and retention claims without table-wide sorts.

ALTER TABLE wp_bookings
    ADD COLUMN no_show_due_at TIMESTAMPTZ;

UPDATE wp_bookings
   SET no_show_due_at = starts_at
           + auto_release_minutes_snapshot * INTERVAL '1 minute';

ALTER TABLE wp_bookings
    ALTER COLUMN no_show_due_at SET NOT NULL;

CREATE FUNCTION wp_set_booking_no_show_due_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.no_show_due_at := NEW.starts_at
            + NEW.auto_release_minutes_snapshot * INTERVAL '1 minute';
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_bookings_no_show_due_at
BEFORE INSERT OR UPDATE OF starts_at, auto_release_minutes_snapshot ON wp_bookings
FOR EACH ROW EXECUTE FUNCTION wp_set_booking_no_show_due_at();

CREATE INDEX idx_wp_bookings_no_show_claim
    ON wp_bookings (tenant_id, no_show_due_at, booking_id)
    WHERE booking_status = 'RESERVED' AND require_check_in_snapshot = TRUE;

COMMENT ON COLUMN wp_bookings.no_show_due_at IS
    'Stored no-show eligibility boundary maintained from immutable booking snapshots.';

CREATE INDEX idx_wp_bookings_completion_claim
    ON wp_bookings (tenant_id, ends_at, booking_id)
    WHERE booking_status = 'CHECKED_IN'
       OR (booking_status = 'RESERVED' AND require_check_in_snapshot = FALSE);

CREATE INDEX idx_wp_bookings_user_history_bounded
    ON wp_bookings (tenant_id, user_id, starts_at, booking_id);

CREATE INDEX idx_wp_bookings_privacy_claim
    ON wp_bookings (personal_data_expires_at, booking_id)
    INCLUDE (tenant_id)
    WHERE anonymized_at IS NULL AND legal_hold = FALSE
      AND booking_status IN ('COMPLETED', 'NO_SHOW', 'RELEASED', 'CANCELLED');

CREATE INDEX idx_wp_release_windows_privacy_claim
    ON wp_resource_release_windows (personal_data_expires_at, release_window_id)
    INCLUDE (tenant_id)
    WHERE anonymized_at IS NULL AND legal_hold = FALSE;

CREATE INDEX idx_wp_experience_work_plan_retention
    ON wp_experience_work_plans (expires_at, plan_id)
    INCLUDE (tenant_id);

CREATE INDEX idx_wp_facility_request_retention
    ON wp_experience_facility_requests (updated_at, request_id)
    INCLUDE (tenant_id, resource_id)
    WHERE request_status IN ('RESOLVED', 'CANCELLED');

CREATE INDEX idx_wp_facility_closure_retention
    ON wp_experience_facility_closures (
        (GREATEST(ends_at, updated_at)), closure_id)
    INCLUDE (tenant_id, resource_id);

CREATE INDEX idx_wp_audit_events_retention
    ON wp_audit_events (occurred_at, audit_event_id);
