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

CREATE OR REPLACE FUNCTION sys_identity_audit_to_outbox()
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
                WHEN NEW.action LIKE 'provisioning.%' THEN 'PROVISIONING'
                WHEN NEW.action LIKE 'access.%' OR NEW.action LIKE 'identity.%' THEN 'AUTHORIZATION'
                ELSE 'ADMIN_CHANGE'
            END,
            'action', NEW.action,
            'outcome', 'SUCCESS',
            'severity', 'INFO',
            'riskScore', 15,
            'actorType', 'USER',
            'actorId', NEW.actor_id::TEXT,
            'actorRoles', '[]'::jsonb,
            'sourceService', 'dwp-auth-server',
            'sourceModule', 'identity-governance',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', NEW.target_type,
            'targetId', NEW.target_id,
            'targetDisplayName', NEW.target_id,
            'correlationId', NEW.correlation_id,
            'beforeState', CASE WHEN NEW.before_snapshot IS NULL THEN '{}'::jsonb ELSE NEW.before_snapshot::jsonb END,
            'afterState', CASE WHEN NEW.after_snapshot IS NULL THEN '{}'::jsonb ELSE NEW.after_snapshot::jsonb END,
            'metadata', jsonb_build_object('legacyAuditEventId', NEW.audit_event_id),
            'retentionClass', 'STANDARD'),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_identity_audit_outbox
AFTER INSERT ON sys_identity_audit_events
FOR EACH ROW EXECUTE FUNCTION sys_identity_audit_to_outbox();

CREATE OR REPLACE FUNCTION sys_login_history_to_audit_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    generated_event_id UUID := gen_random_uuid();
BEGIN
    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), generated_event_id, NEW.tenant_id,
        jsonb_build_object(
            'eventId', generated_event_id,
            'eventVersion', '1.0',
            'occurredAt', NEW.created_at AT TIME ZONE 'UTC',
            'tenantId', NEW.tenant_id,
            'category', 'AUTHENTICATION',
            'action', CASE WHEN NEW.success THEN 'authentication.login.succeeded' ELSE 'authentication.login.failed' END,
            'outcome', CASE WHEN NEW.success THEN 'SUCCESS' ELSE 'DENIED' END,
            'severity', CASE WHEN NEW.success THEN 'INFO' ELSE 'MEDIUM' END,
            'riskScore', CASE WHEN NEW.success THEN 5 ELSE 45 END,
            'actorType', CASE WHEN NEW.user_id IS NULL THEN 'ANONYMOUS' ELSE 'USER' END,
            'actorId', NEW.user_id::TEXT,
            'actorPrincipal', LOWER(NEW.principal),
            'actorRoles', '[]'::jsonb,
            'sourceService', 'dwp-auth-server',
            'sourceModule', 'authentication',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', 'USER_ACCOUNT',
            'targetId', COALESCE(NEW.user_id::TEXT, 'principal:' || md5(LOWER(NEW.principal))),
            'targetDisplayName', CASE WHEN NEW.user_id IS NULL THEN 'Unknown account' ELSE LOWER(NEW.principal) END,
            'reason', NEW.failure_reason,
            'authenticationMethod', NEW.provider_type,
            'beforeState', '{}'::jsonb,
            'afterState', '{}'::jsonb,
            'metadata', jsonb_strip_nulls(jsonb_build_object(
                'loginHistoryId', NEW.login_history_id,
                'providerType', NEW.provider_type,
                'providerId', NEW.provider_id,
                'failureReason', NEW.failure_reason,
                'networkContextPresent', NEW.ip_address IS NOT NULL,
                'clientContextPresent', NEW.user_agent IS NOT NULL)),
            'retentionClass', 'EXTENDED'),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_login_history_audit_outbox
AFTER INSERT ON sys_login_histories
FOR EACH ROW EXECUTE FUNCTION sys_login_history_to_audit_outbox();

CREATE OR REPLACE FUNCTION sys_auth_session_to_audit_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    generated_event_id UUID := gen_random_uuid();
    event_action TEXT;
    event_time TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'INSERT' THEN
        event_action := 'authentication.session.created';
        event_time := COALESCE(NEW.issued_at, NEW.created_at AT TIME ZONE 'UTC', CURRENT_TIMESTAMP);
    ELSIF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
        event_action := 'authentication.session.revoked';
        event_time := NEW.revoked_at;
    ELSE
        RETURN NEW;
    END IF;

    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), generated_event_id, NEW.tenant_id,
        jsonb_build_object(
            'eventId', generated_event_id,
            'eventVersion', '1.0',
            'occurredAt', event_time,
            'tenantId', NEW.tenant_id,
            'category', 'AUTHENTICATION',
            'action', event_action,
            'outcome', 'SUCCESS',
            'severity', CASE WHEN event_action LIKE '%.revoked' THEN 'LOW' ELSE 'INFO' END,
            'riskScore', CASE WHEN event_action LIKE '%.revoked' THEN 20 ELSE 5 END,
            'actorType', 'USER',
            'actorId', NEW.user_id::TEXT,
            'actorRoles', '[]'::jsonb,
            'sourceService', 'dwp-auth-server',
            'sourceModule', 'session-management',
            'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
            'targetType', 'AUTH_SESSION',
            'targetId', NEW.session_id::TEXT,
            'targetDisplayName', 'Session ' || LEFT(NEW.session_id::TEXT, 8),
            'authenticationMethod', 'SESSION',
            'beforeState', '{}'::jsonb,
            'afterState', '{}'::jsonb,
            'metadata', jsonb_build_object(
                'sessionFamilyId', NEW.session_family_id,
                'expiresAt', NEW.expires_at,
                'revokedAt', NEW.revoked_at,
                'networkContextPresent', NEW.ip_address IS NOT NULL,
                'clientContextPresent', NEW.user_agent IS NOT NULL),
            'retentionClass', 'EXTENDED'),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_auth_session_created_audit_outbox
