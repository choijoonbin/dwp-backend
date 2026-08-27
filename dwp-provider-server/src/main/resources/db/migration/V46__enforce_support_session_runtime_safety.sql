-- Standard provider JIT access is fail-closed at two independent layers:
-- the deployment property and this durable database control. The migration
-- starts with the database control disabled and revokes inherited sessions.
CREATE TABLE prv_support_activation_control (
    control_key VARCHAR(40) PRIMARY KEY,
    activation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    change_reason VARCHAR(1000),
    change_correlation_id VARCHAR(128),
    changed_at TIMESTAMPTZ,
    changed_by BIGINT REFERENCES prv_operators(provider_operator_id),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_prv_support_activation_control_key
        CHECK (control_key = 'STANDARD_JIT'),
    CONSTRAINT ck_prv_support_activation_control_change_evidence
        CHECK (
            version = 0
            OR (changed_by IS NOT NULL
                AND changed_at IS NOT NULL
                AND change_reason IS NOT NULL
                AND LENGTH(BTRIM(change_reason)) > 0)
        )
);

INSERT INTO prv_support_activation_control (control_key, activation_enabled)
VALUES ('STANDARD_JIT', FALSE);

UPDATE prv_support_sessions
   SET last_used_at = COALESCE(last_used_at, started_at);

ALTER TABLE prv_support_sessions
    ALTER COLUMN last_used_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN last_used_at SET NOT NULL;

-- Existing sessions predate the dual-control and exact-scope runtime
-- contract. They cannot be grandfathered safely.
WITH revoked AS (
    UPDATE prv_support_sessions session
       SET lifecycle_state = 'REVOKED',
           revoked_at = CURRENT_TIMESTAMP,
           revoked_by = session.provider_operator_id,
           updated_at = CURRENT_TIMESTAMP,
           updated_by = session.provider_operator_id,
           version = session.version + 1
     WHERE session.lifecycle_state = 'ACTIVE'
    RETURNING session.support_session_id,
              session.support_access_request_id,
              session.provider_tenant_id,
              session.provider_operator_id,
              session.expires_at,
              session.last_used_at,
              session.version
)
INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category)
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.support-session.revoked-by-runtime-safety-migration',
       'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
       'migration:V46-support-runtime-safety',
       jsonb_strip_nulls(jsonb_build_object(
           'supportSessionId', revoked.support_session_id,
           'supportAccessRequestId', revoked.support_access_request_id,
           'absoluteExpiresAt', revoked.expires_at,
           'lastUsedAt', revoked.last_used_at,
           'sessionVersion', revoked.version,
           'reasonCode', 'DUAL_CONTROL_REAUTHORIZATION_REQUIRED')),
       revoked.provider_operator_id, revoked.provider_tenant_id,
       tenant.organization_id, 'PRIVILEGED_ACCESS'
  FROM revoked
  JOIN prv_operators operator
    ON operator.provider_operator_id = revoked.provider_operator_id
  JOIN prv_tenants tenant
    ON tenant.provider_tenant_id = revoked.provider_tenant_id;

UPDATE prv_support_access_requests request
   SET lifecycle_state = 'COMPLETED',
       completed_at = CURRENT_TIMESTAMP,
       post_review_state = 'PENDING',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = request.requester_operator_id,
       version = request.version + 1
 WHERE request.lifecycle_state = 'ACTIVATED'
   AND EXISTS (
       SELECT 1
         FROM prv_support_sessions session
        WHERE session.support_access_request_id = request.support_access_request_id
          AND session.lifecycle_state = 'REVOKED');

ALTER TABLE prv_support_sessions
    ADD CONSTRAINT ck_prv_support_session_executable_metadata
        CHECK (
            lifecycle_state <> 'ACTIVE'
            OR (access_mode = 'STANDARD'
                AND risk_tier = 'L1'
                AND customer_approval_required
                AND approval_reference IS NOT NULL
                AND LENGTH(BTRIM(approval_reference)) > 0)
        );

