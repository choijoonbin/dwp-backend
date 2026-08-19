ALTER TABLE wp_bookings
    ADD COLUMN idempotency_key VARCHAR(160),
    ADD COLUMN request_fingerprint CHAR(64),
    ADD CONSTRAINT ck_wp_bookings_idempotency_pair CHECK (
        (idempotency_key IS NULL AND request_fingerprint IS NULL)
        OR (idempotency_key IS NOT NULL
            AND idempotency_key ~ '^[!-~]{1,160}$'
            AND request_fingerprint ~ '^[0-9a-f]{64}$'));

CREATE UNIQUE INDEX uk_wp_bookings_idempotency
    ON wp_bookings (tenant_id, user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_wp_bookings_admin_search
    ON wp_bookings (tenant_id, starts_at DESC, booking_status, resource_id, user_id);

COMMENT ON COLUMN wp_bookings.idempotency_key IS
    'Client supplied opaque key scoped to tenant and booking owner.';
COMMENT ON COLUMN wp_bookings.request_fingerprint IS
    'SHA-256 fingerprint of the normalized create-booking command.';
