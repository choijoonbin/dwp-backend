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

CREATE OR REPLACE FUNCTION sys_provider_audit_to_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_tenant_id BIGINT;
    actor_display_name VARCHAR(200);
    actor_role VARCHAR(50);
BEGIN
    SELECT COALESCE(tenant.auth_tenant_id, actor.auth_tenant_id, 1),
           actor.display_name,
           actor.role_code
      INTO resolved_tenant_id, actor_display_name, actor_role
      FROM (SELECT 1) anchor
      LEFT JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = NEW.provider_tenant_id
      LEFT JOIN prv_operators actor
        ON actor.provider_operator_id = NEW.provider_operator_id;

    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), NEW.audit_event_id, resolved_tenant_id,
        jsonb_build_object(
            'eventId', NEW.audit_event_id,
            'eventVersion', '1.0',
            'occurredAt', NEW.occurred_at,
            'tenantId', resolved_tenant_id,
            'category', CASE
                WHEN NEW.event_category IN ('PROVISIONING', 'ONBOARDING')
                    OR NEW.action LIKE 'provider.tenant.%' THEN 'PROVISIONING'
                WHEN NEW.event_category IN ('SUPPORT', 'SECURITY')
                    OR NEW.action LIKE '%support%' THEN 'AUTHORIZATION'
                ELSE 'ADMIN_CHANGE'
            END,
            'action', NEW.action,
            'outcome', NEW.outcome,
            'severity', CASE
                WHEN NEW.outcome = 'FAILED' THEN 'HIGH'
                WHEN NEW.outcome = 'DENIED' THEN 'MEDIUM'
                WHEN NEW.action LIKE '%support%' THEN 'MEDIUM'
                ELSE 'INFO'
            END,
            'riskScore', CASE
                WHEN NEW.outcome = 'FAILED' THEN 78
                WHEN NEW.outcome = 'DENIED' THEN 62
                WHEN NEW.action LIKE '%support%' THEN 55
                WHEN NEW.action LIKE '%delete%' OR NEW.action LIKE '%retire%' THEN 48
                ELSE 20
            END,
            'actorType', 'USER',
            'actorId', NEW.actor_id::TEXT,
            'actorDisplayName', actor_display_name,
            'actorRoles', CASE
                WHEN actor_role IS NULL THEN '[]'::jsonb
                ELSE jsonb_build_array(actor_role)
            END,
            'sourceService', 'dwp-provider-server',
            'sourceModule', 'provider-control-plane',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', NEW.target_type,
            'targetId', NEW.target_id,
            'targetDisplayName', NEW.target_id,
            'correlationId', NEW.correlation_id,
            'beforeState', '{}'::jsonb,
            'afterState', COALESCE(NEW.redacted_snapshot, '{}'::jsonb),
            'metadata', jsonb_strip_nulls(jsonb_build_object(
                'legacyAuditEventId', NEW.audit_event_id,
                'providerTenantId', NEW.provider_tenant_id,
                'organizationId', NEW.organization_id,
                'providerEventCategory', NEW.event_category)),
            'retentionClass', CASE
                WHEN NEW.event_category IN ('SUPPORT', 'SECURITY') THEN 'EXTENDED'
                ELSE 'STANDARD'
            END),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_provider_audit_outbox
AFTER INSERT ON prv_audit_events
FOR EACH ROW EXECUTE FUNCTION sys_provider_audit_to_outbox();

INSERT INTO sys_audit_outbox (
    outbox_id, event_id, tenant_id, payload, status, attempt_count,
    available_at, created_at, updated_at)
SELECT
    gen_random_uuid(), event.audit_event_id,
    COALESCE(tenant.auth_tenant_id, actor.auth_tenant_id, 1),
    jsonb_build_object(
        'eventId', event.audit_event_id,
        'eventVersion', '1.0',
        'occurredAt', event.occurred_at,
        'tenantId', COALESCE(tenant.auth_tenant_id, actor.auth_tenant_id, 1),
        'category', CASE
            WHEN event.event_category IN ('PROVISIONING', 'ONBOARDING')
                OR event.action LIKE 'provider.tenant.%' THEN 'PROVISIONING'
            WHEN event.event_category IN ('SUPPORT', 'SECURITY')
                OR event.action LIKE '%support%' THEN 'AUTHORIZATION'
            ELSE 'ADMIN_CHANGE'
        END,
        'action', event.action,
        'outcome', event.outcome,
        'severity', CASE
            WHEN event.outcome = 'FAILED' THEN 'HIGH'
            WHEN event.outcome = 'DENIED' OR event.action LIKE '%support%' THEN 'MEDIUM'
            ELSE 'INFO'
        END,
        'riskScore', CASE
            WHEN event.outcome = 'FAILED' THEN 78
            WHEN event.outcome = 'DENIED' THEN 62
            WHEN event.action LIKE '%support%' THEN 55
            WHEN event.action LIKE '%delete%' OR event.action LIKE '%retire%' THEN 48
            ELSE 20
        END,
        'actorType', 'USER',
        'actorId', event.actor_id::TEXT,
        'actorDisplayName', actor.display_name,
        'actorRoles', CASE
            WHEN actor.role_code IS NULL THEN '[]'::jsonb
            ELSE jsonb_build_array(actor.role_code)
        END,
        'sourceService', 'dwp-provider-server',
        'sourceModule', 'provider-control-plane',
        'environment', 'local',
        'targetType', event.target_type,
        'targetId', event.target_id,
        'targetDisplayName', event.target_id,
        'correlationId', event.correlation_id,
        'beforeState', '{}'::jsonb,
        'afterState', COALESCE(event.redacted_snapshot, '{}'::jsonb),
        'metadata', jsonb_strip_nulls(jsonb_build_object(
            'legacyAuditEventId', event.audit_event_id,
            'providerTenantId', event.provider_tenant_id,
            'organizationId', event.organization_id,
            'providerEventCategory', event.event_category)),
        'retentionClass', CASE
            WHEN event.event_category IN ('SUPPORT', 'SECURITY') THEN 'EXTENDED'
            ELSE 'STANDARD'
        END),
    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM prv_audit_events event
LEFT JOIN prv_tenants tenant
  ON tenant.provider_tenant_id = event.provider_tenant_id
LEFT JOIN prv_operators actor
  ON actor.provider_operator_id = event.provider_operator_id
ON CONFLICT (event_id) DO NOTHING;
