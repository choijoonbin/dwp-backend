-- V99 is already deployed and immutable. Apply its later concurrency
-- hardening as a forward migration so upgrades remain Flyway-safe.

ALTER FUNCTION sys_assert_identity_role_plane_boundary(BIGINT, BIGINT)
    RENAME TO sys_assert_identity_role_plane_boundary_unlocked;

CREATE OR REPLACE FUNCTION sys_assert_identity_role_plane_boundary(
    checked_tenant_id BIGINT,
    checked_user_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM sys_lock_identity_authority_boundary(
        checked_tenant_id, checked_user_id);
    PERFORM sys_assert_identity_role_plane_boundary_unlocked(
        checked_tenant_id, checked_user_id);
END;
$$;

CREATE OR REPLACE FUNCTION sys_enforce_group_role_plane_boundary()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    member_user_id BIGINT;
BEGIN
    IF NEW.lifecycle_state = 'ACTIVE'
       AND EXISTS (
           SELECT 1
             FROM com_roles role
             LEFT JOIN sys_builtin_role_catalog catalog
               ON catalog.role_code = role.builtin_role_code
            WHERE role.tenant_id = NEW.tenant_id
              AND role.role_id = NEW.role_id
              AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER')) THEN
        RAISE EXCEPTION 'Provider control-plane roles cannot be assigned to groups'
            USING ERRCODE = '23514';
    END IF;
    FOR member_user_id IN
        SELECT membership.user_id
          FROM com_group_members membership
         WHERE membership.tenant_id = NEW.tenant_id
           AND membership.group_id = NEW.group_id
         ORDER BY membership.user_id
    LOOP
        PERFORM sys_assert_identity_role_plane_boundary(NEW.tenant_id, member_user_id);
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION sys_enforce_group_activation_plane_boundary()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    member_user_id BIGINT;
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        FOR member_user_id IN
            SELECT membership.user_id
              FROM com_group_members membership
             WHERE membership.tenant_id = NEW.tenant_id
               AND membership.group_id = NEW.group_id
             ORDER BY membership.user_id
        LOOP
            PERFORM sys_assert_identity_role_plane_boundary(NEW.tenant_id, member_user_id);
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION sys_enforce_role_activation_plane_boundary()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_user_id BIGINT;
BEGIN
    IF NEW.status <> 'ACTIVE' THEN RETURN NEW; END IF;
    IF (NEW.code ~ '^PROVIDER_' OR EXISTS (
            SELECT 1 FROM sys_builtin_role_catalog catalog
             WHERE catalog.role_code = NEW.builtin_role_code
               AND catalog.role_family = 'PROVIDER'))
       AND EXISTS (
            SELECT 1 FROM com_group_role_assignments assignment
             WHERE assignment.tenant_id = NEW.tenant_id
               AND assignment.role_id = NEW.role_id
               AND assignment.lifecycle_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'Provider control-plane roles cannot be assigned to groups'
            USING ERRCODE = '23514';
    END IF;
    FOR affected_user_id IN
        SELECT affected.user_id
          FROM (
              SELECT membership.user_id
                FROM com_role_members membership
               WHERE membership.tenant_id = NEW.tenant_id
                 AND membership.role_id = NEW.role_id
              UNION
              SELECT membership.user_id
                FROM com_group_role_assignments assignment
                JOIN com_group_members membership
                  ON membership.tenant_id = assignment.tenant_id
                 AND membership.group_id = assignment.group_id
               WHERE assignment.tenant_id = NEW.tenant_id
                 AND assignment.role_id = NEW.role_id
              UNION
              SELECT active_grant.user_id
                FROM com_active_privileged_grants active_grant
               WHERE active_grant.tenant_id = NEW.tenant_id
                 AND active_grant.role_id = NEW.role_id
          ) affected
         ORDER BY affected.user_id
    LOOP
        PERFORM sys_assert_identity_role_plane_boundary(NEW.tenant_id, affected_user_id);
    END LOOP;
    RETURN NEW;
END;
$$;
