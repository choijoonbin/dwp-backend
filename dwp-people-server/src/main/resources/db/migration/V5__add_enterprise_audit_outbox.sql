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
    CONSTRAINT ck_sys_audit_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_sys_audit_outbox_status
        CHECK (status IN ('PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_sys_audit_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_sys_audit_outbox_delivery
    ON sys_audit_outbox(status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');
CREATE INDEX idx_sys_audit_outbox_published
    ON sys_audit_outbox(published_at)
    WHERE status = 'PUBLISHED';

CREATE OR REPLACE FUNCTION sys_people_audit_to_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), NEW.audit_event_id, NEW.tenant_id,
        jsonb_build_object(
            'eventId', NEW.audit_event_id,
            'eventVersion', '1.0',
            'occurredAt', NEW.occurred_at,
            'tenantId', NEW.tenant_id,
            'category', CASE
                WHEN NEW.action LIKE 'people.hris-%' OR NEW.action LIKE 'people.import.%'
                    THEN 'PROVISIONING'
                ELSE 'ADMIN_CHANGE'
            END,
            'action', NEW.action,
            'outcome', NEW.outcome,
            'severity', CASE
                WHEN NEW.outcome = 'FAILED' THEN 'HIGH'
                WHEN NEW.outcome = 'DENIED' THEN 'MEDIUM'
                ELSE 'INFO'
            END,
            'riskScore', CASE
                WHEN NEW.outcome = 'FAILED' THEN 75
                WHEN NEW.outcome = 'DENIED' THEN 55
                ELSE 20
            END,
            'actorType', NEW.actor_type,
            'actorId', NEW.actor_id,
            'actorRoles', '[]'::jsonb,
            'sourceService', 'dwp-people-server',
            'sourceModule', 'workforce-and-hris',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', NEW.target_type,
            'targetId', NEW.target_id,
            'targetDisplayName', NEW.target_id,
            'correlationId', NEW.correlation_id,
            'beforeState', COALESCE(NEW.before_snapshot, '{}'::jsonb),
            'afterState', COALESCE(NEW.after_snapshot, '{}'::jsonb),
            'metadata', jsonb_strip_nulls(jsonb_build_object(
                'legacyAuditEventId', NEW.audit_event_id,
                'sourceSystemId', NEW.source_system_id)),
            'retentionClass', 'EXTENDED'),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_people_audit_outbox
AFTER INSERT ON sys_people_audit_events
FOR EACH ROW EXECUTE FUNCTION sys_people_audit_to_outbox();

INSERT INTO sys_audit_outbox (
    outbox_id, event_id, tenant_id, payload, status, attempt_count,
    available_at, created_at, updated_at)
SELECT
    gen_random_uuid(), event.audit_event_id, event.tenant_id,
    jsonb_build_object(
        'eventId', event.audit_event_id,
        'eventVersion', '1.0',
        'occurredAt', event.occurred_at,
        'tenantId', event.tenant_id,
        'category', CASE
            WHEN event.action LIKE 'people.hris-%' OR event.action LIKE 'people.import.%'
                THEN 'PROVISIONING'
            ELSE 'ADMIN_CHANGE'
        END,
        'action', event.action,
        'outcome', event.outcome,
        'severity', CASE
            WHEN event.outcome = 'FAILED' THEN 'HIGH'
            WHEN event.outcome = 'DENIED' THEN 'MEDIUM'
            ELSE 'INFO'
        END,
        'riskScore', CASE
            WHEN event.outcome = 'FAILED' THEN 75
            WHEN event.outcome = 'DENIED' THEN 55
            ELSE 20
        END,
        'actorType', event.actor_type,
        'actorId', event.actor_id,
        'actorRoles', '[]'::jsonb,
        'sourceService', 'dwp-people-server',
        'sourceModule', 'workforce-and-hris',
        'environment', 'local',
        'targetType', event.target_type,
        'targetId', event.target_id,
        'targetDisplayName', event.target_id,
        'correlationId', event.correlation_id,
        'beforeState', COALESCE(event.before_snapshot, '{}'::jsonb),
        'afterState', COALESCE(event.after_snapshot, '{}'::jsonb),
        'metadata', jsonb_strip_nulls(jsonb_build_object(
            'legacyAuditEventId', event.audit_event_id,
            'sourceSystemId', event.source_system_id)),
        'retentionClass', 'EXTENDED'),
    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM sys_people_audit_events event
ON CONFLICT (event_id) DO NOTHING;
