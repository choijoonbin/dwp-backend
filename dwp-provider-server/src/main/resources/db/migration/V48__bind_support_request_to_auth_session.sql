ALTER TABLE prv_support_access_requests
    ADD COLUMN requester_auth_session_id UUID;

-- A request created before the binding existed cannot prove that activation is
-- happening in the same authenticated browser session. Revoke any live grant
-- and expire every still-activatable request instead of inventing a binding.
WITH revoked AS (
    UPDATE prv_support_sessions session
       SET lifecycle_state = 'REVOKED',
           revoked_at = CURRENT_TIMESTAMP,
           revoked_by = session.provider_operator_id,
           updated_at = CURRENT_TIMESTAMP,
           updated_by = session.provider_operator_id,
           version = session.version + 1
     WHERE session.lifecycle_state = 'ACTIVE'
       AND NOT EXISTS (
           SELECT 1
             FROM prv_support_access_requests request
            WHERE request.support_access_request_id = session.support_access_request_id
              AND request.requester_auth_session_id IS NOT NULL
              AND request.requester_auth_session_id = session.origin_auth_session_id)
    RETURNING session.support_session_id,
              session.support_access_request_id,
              session.provider_tenant_id,
              session.provider_operator_id,
              session.origin_auth_session_id,
              session.version
)
INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category)
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.support-session.revoked-for-request-auth-binding',
       'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
       'migration:v48:request-auth-binding',
       jsonb_build_object(
           'supportSessionId', revoked.support_session_id,
           'supportAccessRequestId', revoked.support_access_request_id,
           'reasonCode', 'REQUEST_AUTH_SESSION_UNBOUND',
           'originAuthSessionPresent', revoked.origin_auth_session_id IS NOT NULL,
           'sessionVersion', revoked.version),
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
   AND request.requester_auth_session_id IS NULL;

UPDATE prv_support_access_requests request
   SET lifecycle_state = 'EXPIRED',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = request.requester_operator_id,
       version = request.version + 1
 WHERE request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
   AND request.requester_auth_session_id IS NULL;

ALTER TABLE prv_support_access_requests
    ADD CONSTRAINT ck_prv_support_request_auth_session_binding
        CHECK (
            requester_auth_session_id IS NOT NULL
            OR lifecycle_state IN (
                'DENIED', 'CANCELLED', 'EXPIRED', 'COMPLETED', 'REVIEWED'));

CREATE OR REPLACE FUNCTION prv_validate_support_request_auth_session_binding()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.lifecycle_state = 'ACTIVE' AND NOT EXISTS (
        SELECT 1
          FROM prv_support_access_requests request
         WHERE request.support_access_request_id = NEW.support_access_request_id
           AND request.requester_operator_id = NEW.provider_operator_id
           AND request.requester_auth_session_id = NEW.origin_auth_session_id
           AND request.lifecycle_state IN ('APPROVED', 'ACTIVATED')
    ) THEN
        RAISE EXCEPTION
            'active support session % is not bound to the original request auth session',
            NEW.support_session_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_prv_validate_support_request_auth_session_binding
AFTER INSERT OR UPDATE ON prv_support_sessions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION prv_validate_support_request_auth_session_binding();

COMMENT ON COLUMN prv_support_access_requests.requester_auth_session_id IS
    'Immutable auth session that created the request; activation must use the exact same session.';
