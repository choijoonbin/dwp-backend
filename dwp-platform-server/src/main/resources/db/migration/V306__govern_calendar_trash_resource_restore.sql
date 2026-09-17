-- Calendar trash and explicit resource-recovery contract.
ALTER TABLE cal_resource_bookings
    ADD COLUMN cancelled_for_event_trash BOOLEAN NOT NULL DEFAULT FALSE;

-- Historical rows can be identified safely when both updates were made by the
-- same trash transaction (CURRENT_TIMESTAMP is transaction-stable in PostgreSQL).
UPDATE cal_resource_bookings booking
   SET cancelled_for_event_trash = TRUE
  FROM cal_events event
 WHERE event.tenant_id = booking.tenant_id
   AND event.event_id = booking.event_id
   AND event.deleted_at IS NOT NULL
   AND booking.booking_status = 'CANCELLED'
   AND booking.updated_at = event.deleted_at;

CREATE TABLE cal_resource_restore_commands (
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    event_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(80) NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    event_version BIGINT NOT NULL,
    booking_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_user_id, idempotency_key),
    CONSTRAINT fk_cal_restore_command_tenant_event
        FOREIGN KEY (tenant_id, event_id)
        REFERENCES cal_events (tenant_id, event_id) ON DELETE CASCADE,
    CONSTRAINT fk_cal_restore_command_tenant_resource
        FOREIGN KEY (tenant_id, resource_id)
        REFERENCES cal_resources (tenant_id, resource_id),
    CONSTRAINT ck_cal_restore_command_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_restore_command_versions
        CHECK (event_version >= 0 AND booking_version >= 0)
);

CREATE INDEX idx_cal_restore_commands_event
    ON cal_resource_restore_commands (tenant_id, event_id, created_at DESC);

COMMENT ON COLUMN cal_resource_bookings.cancelled_for_event_trash IS
    'True only when an active booking was cancelled by the Calendar trash transaction.';
COMMENT ON TABLE cal_resource_restore_commands IS
    'Durable Calendar resource-rebook receipts; retries replay the exact governed result.';
