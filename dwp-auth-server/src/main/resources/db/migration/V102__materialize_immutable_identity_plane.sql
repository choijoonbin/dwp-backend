-- Identity plane is a durable principal property. Removing the last provider
-- role must not silently turn the same credential into a tenant identity.
ALTER TABLE com_users
    ADD COLUMN identity_plane VARCHAR(16) NOT NULL DEFAULT 'TENANT';

ALTER TABLE com_users
    ADD CONSTRAINT ck_com_users_identity_plane
        CHECK (identity_plane IN ('PROVIDER', 'TENANT'));

UPDATE com_users user_record
   SET identity_plane = 'PROVIDER',
       updated_at = CURRENT_TIMESTAMP
 WHERE EXISTS (
     SELECT 1
       FROM com_role_members membership
       JOIN com_roles role
         ON role.tenant_id = membership.tenant_id
        AND role.role_id = membership.role_id
       LEFT JOIN sys_builtin_role_catalog catalog
         ON catalog.role_code = role.builtin_role_code
      WHERE membership.tenant_id = user_record.tenant_id
        AND membership.user_id = user_record.user_id
        AND (UPPER(BTRIM(role.code)) ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER'));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_roles role
          LEFT JOIN sys_builtin_role_catalog catalog
            ON catalog.role_code = role.builtin_role_code
         WHERE UPPER(BTRIM(role.code)) ~ '^PROVIDER_'
           AND NOT (
               role.code = UPPER(BTRIM(role.code))
               AND role.role_type = 'SYSTEM'
               AND role.builtin_role_code = role.code
               AND COALESCE(catalog.role_family = 'PROVIDER', FALSE))) THEN
        RAISE EXCEPTION 'Custom or malformed roles use the reserved PROVIDER_* namespace';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION sys_enforce_provider_role_namespace()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF UPPER(BTRIM(NEW.code)) ~ '^PROVIDER_'
       AND NOT (
           NEW.code = UPPER(BTRIM(NEW.code))
           AND NEW.role_type = 'SYSTEM'
           AND NEW.builtin_role_code = NEW.code
           AND EXISTS (
               SELECT 1 FROM sys_builtin_role_catalog catalog
                WHERE catalog.role_code = NEW.builtin_role_code
                  AND catalog.role_family = 'PROVIDER')) THEN
        RAISE EXCEPTION 'The PROVIDER_* role namespace is reserved for built-in provider roles'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_provider_role_namespace
BEFORE INSERT OR UPDATE OF code, role_type, builtin_role_code ON com_roles
FOR EACH ROW EXECUTE FUNCTION sys_enforce_provider_role_namespace();

CREATE OR REPLACE FUNCTION sys_prevent_identity_plane_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.identity_plane IS DISTINCT FROM NEW.identity_plane THEN
        RAISE EXCEPTION
            'Identity plane is immutable; deprovision the principal instead of changing planes'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_identity_plane_immutable
BEFORE UPDATE OF identity_plane ON com_users
FOR EACH ROW EXECUTE FUNCTION sys_prevent_identity_plane_mutation();

CREATE OR REPLACE FUNCTION sys_assert_role_matches_identity_plane(
    checked_tenant_id BIGINT,
    checked_user_id BIGINT,
    checked_role_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    resolved_plane VARCHAR(16);
    provider_role BOOLEAN;
BEGIN
    PERFORM sys_lock_identity_authority_boundary(checked_tenant_id, checked_user_id);
    SELECT user_record.identity_plane,
           (UPPER(BTRIM(role.code)) ~ '^PROVIDER_'
               OR COALESCE(catalog.role_family = 'PROVIDER', FALSE))
      INTO resolved_plane, provider_role
      FROM com_users user_record
      JOIN com_roles role
        ON role.tenant_id = user_record.tenant_id
       AND role.role_id = checked_role_id
      LEFT JOIN sys_builtin_role_catalog catalog
        ON catalog.role_code = role.builtin_role_code
     WHERE user_record.tenant_id = checked_tenant_id
       AND user_record.user_id = checked_user_id;
    IF resolved_plane IS NULL THEN
        RAISE EXCEPTION 'Unknown identity or role for tenant %, user %, role %',
            checked_tenant_id, checked_user_id, checked_role_id
            USING ERRCODE = '23503';
    END IF;
    IF (resolved_plane = 'PROVIDER') IS DISTINCT FROM provider_role THEN
        RAISE EXCEPTION
            'Identity plane % does not match role namespace for tenant %, user %, role %',
            resolved_plane, checked_tenant_id, checked_user_id, checked_role_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION sys_enforce_direct_role_identity_plane()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM sys_assert_role_matches_identity_plane(
        NEW.tenant_id, NEW.user_id, NEW.role_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_direct_role_identity_plane
BEFORE INSERT OR UPDATE OF tenant_id, user_id, role_id ON com_role_members
FOR EACH ROW EXECUTE FUNCTION sys_enforce_direct_role_identity_plane();

CREATE OR REPLACE FUNCTION sys_enforce_privileged_role_identity_plane()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.revoked_at IS NULL THEN
        PERFORM sys_assert_role_matches_identity_plane(
            NEW.tenant_id, NEW.user_id, NEW.role_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_privileged_role_identity_plane
BEFORE INSERT OR UPDATE OF tenant_id, user_id, role_id, revoked_at
ON com_active_privileged_grants
FOR EACH ROW EXECUTE FUNCTION sys_enforce_privileged_role_identity_plane();

CREATE OR REPLACE FUNCTION sys_reject_provider_group_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM sys_lock_identity_authority_boundary(NEW.tenant_id, NEW.user_id);
    IF EXISTS (
        SELECT 1 FROM com_users user_record
         WHERE user_record.tenant_id = NEW.tenant_id
           AND user_record.user_id = NEW.user_id
           AND user_record.identity_plane = 'PROVIDER') THEN
        RAISE EXCEPTION 'Provider identities cannot join tenant groups'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_provider_group_membership_identity_plane
BEFORE INSERT OR UPDATE OF tenant_id, group_id, user_id ON com_group_members
FOR EACH ROW EXECUTE FUNCTION sys_reject_provider_group_membership();

CREATE OR REPLACE FUNCTION sys_reject_provider_principal_authority()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.lifecycle_state NOT IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
       OR NEW.principal_type <> 'USER'
       OR NEW.principal_ref !~ '^[1-9][0-9]*$' THEN
        RETURN NEW;
    END IF;
    PERFORM sys_lock_identity_authority_boundary(
        NEW.tenant_id, NEW.principal_ref::bigint);
    IF EXISTS (
        SELECT 1 FROM com_users user_record
         WHERE user_record.tenant_id = NEW.tenant_id
           AND user_record.user_id = NEW.principal_ref::bigint
           AND user_record.identity_plane = 'PROVIDER') THEN
        RAISE EXCEPTION 'Provider identities cannot hold tenant authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_resource_grant_identity_plane
BEFORE INSERT OR UPDATE OF tenant_id, principal_type, principal_ref, lifecycle_state
ON com_principal_resource_grants
FOR EACH ROW EXECUTE FUNCTION sys_reject_provider_principal_authority();

CREATE TRIGGER trg_admin_role_identity_plane
BEFORE INSERT OR UPDATE OF tenant_id, principal_type, principal_ref, lifecycle_state
ON com_admin_role_assignments
FOR EACH ROW EXECUTE FUNCTION sys_reject_provider_principal_authority();

CREATE TRIGGER trg_scoped_duty_identity_plane
BEFORE INSERT OR UPDATE OF tenant_id, principal_type, principal_ref, lifecycle_state
ON com_admin_scoped_duty_assignments
FOR EACH ROW EXECUTE FUNCTION sys_reject_provider_principal_authority();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_users user_record
          JOIN com_role_members membership
            ON membership.tenant_id = user_record.tenant_id
           AND membership.user_id = user_record.user_id
          JOIN com_roles role
            ON role.tenant_id = membership.tenant_id
           AND role.role_id = membership.role_id
         WHERE (user_record.identity_plane = 'PROVIDER')
               IS DISTINCT FROM (UPPER(BTRIM(role.code)) ~ '^PROVIDER_')) THEN
        RAISE EXCEPTION 'Identity-plane and direct-role namespace drift remains';
    END IF;
END;
$$;
