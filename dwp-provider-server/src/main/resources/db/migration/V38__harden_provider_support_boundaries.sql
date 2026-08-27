-- Provider identities never receive ambient tenant access. The preview scope
-- exposes only an explicit, redacted tenant-configuration projection through a
-- JIT support session.
INSERT INTO prv_support_scope_catalog (
    scope_code, display_name, risk_tier, lifecycle_state, requires_customer_approval)
VALUES (
    'TENANT_EXPERIENCE_PREVIEW',
    'Preview tenant experience configuration',
    'L1',
    'ACTIVE',
    TRUE)
ON CONFLICT (scope_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    lifecycle_state = 'ACTIVE',
    requires_customer_approval = TRUE;

-- Make the retained legacy bootstrap linkage explicit. Daily provider review
-- continues to use the separately seeded provider.admin identity.
UPDATE prv_operators
   SET display_name = 'Provider Bootstrap Administrator',
       role_code = 'PROVIDER_ADMIN',
       updated_at = CURRENT_TIMESTAMP
 WHERE auth_tenant_id = 1
   AND auth_user_id = 1;

-- Expiry is materialized before adding the concurrency invariant. If an older
-- installation already contains overlapping sessions, retain the newest and
-- revoke the rest so the migration is deterministic and auditable.
UPDATE prv_support_sessions
   SET lifecycle_state = 'EXPIRED',
       updated_at = CURRENT_TIMESTAMP,
       version = version + 1
 WHERE lifecycle_state = 'ACTIVE'
   AND expires_at <= CURRENT_TIMESTAMP;

WITH ranked AS (
    SELECT support_session_id,
           ROW_NUMBER() OVER (
               PARTITION BY provider_operator_id
               ORDER BY started_at DESC, support_session_id DESC) AS row_number
      FROM prv_support_sessions
     WHERE lifecycle_state = 'ACTIVE'
       AND expires_at > CURRENT_TIMESTAMP
)
UPDATE prv_support_sessions session
   SET lifecycle_state = 'REVOKED',
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = session.provider_operator_id,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = session.provider_operator_id,
       version = session.version + 1
  FROM ranked
 WHERE ranked.support_session_id = session.support_session_id
   AND ranked.row_number > 1;

CREATE UNIQUE INDEX uk_prv_support_sessions_one_active_operator
    ON prv_support_sessions(provider_operator_id)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON INDEX uk_prv_support_sessions_one_active_operator IS
    'One provider operator may target only one tenant support context at a time.';
