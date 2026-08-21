CREATE TABLE sys_audit_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(255),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ntf_audit_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_ntf_audit_outbox_status CHECK (
        status IN ('PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')
    ),
    CONSTRAINT ck_ntf_audit_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_ntf_audit_outbox_delivery
    ON sys_audit_outbox (status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');

CREATE INDEX idx_ntf_audit_outbox_published
    ON sys_audit_outbox (published_at)
    WHERE status = 'PUBLISHED';

DO $runtime_access$
DECLARE
    runtime_role_name TEXT := '${notificationRuntimeRole}';
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid notification runtime role name';
    END IF;
    IF to_regrole(runtime_role_name) IS NULL THEN
        RAISE EXCEPTION 'Notification runtime role % must exist', runtime_role_name;
    END IF;

    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE sys_audit_outbox TO %I',
        runtime_role_name
    );
END
$runtime_access$;

GRANT INSERT ON sys_audit_outbox TO dwp_notification_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON sys_audit_outbox TO dwp_notification_worker;

COMMENT ON TABLE sys_audit_outbox IS
    'Service-local durable audit outbox for notification policy, preference, and delivery operations.';
