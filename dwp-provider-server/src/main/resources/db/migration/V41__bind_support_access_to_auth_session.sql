-- A support credential is valid only in the login-session family that
-- activated it. Token rotation preserves the family id; logout/re-login does
-- not. Historical active rows cannot be safely backfilled, so fail closed by
-- revoking them and retaining explicit migration evidence.
ALTER TABLE prv_support_sessions
    ADD COLUMN origin_auth_session_id UUID;

WITH revoked AS (
    UPDATE prv_support_sessions session
       SET lifecycle_state = 'REVOKED',
           revoked_at = CURRENT_TIMESTAMP,
           revoked_by = session.provider_operator_id,
           updated_at = CURRENT_TIMESTAMP,
           updated_by = session.provider_operator_id,
           version = session.version + 1
     WHERE session.lifecycle_state = 'ACTIVE'
       AND session.origin_auth_session_id IS NULL
    RETURNING session.support_session_id,
              session.support_access_request_id,
              session.provider_operator_id,
              session.provider_tenant_id,
              session.expires_at,
              session.version
)
INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category)
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.support-session.revoked-for-auth-session-binding',
       'SUPPORT_SESSION', revoked.support_session_id::text, 'SUCCESS',
       'migration:V41-auth-session-binding',
       jsonb_strip_nulls(jsonb_build_object(
           'supportSessionId', revoked.support_session_id,
           'supportAccessRequestId', revoked.support_access_request_id,
           'expiresAt', revoked.expires_at,
           'sessionVersion', revoked.version,
           'reasonCode', 'AUTH_SESSION_BINDING_REQUIRED')),
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
          AND session.lifecycle_state = 'REVOKED'
          AND session.origin_auth_session_id IS NULL);

ALTER TABLE prv_support_sessions
    ADD CONSTRAINT ck_prv_support_session_auth_binding
        CHECK (lifecycle_state <> 'ACTIVE' OR origin_auth_session_id IS NOT NULL);

CREATE INDEX idx_prv_support_sessions_auth_binding
    ON prv_support_sessions(provider_operator_id, origin_auth_session_id)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON COLUMN prv_support_sessions.origin_auth_session_id IS
    'Auth JWT sid/session-family UUID that activated the JIT session; every use must match.';
