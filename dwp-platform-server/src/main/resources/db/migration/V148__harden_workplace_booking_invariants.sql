ALTER TABLE wp_bookings
    DROP CONSTRAINT ck_wp_bookings_status,
    ADD CONSTRAINT ck_wp_bookings_status CHECK (
        booking_status IN (
            'RESERVED', 'CHECKED_IN', 'RELEASED', 'CANCELLED'));

ALTER TABLE wp_bookings
    ADD CONSTRAINT ex_wp_bookings_user_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        user_id WITH =,
        (tstzrange(starts_at, ends_at, '[)')) WITH &&)
    WHERE (booking_status IN ('RESERVED', 'CHECKED_IN'));

COMMENT ON CONSTRAINT ex_wp_bookings_user_overlap ON wp_bookings IS
    'Prevents a member from holding overlapping Workplace reservations, including concurrent requests.';
