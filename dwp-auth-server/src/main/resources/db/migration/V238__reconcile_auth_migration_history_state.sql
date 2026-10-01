-- Converge databases that briefly applied the pre-release V225/V228 rewrites with
-- databases that retained the canonical, append-only migration history. This does
-- not make any additional historical variant acceptable.

DO $$
DECLARE
    assignment_constraint_count INTEGER;
BEGIN
    SELECT COUNT(*)
      INTO assignment_constraint_count
      FROM pg_constraint
     WHERE conrelid = 'com_tenant_app_workforce_assignments'::regclass
       AND conname IN (
           'ck_tenant_app_assignment_review',
           'ck_tenant_app_assignment_activation',
           'ck_tenant_app_assignment_reviewer_not_principal',
           'ck_tenant_app_assignment_activator_not_principal'
       );

    IF assignment_constraint_count <> 4 THEN
        RAISE EXCEPTION
            'Expected the four pre-reconciliation assignment actor constraints, found %',
            assignment_constraint_count;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM com_tenant_app_workforce_assignments
         WHERE lifecycle_state IN ('APPROVED', 'ACTIVE')
           AND (approved_by IS NULL
                OR approved_at IS NULL
                OR approved_by = requested_by
                OR approved_by::text = principal_ref)
    ) THEN
        RAISE EXCEPTION
            'Cannot reconcile assignment review constraints while violating rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM com_tenant_app_workforce_assignments
         WHERE lifecycle_state = 'ACTIVE'
           AND (activated_by IS NULL
                OR activated_at IS NULL
                OR activation_receipt_id IS NULL
                OR activated_by = requested_by
                OR activated_by = approved_by
                OR activated_by::text = principal_ref)
    ) THEN
        RAISE EXCEPTION
            'Cannot reconcile assignment activation constraints while violating rows exist';
    END IF;
END;
$$;

ALTER TABLE com_tenant_app_workforce_assignments
    DROP CONSTRAINT ck_tenant_app_assignment_review,
    DROP CONSTRAINT ck_tenant_app_assignment_activation,
    DROP CONSTRAINT ck_tenant_app_assignment_reviewer_not_principal,
    DROP CONSTRAINT ck_tenant_app_assignment_activator_not_principal;

ALTER TABLE com_tenant_app_workforce_assignments
    ADD CONSTRAINT ck_tenant_app_assignment_review CHECK (
        (lifecycle_state IN ('APPROVED', 'ACTIVE')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by <> requested_by
            AND approved_by::text <> principal_ref)
        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE')) NOT VALID,
    ADD CONSTRAINT ck_tenant_app_assignment_activation CHECK (
        (lifecycle_state = 'ACTIVE'
            AND activated_by IS NOT NULL AND activated_at IS NOT NULL
            AND activation_receipt_id IS NOT NULL
            AND activated_by <> requested_by AND activated_by <> approved_by
            AND activated_by::text <> principal_ref)
        OR lifecycle_state <> 'ACTIVE') NOT VALID;

ALTER TABLE com_tenant_app_workforce_assignments
    VALIDATE CONSTRAINT ck_tenant_app_assignment_review;
ALTER TABLE com_tenant_app_workforce_assignments
    VALIDATE CONSTRAINT ck_tenant_app_assignment_activation;

DO $$
DECLARE
    owner_count INTEGER;
    canonical_count INTEGER;
    alternate_count INTEGER;
    active_v4_count INTEGER;
BEGIN
    SELECT COUNT(*),
           COUNT(*) FILTER (
               WHERE override_policy = 'OWNER_LOCKED'
                 AND default_value = 'false'::jsonb),
           COUNT(*) FILTER (
               WHERE override_policy = 'TENANT_ALLOWED'
                 AND default_value = 'true'::jsonb)
      INTO owner_count, canonical_count, alternate_count
      FROM sys_tenant_setting_owner_registry
     WHERE owner_key = 'AUTH_POLICY'
       AND owner_version = 1
       AND setting_key = 'authentication.requireMfa'
       AND owner_service = 'auth'
       AND value_type = 'BOOLEAN'
       AND resolution_strategy = 'TENANT_OVERRIDE_OR_OWNER_DEFAULT'
       AND activation_mode = 'PUBLISH'
       AND localized_label_key =
           'managed.effective.settings.authentication.requireMfa.title'
       AND lifecycle_state = 'RETIRED';

    IF owner_count <> 1 OR canonical_count + alternate_count <> 1 THEN
        RAISE EXCEPTION
            'AUTH_POLICY v1 MFA owner is not an exact supported historical variant';
    END IF;

    SELECT COUNT(*)
      INTO active_v4_count
      FROM sys_tenant_setting_owner_registry
     WHERE owner_key = 'AUTH_POLICY'
       AND owner_version = 4
       AND setting_key = 'authentication.requireMfa'
       AND owner_service = 'auth'
       AND value_type = 'BOOLEAN'
       AND resolution_strategy = 'TENANT_OVERRIDE_OR_OWNER_DEFAULT'
       AND override_policy = 'OWNER_LOCKED'
       AND activation_mode = 'PUBLISH'
       AND default_value = 'false'::jsonb
       AND localized_label_key =
           'managed.effective.settings.authentication.requireMfa.title'
       AND lifecycle_state = 'ACTIVE';

    IF active_v4_count <> 1 THEN
        RAISE EXCEPTION
            'Expected the exact active AUTH_POLICY v4 MFA owner, found %',
            active_v4_count;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM com_tenant_setting_override_changes
         WHERE owner_key = 'AUTH_POLICY'
           AND owner_version = 1
           AND setting_key = 'authentication.requireMfa'
           AND lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED')
    ) THEN
        RAISE EXCEPTION
            'Cannot reconcile AUTH_POLICY v1 MFA history while an open workflow exists';
    END IF;
END;
$$;

DROP TRIGGER trg_tenant_setting_owner_immutable
    ON sys_tenant_setting_owner_registry;

UPDATE sys_tenant_setting_owner_registry
   SET override_policy = 'OWNER_LOCKED',
       default_value = 'false'::jsonb
 WHERE owner_key = 'AUTH_POLICY'
   AND owner_version = 1
   AND setting_key = 'authentication.requireMfa'
   AND owner_service = 'auth'
   AND value_type = 'BOOLEAN'
   AND resolution_strategy = 'TENANT_OVERRIDE_OR_OWNER_DEFAULT'
   AND override_policy = 'TENANT_ALLOWED'
   AND activation_mode = 'PUBLISH'
   AND default_value = 'true'::jsonb
   AND localized_label_key =
       'managed.effective.settings.authentication.requireMfa.title'
   AND lifecycle_state = 'RETIRED';

CREATE TRIGGER trg_tenant_setting_owner_immutable
    BEFORE UPDATE OR DELETE ON sys_tenant_setting_owner_registry
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_setting_owner_mutation();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM sys_tenant_setting_owner_registry
         WHERE owner_key = 'AUTH_POLICY'
           AND owner_version = 1
           AND setting_key = 'authentication.requireMfa'
           AND override_policy = 'OWNER_LOCKED'
           AND default_value = 'false'::jsonb
           AND lifecycle_state = 'RETIRED'
    ) THEN
        RAISE EXCEPTION 'AUTH_POLICY v1 MFA history did not converge to canonical state';
    END IF;
END;
$$;
