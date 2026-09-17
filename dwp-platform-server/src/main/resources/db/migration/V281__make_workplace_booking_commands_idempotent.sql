ALTER TABLE wp_bookings
    ADD CONSTRAINT uk_wp_bookings_tenant_booking UNIQUE (tenant_id, booking_id);

ALTER TABLE wp_audit_events
    ADD CONSTRAINT uk_wp_audit_events_tenant_event UNIQUE (tenant_id, audit_event_id);

CREATE TABLE wp_booking_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    booking_id UUID NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    result_snapshot JSONB NOT NULL,
    audit_event_id UUID NOT NULL,
    correlation_id VARCHAR(160),
    completed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, booking_id)
        REFERENCES wp_bookings(tenant_id, booking_id),
    FOREIGN KEY (tenant_id, audit_event_id)
        REFERENCES wp_audit_events(tenant_id, audit_event_id),
    CONSTRAINT ck_wp_booking_command_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_booking_command_type CHECK (
        command_type IN ('CHECK_IN', 'CANCEL', 'RELEASE', 'RELOCATE')),
    CONSTRAINT ck_wp_booking_command_key CHECK (
        idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_booking_command_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_booking_command_snapshot CHECK (
        jsonb_typeof(result_snapshot) = 'object'),
    CONSTRAINT ck_wp_booking_command_correlation CHECK (
        correlation_id IS NULL OR length(correlation_id) BETWEEN 1 AND 160)
);

CREATE INDEX idx_wp_booking_commands_booking
    ON wp_booking_commands(tenant_id, booking_id, completed_at DESC, command_id);

CREATE FUNCTION wp_delete_anonymized_booking_commands()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM wp_booking_commands command
     WHERE command.tenant_id = NEW.tenant_id
       AND command.booking_id = NEW.booking_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wp_booking_command_privacy_expiry
AFTER UPDATE OF anonymized_at ON wp_bookings
FOR EACH ROW
WHEN (OLD.anonymized_at IS NULL AND NEW.anonymized_at IS NOT NULL)
EXECUTE FUNCTION wp_delete_anonymized_booking_commands();

COMMENT ON TABLE wp_booking_commands IS
    'Durable receipts for member booking lifecycle commands. The result snapshot is replayed exactly after a committed command.';
COMMENT ON COLUMN wp_booking_commands.idempotency_key IS
    'Visible ASCII key serialized per tenant and actor before fingerprint comparison or mutation.';
COMMENT ON COLUMN wp_booking_commands.audit_event_id IS
    'Immutable Workplace audit evidence projected by the existing trigger to the central audit/outbox pipeline.';
COMMENT ON TRIGGER trg_wp_booking_command_privacy_expiry ON wp_bookings IS
    'Deletes replay snapshots when the owning booking crosses its governed personal-data expiry boundary.';
