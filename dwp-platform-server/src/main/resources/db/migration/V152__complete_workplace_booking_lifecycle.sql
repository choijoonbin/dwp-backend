ALTER TABLE wp_bookings
    DROP CONSTRAINT ck_wp_bookings_status,
    ADD CONSTRAINT ck_wp_bookings_status CHECK (
        booking_status IN (
            'RESERVED', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW', 'RELEASED', 'CANCELLED'));

CREATE INDEX idx_wp_bookings_lifecycle_sweep
    ON wp_bookings (tenant_id, starts_at, ends_at)
    WHERE booking_status IN ('RESERVED', 'CHECKED_IN');

CREATE INDEX idx_wp_audit_events_tenant_time
    ON wp_audit_events (tenant_id, occurred_at DESC);

ALTER TABLE wp_tenant_policies
    ALTER COLUMN show_colleague_names SET DEFAULT FALSE;

UPDATE wp_tenant_policies
   SET show_colleague_names = FALSE,
       updated_at = CURRENT_TIMESTAMP
 WHERE version = 0;
