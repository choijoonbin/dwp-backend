-- Persist independent round-robin cursors so lifecycle batches never scan or sort every tenant.

CREATE TABLE wp_booking_lifecycle_cursors (
    lifecycle_kind VARCHAR(24) PRIMARY KEY,
    last_tenant_id BIGINT,
    completed_cycles BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_wp_booking_lifecycle_cursor_kind CHECK (
        lifecycle_kind IN ('NO_SHOW', 'COMPLETION')),
    CONSTRAINT ck_wp_booking_lifecycle_cursor_cycles CHECK (completed_cycles >= 0)
);

INSERT INTO wp_booking_lifecycle_cursors (lifecycle_kind)
VALUES ('NO_SHOW'), ('COMPLETION');

COMMENT ON TABLE wp_booking_lifecycle_cursors IS
    'Durable tenant cursors bounding and fairly rotating Workplace booking lifecycle scans.';