CREATE INDEX idx_prv_support_sessions_idle_expiry
    ON prv_support_sessions(lifecycle_state, last_used_at, expires_at)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON TABLE prv_support_activation_control IS
    'Durable kill switch for standard provider JIT activation; re-enable has no HTTP API.';
COMMENT ON COLUMN prv_support_sessions.last_used_at IS
    'Server-authoritative idle lease timestamp; effective expiry is min(expires_at, last_used_at + 15 minutes).';

-- A deferred constraint permits the session row and its exact scope row to be
-- created in one transaction while preventing a scope-less or broad session
-- from ever committing as ACTIVE.
CREATE OR REPLACE FUNCTION prv_validate_active_support_session_contract()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_session_id UUID;
    active_session RECORD;
    executable_scope_count INTEGER;
    tenant_ready BOOLEAN;
    activation_enabled BOOLEAN;
BEGIN
    resolved_session_id := CASE
        WHEN TG_TABLE_NAME = 'prv_support_sessions'
            THEN COALESCE(NEW.support_session_id, OLD.support_session_id)
        ELSE COALESCE(NEW.support_session_id, OLD.support_session_id)
    END;

    SELECT session.access_mode, session.risk_tier,
           session.customer_approval_required, session.approval_reference,
           session.provider_tenant_id
      INTO active_session
      FROM prv_support_sessions session
     WHERE session.support_session_id = resolved_session_id
       AND session.lifecycle_state = 'ACTIVE';
    IF NOT FOUND THEN RETURN NULL; END IF;

    SELECT COUNT(*)
      INTO executable_scope_count
      FROM prv_support_session_scopes scope
      JOIN prv_support_scope_catalog catalog
        ON catalog.scope_code = scope.scope_code
     WHERE scope.support_session_id = resolved_session_id
       AND scope.scope_code = 'TENANT_EXPERIENCE_PREVIEW'
       AND catalog.lifecycle_state = 'ACTIVE'
       AND catalog.risk_tier = 'L1'
       AND catalog.requires_customer_approval;

    SELECT tenant.lifecycle_state = 'ACTIVE'
           AND tenant.onboarding_state = 'READY'
           AND tenant.auth_tenant_id IS NOT NULL
      INTO tenant_ready
      FROM prv_tenants tenant
     WHERE tenant.provider_tenant_id = active_session.provider_tenant_id;

    SELECT control.activation_enabled
      INTO activation_enabled
      FROM prv_support_activation_control control
     WHERE control.control_key = 'STANDARD_JIT';

    IF active_session.access_mode <> 'STANDARD'
       OR active_session.risk_tier <> 'L1'
       OR NOT active_session.customer_approval_required
       OR active_session.approval_reference IS NULL
       OR (SELECT COUNT(*) FROM prv_support_session_scopes
            WHERE support_session_id = resolved_session_id) <> 1
       OR executable_scope_count <> 1
       OR tenant_ready IS DISTINCT FROM TRUE
       OR activation_enabled IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION
            'active support session % violates the executable JIT contract',
            resolved_session_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_prv_validate_active_support_session
AFTER INSERT OR UPDATE ON prv_support_sessions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION prv_validate_active_support_session_contract();

CREATE CONSTRAINT TRIGGER trg_prv_validate_active_support_session_scope
AFTER INSERT OR UPDATE OR DELETE ON prv_support_session_scopes
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION prv_validate_active_support_session_contract();

