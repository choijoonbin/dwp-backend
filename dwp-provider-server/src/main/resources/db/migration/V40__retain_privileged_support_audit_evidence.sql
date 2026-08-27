-- Support/JIT events use the governed PRIVILEGED_ACCESS category. Preserve them
-- with the extended retention class both for future trigger output and already
-- queued audit evidence.
ALTER TABLE prv_audit_events
    DROP CONSTRAINT ck_prv_audit_events_category,
    ADD CONSTRAINT ck_prv_audit_events_category
        CHECK (event_category IN (
            'ADMINISTRATION', 'PRIVILEGED_ACCESS', 'SERVICE_HEALTH',
            'CHANGE_MANAGEMENT', 'TENANT_LIFECYCLE', 'DATA_GOVERNANCE',
            'FEATURE_ROLLOUT', 'COMMERCIAL_GOVERNANCE'));

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
                WHEN NEW.event_category IN ('SUPPORT', 'SECURITY', 'PRIVILEGED_ACCESS')
                    OR NEW.action LIKE 'provider.support-%' THEN 'AUTHORIZATION'
                ELSE 'ADMIN_CHANGE'
            END,
            'action', NEW.action,
            'outcome', NEW.outcome,
            'severity', CASE
                WHEN NEW.outcome = 'FAILED' THEN 'HIGH'
                WHEN NEW.outcome = 'DENIED' THEN 'MEDIUM'
                WHEN NEW.action LIKE 'provider.support-%' THEN 'MEDIUM'
                ELSE 'INFO'
            END,
            'riskScore', CASE
                WHEN NEW.outcome = 'FAILED' THEN 78
                WHEN NEW.outcome = 'DENIED' THEN 62
                WHEN NEW.action LIKE 'provider.support-%' THEN 55
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
                WHEN NEW.event_category IN ('SUPPORT', 'SECURITY', 'PRIVILEGED_ACCESS')
                    OR NEW.action LIKE 'provider.support-%' THEN 'EXTENDED'
                ELSE 'STANDARD'
            END),
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

UPDATE sys_audit_outbox outbox
   SET payload = jsonb_set(outbox.payload, '{retentionClass}', '"EXTENDED"'::jsonb, TRUE),
       updated_at = CURRENT_TIMESTAMP
  FROM prv_audit_events event
 WHERE event.audit_event_id = outbox.event_id
   AND (
       event.event_category IN ('SUPPORT', 'SECURITY', 'PRIVILEGED_ACCESS')
       OR event.action LIKE 'provider.support-%'
   )
   AND outbox.payload ->> 'retentionClass' IS DISTINCT FROM 'EXTENDED';

-- Session expiry is often materialized by a read/reconcile query rather than an
-- HTTP command. Record that automatic privilege removal at the database state
-- transition so it cannot bypass the provider audit/outbox pipeline.
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
            'expiresAt', NEW.expires_at,
            'sessionVersion', NEW.version)),
        NEW.provider_operator_id, NEW.provider_tenant_id,
        resolved_organization_id, 'PRIVILEGED_ACCESS');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_support_session_auto_expiry ON prv_support_sessions;
CREATE TRIGGER trg_prv_support_session_auto_expiry
AFTER UPDATE OF lifecycle_state ON prv_support_sessions
FOR EACH ROW
WHEN (OLD.lifecycle_state = 'ACTIVE' AND NEW.lifecycle_state = 'EXPIRED')
EXECUTE FUNCTION prv_audit_support_session_auto_expiry();

CREATE OR REPLACE FUNCTION prv_audit_support_request_automatic_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_actor_id BIGINT;
    resolved_organization_id UUID;
    resolved_support_session_id UUID;
    transition_action VARCHAR(120);
BEGIN
    IF OLD.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
       AND NEW.lifecycle_state = 'EXPIRED' THEN
        transition_action := 'provider.support-access.expired-automatically';
    ELSIF OLD.lifecycle_state = 'ACTIVATED'
       AND NEW.lifecycle_state = 'COMPLETED' THEN
        transition_action := 'provider.support-access.completed-after-session-end';
    ELSE
        RETURN NEW;
    END IF;

    SELECT operator.auth_user_id, tenant.organization_id
      INTO resolved_actor_id, resolved_organization_id
      FROM prv_operators operator
      JOIN prv_tenants tenant
        ON tenant.provider_tenant_id = NEW.provider_tenant_id
     WHERE operator.provider_operator_id = NEW.requester_operator_id;

    SELECT session.support_session_id
      INTO resolved_support_session_id
      FROM prv_support_sessions session
     WHERE session.support_access_request_id = NEW.support_access_request_id;

    INSERT INTO prv_audit_events (
        audit_event_id, actor_id, action, target_type, target_id, outcome,
        correlation_id, redacted_snapshot, provider_operator_id,
        provider_tenant_id, organization_id, event_category)
    VALUES (
        gen_random_uuid(), resolved_actor_id, transition_action,
        'SUPPORT_ACCESS_REQUEST', NEW.support_access_request_id::text,
        'SUCCESS', 'automatic:support-access-reconcile',
        jsonb_strip_nulls(jsonb_build_object(
            'supportAccessRequestId', NEW.support_access_request_id,
            'supportSessionId', resolved_support_session_id,
            'fromState', OLD.lifecycle_state,
            'toState', NEW.lifecycle_state,
            'decisionDueAt', NEW.decision_due_at,
            'completedAt', NEW.completed_at,
            'postReviewState', NEW.post_review_state,
            'requestVersion', NEW.version)),
        NEW.requester_operator_id, NEW.provider_tenant_id,
        resolved_organization_id, 'PRIVILEGED_ACCESS');
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_support_request_automatic_transition
    ON prv_support_access_requests;
CREATE TRIGGER trg_prv_support_request_automatic_transition
AFTER UPDATE OF lifecycle_state ON prv_support_access_requests
FOR EACH ROW
WHEN (OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state)
EXECUTE FUNCTION prv_audit_support_request_automatic_transition();

-- The provider-local audit ledger is append-only for the application role.
-- The enterprise WORM/SIEM copy remains a separate production release gate.
CREATE OR REPLACE FUNCTION prv_reject_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Provider audit events are append-only'
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS trg_prv_audit_events_append_only ON prv_audit_events;
CREATE TRIGGER trg_prv_audit_events_append_only
BEFORE UPDATE OR DELETE ON prv_audit_events
FOR EACH ROW EXECUTE FUNCTION prv_reject_audit_event_mutation();
