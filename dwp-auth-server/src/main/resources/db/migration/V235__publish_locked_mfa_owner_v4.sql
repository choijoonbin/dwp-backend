-- Retiring an owner version while a mutable workflow still references it would leave
-- an in-flight decision bound to a descriptor that can no longer be published.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_tenant_setting_override_changes
         WHERE owner_key = 'AUTH_POLICY'
           AND owner_version = 1
           AND setting_key = 'authentication.requireMfa'
           AND lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED')
    ) THEN
        RAISE EXCEPTION
            'Cannot retire AUTH_POLICY v1 authentication.requireMfa while an open workflow exists';
    END IF;
END;
$$;

-- Owner descriptors are immutable outside migrations. PostgreSQL transactional DDL ensures
-- that a failure rolls back both the temporary trigger removal and the descriptor changes.
DROP TRIGGER trg_tenant_setting_owner_immutable
    ON sys_tenant_setting_owner_registry;

DO $$
DECLARE
    retired_count INTEGER;
BEGIN
    UPDATE sys_tenant_setting_owner_registry
       SET lifecycle_state = 'RETIRED'
     WHERE owner_key = 'AUTH_POLICY'
       AND owner_version = 1
       AND setting_key = 'authentication.requireMfa'
       AND owner_service = 'auth'
       AND value_type = 'BOOLEAN'
       AND resolution_strategy = 'TENANT_OVERRIDE_OR_OWNER_DEFAULT'
       AND override_policy = 'OWNER_LOCKED'
       AND activation_mode = 'PUBLISH'
       AND default_value = 'false'::jsonb
       AND lifecycle_state = 'ACTIVE';

    GET DIAGNOSTICS retired_count = ROW_COUNT;
    IF retired_count <> 1 THEN
        RAISE EXCEPTION
            'Expected exactly one active AUTH_POLICY v1 authentication.requireMfa descriptor, retired %',
            retired_count;
    END IF;
END;
$$;

INSERT INTO sys_tenant_setting_owner_registry (
    owner_key, owner_version, setting_key, owner_service, value_type,
    resolution_strategy, override_policy, activation_mode, default_value,
    localized_label_key, lifecycle_state)
VALUES (
    'AUTH_POLICY', 4, 'authentication.requireMfa', 'auth', 'BOOLEAN',
    'TENANT_OVERRIDE_OR_OWNER_DEFAULT', 'OWNER_LOCKED', 'PUBLISH',
    'false'::jsonb, 'managed.effective.settings.authentication.requireMfa.title', 'ACTIVE');

CREATE TRIGGER trg_tenant_setting_owner_immutable
    BEFORE UPDATE OR DELETE ON sys_tenant_setting_owner_registry
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_setting_owner_mutation();
