-- sys_login_histories.created_at is a legacy TIMESTAMP column whose effective
-- timezone depends on the application JVM. The AFTER INSERT trigger runs at the
-- same instant, so the database transaction timestamp is the unambiguous source.
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
            'occurredAt', CURRENT_TIMESTAMP,
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

-- Repair only unpublished login events from the draft trigger. outbox.created_at
-- is TIMESTAMPTZ and records the same transaction instant without ambiguity.
UPDATE sys_audit_outbox
SET payload = jsonb_set(payload, '{occurredAt}', to_jsonb(created_at), TRUE),
    status = 'FAILED',
    attempt_count = 0,
    available_at = CURRENT_TIMESTAMP,
    locked_by = NULL,
    locked_until = NULL,
    last_error = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE status <> 'PUBLISHED'
  AND payload->>'sourceService' = 'dwp-auth-server'
  AND payload->>'action' IN (
      'authentication.login.succeeded', 'authentication.login.failed');
