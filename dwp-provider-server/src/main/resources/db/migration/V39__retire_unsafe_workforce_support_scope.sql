-- WORKFORCE_READ remains closed until People owns an executable, field-masked
-- provider-support projection with trusted population provenance. Retiring the
-- catalog entry prevents new requests while the gateway and People service both
-- deny every People support path.
UPDATE prv_support_scope_catalog
   SET lifecycle_state = 'RETIRED'
 WHERE scope_code = 'WORKFORCE_READ';

WITH cancelled_requests AS (
    UPDATE prv_support_access_requests request
       SET lifecycle_state = 'CANCELLED',
           cancelled_at = CURRENT_TIMESTAMP,
           cancelled_by = request.requester_operator_id,
           cancellation_reason = 'WORKFORCE_READ retired until a safe support projection is available.',
           updated_at = CURRENT_TIMESTAMP,
           updated_by = request.requester_operator_id,
           version = request.version + 1
     WHERE request.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED')
       AND EXISTS (
           SELECT 1
             FROM prv_support_access_request_scopes scope
            WHERE scope.support_access_request_id = request.support_access_request_id
              AND scope.scope_code = 'WORKFORCE_READ'
       )
    RETURNING request.support_access_request_id, request.provider_tenant_id,
              request.requester_operator_id
)
INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category)
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.support-access.cancelled-by-policy',
       'SUPPORT_ACCESS_REQUEST', cancelled.support_access_request_id::text,
       'SUCCESS', 'migration:V39:retire-workforce-read',
       jsonb_build_object(
           'scope', 'WORKFORCE_READ',
           'reason', 'SAFE_PROJECTION_UNAVAILABLE'),
       cancelled.requester_operator_id, cancelled.provider_tenant_id,
       tenant.organization_id, 'PRIVILEGED_ACCESS'
  FROM cancelled_requests cancelled
  JOIN prv_operators operator
    ON operator.provider_operator_id = cancelled.requester_operator_id
  JOIN prv_tenants tenant
    ON tenant.provider_tenant_id = cancelled.provider_tenant_id;

WITH revoked_sessions AS (
    UPDATE prv_support_sessions session
   SET lifecycle_state = 'REVOKED',
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = session.provider_operator_id,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = session.provider_operator_id,
       version = session.version + 1
 WHERE session.lifecycle_state = 'ACTIVE'
   AND EXISTS (
       SELECT 1
         FROM prv_support_session_scopes scope
        WHERE scope.support_session_id = session.support_session_id
          AND scope.scope_code = 'WORKFORCE_READ'
   )
    RETURNING session.support_session_id, session.provider_tenant_id,
              session.provider_operator_id
)
INSERT INTO prv_audit_events (
    audit_event_id, actor_id, action, target_type, target_id, outcome,
    correlation_id, redacted_snapshot, provider_operator_id,
    provider_tenant_id, organization_id, event_category)
SELECT gen_random_uuid(), operator.auth_user_id,
       'provider.support-session.revoked-by-policy',
       'SUPPORT_SESSION', revoked.support_session_id::text,
       'SUCCESS', 'migration:V39:retire-workforce-read',
       jsonb_build_object(
           'scope', 'WORKFORCE_READ',
           'reason', 'SAFE_PROJECTION_UNAVAILABLE'),
       revoked.provider_operator_id, revoked.provider_tenant_id,
       tenant.organization_id, 'PRIVILEGED_ACCESS'
  FROM revoked_sessions revoked
  JOIN prv_operators operator
    ON operator.provider_operator_id = revoked.provider_operator_id
  JOIN prv_tenants tenant
    ON tenant.provider_tenant_id = revoked.provider_tenant_id;

UPDATE prv_support_access_requests request
   SET lifecycle_state = 'COMPLETED',
       completed_at = CURRENT_TIMESTAMP,
       post_review_state = 'PENDING',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = session.provider_operator_id,
       version = request.version + 1
  FROM prv_support_sessions session
 WHERE session.support_access_request_id = request.support_access_request_id
   AND session.lifecycle_state = 'REVOKED'
   AND request.lifecycle_state = 'ACTIVATED'
   AND EXISTS (
       SELECT 1
         FROM prv_support_session_scopes scope
        WHERE scope.support_session_id = session.support_session_id
          AND scope.scope_code = 'WORKFORCE_READ'
   );
