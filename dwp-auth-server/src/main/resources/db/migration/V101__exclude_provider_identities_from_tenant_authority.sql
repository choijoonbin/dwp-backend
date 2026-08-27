-- Provider roles are control-plane identities. They cannot carry tenant
-- groups, direct application grants, delegated admin responsibilities, or
-- scoped duties even if stale governance rows predate the role-plane split.
-- Serialize all role and authority checks on the canonical identity row.
CREATE OR REPLACE FUNCTION sys_lock_identity_authority_boundary(
    checked_tenant_id BIGINT,
    checked_user_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
      FROM com_users user_record
     WHERE user_record.tenant_id = checked_tenant_id
       AND user_record.user_id = checked_user_id
     FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown identity tenant %, user %',
            checked_tenant_id, checked_user_id
            USING ERRCODE = '23503';
    END IF;
END;
$$;

CREATE TEMP TABLE tmp_provider_authority_identities (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
) ON COMMIT DROP;

INSERT INTO tmp_provider_authority_identities (tenant_id, user_id)
SELECT DISTINCT membership.tenant_id, membership.user_id
  FROM com_role_members membership
  JOIN com_roles role
    ON role.tenant_id = membership.tenant_id
   AND role.role_id = membership.role_id
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE role.status = 'ACTIVE'
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER');

UPDATE com_principal_resource_grants grant_record
   SET lifecycle_state = 'REVOKED',
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = provider_identity.user_id,
       revocation_reason = 'Provider identities cannot hold tenant resource grants.',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = provider_identity.user_id,
       version = grant_record.version + 1
  FROM tmp_provider_authority_identities provider_identity
 WHERE grant_record.tenant_id = provider_identity.tenant_id
   AND grant_record.principal_type = 'USER'
   AND grant_record.principal_ref = provider_identity.user_id::text
   AND grant_record.lifecycle_state = 'ACTIVE';

UPDATE com_admin_scoped_duty_assignments assignment
   SET lifecycle_state = 'REVOKED',
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = provider_identity.user_id,
       revocation_reason = 'Provider identities cannot hold tenant scoped duties.',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = provider_identity.user_id,
       version = assignment.version + 1
  FROM tmp_provider_authority_identities provider_identity
 WHERE assignment.tenant_id = provider_identity.tenant_id
   AND assignment.principal_type = 'USER'
   AND assignment.principal_ref = provider_identity.user_id::text
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE');

UPDATE com_admin_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = provider_identity.user_id,
       revocation_reason = 'Provider identities cannot hold tenant admin responsibilities.',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = provider_identity.user_id,
       version = assignment.version + 1
  FROM tmp_provider_authority_identities provider_identity
 WHERE assignment.tenant_id = provider_identity.tenant_id
   AND assignment.principal_type = 'USER'
   AND assignment.principal_ref = provider_identity.user_id::text
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE');

DELETE FROM com_group_members membership
USING tmp_provider_authority_identities provider_identity
 WHERE membership.tenant_id = provider_identity.tenant_id
   AND membership.user_id = provider_identity.user_id;

UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = provider_identity.user_id
  FROM tmp_provider_authority_identities provider_identity
 WHERE session.tenant_id = provider_identity.tenant_id
   AND session.user_id = provider_identity.user_id
   AND session.revoked_at IS NULL;

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = provider_identity.user_id
  FROM tmp_provider_authority_identities provider_identity
 WHERE user_record.tenant_id = provider_identity.tenant_id
   AND user_record.user_id = provider_identity.user_id;

CREATE OR REPLACE FUNCTION sys_assert_provider_has_no_tenant_authority(
    checked_tenant_id BIGINT,
    checked_user_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    provider_identity BOOLEAN;
BEGIN
    PERFORM sys_lock_identity_authority_boundary(
        checked_tenant_id, checked_user_id);
    SELECT EXISTS (
        SELECT 1
          FROM com_role_members membership
          JOIN com_roles role
            ON role.tenant_id = membership.tenant_id
           AND role.role_id = membership.role_id
          LEFT JOIN sys_builtin_role_catalog catalog
            ON catalog.role_code = role.builtin_role_code
         WHERE membership.tenant_id = checked_tenant_id
           AND membership.user_id = checked_user_id
           AND role.status = 'ACTIVE'
           AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER')
    ) INTO provider_identity;

    IF NOT provider_identity THEN RETURN; END IF;

    IF EXISTS (
        SELECT 1 FROM com_group_members membership
         WHERE membership.tenant_id = checked_tenant_id
           AND membership.user_id = checked_user_id
    ) OR EXISTS (
        SELECT 1 FROM com_principal_resource_grants grant_record
         WHERE grant_record.tenant_id = checked_tenant_id
           AND grant_record.principal_type = 'USER'
           AND grant_record.principal_ref = checked_user_id::text
           AND grant_record.lifecycle_state = 'ACTIVE'
    ) OR EXISTS (
        SELECT 1 FROM com_admin_role_assignments assignment
         WHERE assignment.tenant_id = checked_tenant_id
           AND assignment.principal_type = 'USER'
           AND assignment.principal_ref = checked_user_id::text
           AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
    ) OR EXISTS (
        SELECT 1 FROM com_admin_scoped_duty_assignments assignment
         WHERE assignment.tenant_id = checked_tenant_id
           AND assignment.principal_type = 'USER'
           AND assignment.principal_ref = checked_user_id::text
           AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
    ) THEN
        RAISE EXCEPTION
            'Provider identities cannot hold tenant groups, grants, admin responsibilities, or scoped duties for tenant %, user %',
            checked_tenant_id, checked_user_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION sys_enforce_provider_authority_principal_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Closed rows carry no authority. Skipping them also lets the migration
    -- revoke several legacy rows in one statement without an AFTER-row
    -- trigger observing another row that the statement has not visited yet.
    IF NEW.lifecycle_state NOT IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE') THEN
        RETURN NEW;
    END IF;
    IF NEW.principal_type = 'USER' AND NEW.principal_ref ~ '^[1-9][0-9]*$' THEN
        PERFORM sys_assert_provider_has_no_tenant_authority(
            NEW.tenant_id, NEW.principal_ref::bigint);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_principal_resource_provider_boundary
AFTER INSERT OR UPDATE OF tenant_id, principal_type, principal_ref,
        lifecycle_state, valid_from, valid_to
ON com_principal_resource_grants
FOR EACH ROW EXECUTE FUNCTION sys_enforce_provider_authority_principal_row();

CREATE TRIGGER trg_admin_role_provider_boundary
AFTER INSERT OR UPDATE OF tenant_id, principal_type, principal_ref,
        lifecycle_state, valid_from, valid_to
ON com_admin_role_assignments
FOR EACH ROW EXECUTE FUNCTION sys_enforce_provider_authority_principal_row();

CREATE TRIGGER trg_scoped_duty_provider_boundary
AFTER INSERT OR UPDATE OF tenant_id, principal_type, principal_ref,
        lifecycle_state, valid_from, valid_to
ON com_admin_scoped_duty_assignments
FOR EACH ROW EXECUTE FUNCTION sys_enforce_provider_authority_principal_row();

CREATE OR REPLACE FUNCTION sys_enforce_provider_authority_identity_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM sys_assert_provider_has_no_tenant_authority(NEW.tenant_id, NEW.user_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_role_member_provider_authority_boundary
AFTER INSERT OR UPDATE OF tenant_id, role_id, user_id
ON com_role_members
FOR EACH ROW EXECUTE FUNCTION sys_enforce_provider_authority_identity_row();

CREATE TRIGGER trg_group_member_provider_authority_boundary
AFTER INSERT OR UPDATE OF tenant_id, group_id, user_id
ON com_group_members
FOR EACH ROW EXECUTE FUNCTION sys_enforce_provider_authority_identity_row();

CREATE OR REPLACE FUNCTION sys_enforce_provider_authority_role_activation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_user_id BIGINT;
BEGIN
    IF NEW.status <> 'ACTIVE'
       OR NOT (NEW.code ~ '^PROVIDER_' OR EXISTS (
           SELECT 1 FROM sys_builtin_role_catalog catalog
            WHERE catalog.role_code = NEW.builtin_role_code
              AND catalog.role_family = 'PROVIDER')) THEN
        RETURN NEW;
    END IF;
    FOR affected_user_id IN
        SELECT membership.user_id
          FROM com_role_members membership
         WHERE membership.tenant_id = NEW.tenant_id
           AND membership.role_id = NEW.role_id
         ORDER BY membership.user_id
    LOOP
        PERFORM sys_assert_provider_has_no_tenant_authority(
            NEW.tenant_id, affected_user_id);
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_role_activation_provider_authority_boundary
AFTER UPDATE OF code, status, builtin_role_code ON com_roles
FOR EACH ROW EXECUTE FUNCTION sys_enforce_provider_authority_role_activation();

DO $$
DECLARE
    provider_identity RECORD;
BEGIN
    FOR provider_identity IN
        SELECT tenant_id, user_id FROM tmp_provider_authority_identities
    LOOP
        PERFORM sys_assert_provider_has_no_tenant_authority(
            provider_identity.tenant_id, provider_identity.user_id);
    END LOOP;
END;
$$;