-- Database kill-switch changes are self-auditing. Disabling is always usable,
-- even when activation is already disabled at the deployment layer, and
-- atomically revokes every active support session.
CREATE OR REPLACE FUNCTION prv_apply_support_activation_control()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_actor_id BIGINT;
BEGIN
    IF OLD.activation_enabled IS NOT DISTINCT FROM NEW.activation_enabled THEN
        RETURN NEW;
    END IF;
    IF NEW.changed_by IS NULL OR NEW.change_reason IS NULL
       OR LENGTH(BTRIM(NEW.change_reason)) = 0 THEN
        RAISE EXCEPTION 'support activation control changes require actor and reason';
    END IF;

    SELECT auth_user_id INTO resolved_actor_id
      FROM prv_operators WHERE provider_operator_id = NEW.changed_by;

    IF NOT NEW.activation_enabled THEN
        WITH revoked AS (
            UPDATE prv_support_sessions session
               SET lifecycle_state = 'REVOKED',
                   revoked_at = CURRENT_TIMESTAMP,
                   revoked_by = NEW.changed_by,
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = NEW.changed_by,
                   version = session.version + 1
             WHERE session.lifecycle_state = 'ACTIVE'
            RETURNING session.support_session_id,
                      session.support_access_request_id,
                      session.provider_tenant_id,
                      session.provider_operator_id,
                      session.expires_at,
                      session.last_used_at,
                      session.version
        )
        INSERT INTO prv_audit_events (
            audit_event_id, actor_id, action, target_type, target_id, outcome,
            correlation_id, redacted_snapshot, provider_operator_id,
            provider_tenant_id, organization_id, event_category)
        SELECT gen_random_uuid(), resolved_actor_id,
               'provider.support-session.revoked-by-kill-switch',
               'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
               COALESCE(NEW.change_correlation_id, 'control:support-activation-disabled'),
               jsonb_strip_nulls(jsonb_build_object(
                   'supportSessionId', revoked.support_session_id,
                   'supportAccessRequestId', revoked.support_access_request_id,
                   'absoluteExpiresAt', revoked.expires_at,
                   'lastUsedAt', revoked.last_used_at,
                   'sessionVersion', revoked.version,
                   'reasonCode', 'SUPPORT_KILL_SWITCH_DISABLED')),
               revoked.provider_operator_id, revoked.provider_tenant_id,
               tenant.organization_id, 'PRIVILEGED_ACCESS'
          FROM revoked
          JOIN prv_tenants tenant
            ON tenant.provider_tenant_id = revoked.provider_tenant_id;

        UPDATE prv_support_access_requests request
           SET lifecycle_state = 'COMPLETED',
               completed_at = CURRENT_TIMESTAMP,
               post_review_state = 'PENDING',
               updated_at = CURRENT_TIMESTAMP,
               updated_by = NEW.changed_by,
               version = request.version + 1
         WHERE request.lifecycle_state = 'ACTIVATED'
           AND EXISTS (
               SELECT 1 FROM prv_support_sessions session
                WHERE session.support_access_request_id = request.support_access_request_id
                  AND session.lifecycle_state = 'REVOKED');
    END IF;

    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id, event_category)
    VALUES (
        gen_random_uuid(), resolved_actor_id,
        CASE WHEN NEW.activation_enabled
            THEN 'provider.support-activation.enabled-by-db-control'
            ELSE 'provider.support-activation.disabled-by-db-control' END,
        'SUPPORT_CONTROL', NEW.control_key, 'SUCCESS',
        COALESCE(NEW.change_correlation_id, 'control:support-activation-change'),
        jsonb_build_object(
            'enabled', NEW.activation_enabled,
            'reason', NEW.change_reason,
            'controlVersion', NEW.version),
        NEW.changed_by, 'PRIVILEGED_ACCESS');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_apply_support_activation_control
AFTER UPDATE OF activation_enabled ON prv_support_activation_control
FOR EACH ROW
EXECUTE FUNCTION prv_apply_support_activation_control();

