-- Scoped JIT is intentionally disabled for R1. ORG_UNIT and RESOURCE grants are not yet
-- propagated to every downstream PEP with an exact target and authorization revision.
-- Re-enablement requires a reviewed migration that removes these database guards.

UPDATE com_active_privileged_grants
   SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
       revoke_reason = COALESCE(
           revoke_reason,
           'Privileged access activation is disabled for this release.'),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = NULL
 WHERE revoked_at IS NULL;

UPDATE com_privileged_access_requests
   SET lifecycle_state = CASE
           WHEN lifecycle_state = 'PENDING_APPROVAL' THEN 'CANCELLED'
           ELSE 'REVOKED'
       END,
       decided_at = COALESCE(decided_at, CURRENT_TIMESTAMP),
       revoked_at = CASE
           WHEN lifecycle_state = 'ACTIVE' THEN COALESCE(revoked_at, CURRENT_TIMESTAMP)
           ELSE revoked_at
       END,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = NULL
 WHERE lifecycle_state IN ('ACTIVE', 'PENDING_APPROVAL');

UPDATE com_privileged_access_policies
   SET activation_mode = 'DISABLED',
       emergency_mode = 'DISABLED',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = NULL
 WHERE activation_mode <> 'DISABLED'
    OR emergency_mode <> 'DISABLED';

CREATE OR REPLACE FUNCTION sys_enforce_privileged_access_rollout_disabled()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    row_data JSONB := to_jsonb(NEW);
BEGIN
    IF TG_TABLE_NAME = 'com_privileged_access_policies'
       AND (row_data ->> 'activation_mode' <> 'DISABLED'
            OR row_data ->> 'emergency_mode' <> 'DISABLED') THEN
        RAISE EXCEPTION 'Privileged access activation is disabled for this release'
            USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'com_privileged_access_requests'
       AND row_data ->> 'lifecycle_state' IN ('ACTIVE', 'PENDING_APPROVAL') THEN
        RAISE EXCEPTION 'Privileged access requests are disabled for this release'
            USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'com_active_privileged_grants'
       AND row_data ->> 'revoked_at' IS NULL THEN
        RAISE EXCEPTION 'Active privileged grants are disabled for this release'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_privileged_access_policy_rollout_disabled
    ON com_privileged_access_policies;
CREATE TRIGGER trg_privileged_access_policy_rollout_disabled
BEFORE INSERT OR UPDATE ON com_privileged_access_policies
FOR EACH ROW EXECUTE FUNCTION sys_enforce_privileged_access_rollout_disabled();

DROP TRIGGER IF EXISTS trg_privileged_access_request_rollout_disabled
    ON com_privileged_access_requests;
CREATE TRIGGER trg_privileged_access_request_rollout_disabled
BEFORE INSERT OR UPDATE ON com_privileged_access_requests
FOR EACH ROW EXECUTE FUNCTION sys_enforce_privileged_access_rollout_disabled();

DROP TRIGGER IF EXISTS trg_active_privileged_grant_rollout_disabled
    ON com_active_privileged_grants;
CREATE TRIGGER trg_active_privileged_grant_rollout_disabled
BEFORE INSERT OR UPDATE ON com_active_privileged_grants
FOR EACH ROW EXECUTE FUNCTION sys_enforce_privileged_access_rollout_disabled();

COMMENT ON FUNCTION sys_enforce_privileged_access_rollout_disabled() IS
    'R1 fail-closed kill switch; remove only with approved scoped JIT PEP and revision evidence.';
