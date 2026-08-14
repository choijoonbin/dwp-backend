CREATE TABLE cal_identity_links (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    person_public_id UUID NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT uk_cal_identity_person UNIQUE (tenant_id, person_public_id)
);

ALTER TABLE cal_resource_bookings
    ADD COLUMN requested_by BIGINT,
    ADD COLUMN decision_note VARCHAR(1000),
    ADD COLUMN decided_at TIMESTAMPTZ,
    ADD COLUMN decided_by BIGINT;

UPDATE cal_resource_bookings
   SET requested_by = COALESCE(created_by, updated_by)
 WHERE requested_by IS NULL;

ALTER TABLE cal_resource_bookings
    ALTER COLUMN requested_by SET NOT NULL;

CREATE INDEX idx_cal_resource_booking_status
    ON cal_resource_bookings (tenant_id, booking_status, starts_at)
    WHERE booking_status = 'PENDING';

COMMENT ON TABLE cal_identity_links IS
    'Runtime bridge between IAM user identifiers and authoritative people-directory public identifiers.';
COMMENT ON COLUMN cal_resource_bookings.decision_note IS
    'Operator decision rationale for governed room and equipment reservations.';