-- Tenant suspension, onboarding regression, or auth-link removal is itself a
-- revocation event. Activation locks the tenant row FOR SHARE, so this update
-- is serialized with creation of a new session.
CREATE OR REPLACE FUNCTION prv_revoke_support_for_unavailable_tenant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.lifecycle_state = 'ACTIVE'
       AND NEW.onboarding_state = 'READY'
       AND NEW.auth_tenant_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    WITH revoked AS (
        UPDATE prv_support_sessions session
           SET lifecycle_state = 'REVOKED',
               revoked_at = CURRENT_TIMESTAMP,
               revoked_by = session.provider_operator_id,
               updated_at = CURRENT_TIMESTAMP,
               updated_by = session.provider_operator_id,
               version = session.version + 1
         WHERE session.provider_tenant_id = NEW.provider_tenant_id
           AND session.lifecycle_state = 'ACTIVE'
        RETURNING session.support_session_id,
                  session.support_access_request_id,
                  session.provider_operator_id,
                  session.version
    )
    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    SELECT gen_random_uuid(), operator.auth_user_id,
           'provider.support-session.revoked-for-tenant-state',
           'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
           'automatic:tenant-unavailable',
           jsonb_build_object(
               'supportSessionId', revoked.support_session_id,
               'supportAccessRequestId', revoked.support_access_request_id,
               'tenantLifecycleState', NEW.lifecycle_state,
               'tenantOnboardingState', NEW.onboarding_state,
               'authTenantLinked', NEW.auth_tenant_id IS NOT NULL,
               'sessionVersion', revoked.version,
               'reasonCode', 'TARGET_TENANT_UNAVAILABLE'),
           revoked.provider_operator_id, NEW.provider_tenant_id,
           NEW.organization_id, 'PRIVILEGED_ACCESS'
      FROM revoked
      JOIN prv_operators operator
        ON operator.provider_operator_id = revoked.provider_operator_id;

    UPDATE prv_support_access_requests request
       SET lifecycle_state = 'COMPLETED',
           completed_at = CURRENT_TIMESTAMP,
           post_review_state = 'PENDING',
           updated_at = CURRENT_TIMESTAMP,
           updated_by = request.requester_operator_id,
           version = request.version + 1
     WHERE request.provider_tenant_id = NEW.provider_tenant_id
       AND request.lifecycle_state = 'ACTIVATED'
       AND EXISTS (
           SELECT 1 FROM prv_support_sessions session
            WHERE session.support_access_request_id = request.support_access_request_id
              AND session.lifecycle_state = 'REVOKED');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_prv_revoke_support_for_unavailable_tenant
AFTER UPDATE OF lifecycle_state, onboarding_state, auth_tenant_id ON prv_tenants
FOR EACH ROW
WHEN (
    OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state
    OR OLD.onboarding_state IS DISTINCT FROM NEW.onboarding_state
    OR OLD.auth_tenant_id IS DISTINCT FROM NEW.auth_tenant_id
)
EXECUTE FUNCTION prv_revoke_support_for_unavailable_tenant();

-- Enrich the existing automatic expiry evidence with the idle lease reason.
CREATE OR REPLACE FUNCTION prv_audit_support_session_auto_expiry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_actor_id BIGINT;
    resolved_organization_id UUID;
BEGIN
    SELECT operator.auth_user_id, tenant.organization_id
      INTO resolved_actor_id, resolved_organization_id
      FROM prv_operators operator
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = NEW.provider_tenant_id
     WHERE operator.provider_operator_id = NEW.provider_operator_id;

    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    VALUES (
        gen_random_uuid(), resolved_actor_id,
        'provider.support-session.expired-automatically',
        'SUPPORT_SESSION', NEW.support_session_id::text, 'SUCCESS',
        'automatic:support-session-expiry',
        jsonb_strip_nulls(jsonb_build_object(
            'supportSessionId', NEW.support_session_id,
            'supportAccessRequestId', NEW.support_access_request_id,
            'absoluteExpiresAt', NEW.expires_at,
            'lastUsedAt', NEW.last_used_at,
            'effectiveExpiresAt', LEAST(
                NEW.expires_at, NEW.last_used_at + INTERVAL '15 minutes'),
            'reasonCode', CASE WHEN NEW.expires_at <= CURRENT_TIMESTAMP
                THEN 'ABSOLUTE_EXPIRY' ELSE 'IDLE_EXPIRY' END,
            'sessionVersion', NEW.version)),
        NEW.provider_operator_id, NEW.provider_tenant_id,
        resolved_organization_id, 'PRIVILEGED_ACCESS');
    RETURN NEW;
END;
$$;
