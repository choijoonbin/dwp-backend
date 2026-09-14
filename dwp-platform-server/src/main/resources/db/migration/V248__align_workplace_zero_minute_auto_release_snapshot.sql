-- The public policy DTO and tenant policy allow a zero-minute grace period.
-- Preserve immutable booking policy snapshots and make their accepted bounds match.
ALTER TABLE wp_bookings DROP CONSTRAINT ck_wp_bookings_policy_snapshot;
ALTER TABLE wp_bookings ADD CONSTRAINT ck_wp_bookings_policy_snapshot CHECK (
    jsonb_typeof(policy_snapshot) = 'object'
    AND policy_snapshot_hash ~ '^[0-9a-f]{64}$'
    AND check_in_lead_minutes_snapshot BETWEEN 0 AND 240
    AND auto_release_minutes_snapshot BETWEEN 0 AND 240
    AND booking_retention_days_snapshot BETWEEN 30 AND 3650
);
-- Existing lifecycle actions such as workplace.booking.no_show must be auditable.
ALTER TABLE wp_audit_events DROP CONSTRAINT ck_wp_audit_action;
ALTER TABLE wp_audit_events ADD CONSTRAINT ck_wp_audit_action
    CHECK (action ~ '^[a-z][a-z0-9._]{2,99}$');