AFTER INSERT ON sys_auth_sessions
FOR EACH ROW EXECUTE FUNCTION sys_auth_session_to_audit_outbox();

CREATE TRIGGER trg_sys_auth_session_revoked_audit_outbox
AFTER UPDATE OF revoked_at ON sys_auth_sessions
FOR EACH ROW EXECUTE FUNCTION sys_auth_session_to_audit_outbox();

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
            WHEN event.action LIKE 'provisioning.%' THEN 'PROVISIONING'
            WHEN event.action LIKE 'access.%' OR event.action LIKE 'identity.%' THEN 'AUTHORIZATION'
            ELSE 'ADMIN_CHANGE'
        END,
        'action', event.action,
        'outcome', 'SUCCESS',
        'severity', 'INFO',
        'riskScore', 15,
        'actorType', 'USER',
        'actorId', event.actor_id::TEXT,
        'actorRoles', '[]'::jsonb,
        'sourceService', 'dwp-auth-server',
        'sourceModule', 'identity-governance',
        'environment', 'local',
        'targetType', event.target_type,
        'targetId', event.target_id,
        'targetDisplayName', event.target_id,
        'correlationId', event.correlation_id,
        'beforeState', CASE WHEN event.before_snapshot IS NULL THEN '{}'::jsonb ELSE event.before_snapshot::jsonb END,
        'afterState', CASE WHEN event.after_snapshot IS NULL THEN '{}'::jsonb ELSE event.after_snapshot::jsonb END,
        'metadata', jsonb_build_object('legacyAuditEventId', event.audit_event_id),
        'retentionClass', 'STANDARD'),
    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM sys_identity_audit_events event
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO com_roles (tenant_id, code, name, description, status)
SELECT tenant_id, 'AUDITOR', 'Audit investigator',
       'Read, investigate, and export tenant audit evidence', 'ACTIVE'
FROM com_tenants
ON CONFLICT (tenant_id, code) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (tenant_id, code, name, description, status)
SELECT tenant_id, 'AUDIT_ADMIN', 'Audit administrator',
       'Manage audit evidence, policy, integrity, and integrations', 'ACTIVE'
FROM com_tenants
ON CONFLICT (tenant_id, code) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_resources (tenant_id, type, key, name, enabled)
SELECT tenant_id, 'ADMIN', resource_key, resource_name, TRUE
FROM com_tenants
CROSS JOIN (VALUES
    ('ADMIN.AUDIT_VIEW', 'Audit and compliance overview'),
    ('ADMIN.AUDIT_INVESTIGATE', 'Audit findings and investigations'),
    ('ADMIN.AUDIT_EXPORT', 'Audit evidence export'),
    ('ADMIN.AUDIT_CONFIGURE', 'Audit retention and integrity configuration')
) AS resources(resource_key, resource_name)
ON CONFLICT (tenant_id, type, key) DO UPDATE
SET name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id, effect)
SELECT role.tenant_id, role.role_id, resource.resource_id, permission.permission_id, 'ALLOW'
FROM com_roles role
JOIN com_resources resource
  ON resource.tenant_id = role.tenant_id
 AND resource.type = 'ADMIN'
JOIN com_permissions permission
  ON permission.code = CASE resource.key
      WHEN 'ADMIN.AUDIT_VIEW' THEN 'VIEW'
      WHEN 'ADMIN.AUDIT_INVESTIGATE' THEN 'UPDATE'
      WHEN 'ADMIN.AUDIT_EXPORT' THEN 'EXPORT'
      ELSE 'MANAGE'
  END
WHERE resource.key IN (
        'ADMIN.AUDIT_VIEW', 'ADMIN.AUDIT_INVESTIGATE',
        'ADMIN.AUDIT_EXPORT', 'ADMIN.AUDIT_CONFIGURE')
  AND (
      role.code IN ('ADMIN', 'PLATFORM_ADMIN', 'AUDIT_ADMIN')
      OR (role.code IN ('TENANT_ADMIN', 'AUDITOR') AND resource.key <> 'ADMIN.AUDIT_CONFIGURE')
  )
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE
SET effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP;
