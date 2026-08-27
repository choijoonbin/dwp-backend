-- A provider operator is a control-plane identity, never a tenant identity.
-- Customer access is represented by a separately verified support session and
-- must not be materialized as a role on the operator's auth identity.

-- Java and Gateway classify the reserved PROVIDER_* namespace without a
-- catalog lookup. Keep that claim contract identical to the catalog family so
-- a future role cannot silently cross the enforcement boundary.
ALTER TABLE sys_builtin_role_catalog
    ADD CONSTRAINT ck_sys_builtin_role_provider_namespace
        CHECK ((role_family = 'PROVIDER') = (role_code ~ '^PROVIDER_'));

CREATE TEMP TABLE tmp_direct_provider_identities (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
) ON COMMIT DROP;

INSERT INTO tmp_direct_provider_identities (tenant_id, user_id)
SELECT DISTINCT membership.tenant_id, membership.user_id
  FROM com_role_members membership
  JOIN com_roles role
    ON role.tenant_id = membership.tenant_id
   AND role.role_id = membership.role_id
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE role.code ~ '^PROVIDER_'
    OR catalog.role_family = 'PROVIDER';

CREATE TEMP TABLE tmp_role_plane_affected_identities (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
) ON COMMIT DROP;

INSERT INTO tmp_role_plane_affected_identities (tenant_id, user_id)
SELECT tenant_id, user_id FROM tmp_direct_provider_identities
ON CONFLICT DO NOTHING;

INSERT INTO tmp_role_plane_affected_identities (tenant_id, user_id)
SELECT DISTINCT membership.tenant_id, membership.user_id
  FROM com_group_role_assignments assignment
  JOIN com_group_members membership
    ON membership.tenant_id = assignment.tenant_id
   AND membership.group_id = assignment.group_id
  JOIN com_roles role
    ON role.tenant_id = assignment.tenant_id
   AND role.role_id = assignment.role_id
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE assignment.lifecycle_state = 'ACTIVE'
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER')
ON CONFLICT DO NOTHING;

INSERT INTO tmp_role_plane_affected_identities (tenant_id, user_id)
SELECT DISTINCT active_grant.tenant_id, active_grant.user_id
  FROM com_active_privileged_grants active_grant
  JOIN com_roles role
    ON role.tenant_id = active_grant.tenant_id
   AND role.role_id = active_grant.role_id
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE active_grant.revoked_at IS NULL
   AND active_grant.expires_at > CURRENT_TIMESTAMP
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER')
ON CONFLICT DO NOTHING;

-- Preserve the historically linked bootstrap account as provider-only. Its
-- password remains a local bootstrap source, but it no longer carries ADMIN.
UPDATE com_users
   SET display_name = 'Provider Bootstrap Administrator',
       access_revision = access_revision + 1,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE email_normalized = 'admin@dwp.local';

-- A direct provider identity cannot retain tenant roles or inherit group
-- roles. This includes the former ADMIN + PROVIDER_ADMIN bootstrap identity.
DELETE FROM com_role_members membership
USING tmp_direct_provider_identities provider_identity, com_roles role
 WHERE membership.tenant_id = provider_identity.tenant_id
   AND membership.user_id = provider_identity.user_id
   AND role.tenant_id = membership.tenant_id
   AND role.role_id = membership.role_id
   AND NOT (role.code ~ '^PROVIDER_' OR role.builtin_role_code IN (
       SELECT role_code
         FROM sys_builtin_role_catalog
        WHERE role_family = 'PROVIDER'));

DELETE FROM com_group_members membership
USING tmp_direct_provider_identities provider_identity
 WHERE membership.tenant_id = provider_identity.tenant_id
   AND membership.user_id = provider_identity.user_id;

-- Provider roles are control-plane-only and therefore cannot be inherited by
-- a tenant group or activated through tenant privileged access.
UPDATE com_group_role_assignments assignment
   SET lifecycle_state = 'REVOKED',
       version = assignment.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE assignment.tenant_id = role.tenant_id
   AND assignment.role_id = role.role_id
   AND assignment.lifecycle_state = 'ACTIVE'
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER');

UPDATE com_active_privileged_grants active_grant
   SET revoked_at = CURRENT_TIMESTAMP,
       revoked_by = 1,
       revoke_reason = 'Provider and tenant identity planes were separated.',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE active_grant.tenant_id = role.tenant_id
   AND active_grant.role_id = role.role_id
   AND active_grant.revoked_at IS NULL
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER');

-- A provider identity also cannot retain a different tenant role through an
-- existing live privileged grant.
UPDATE com_active_privileged_grants active_grant
   SET revoked_at = CURRENT_TIMESTAMP,
       revoked_by = 1,
       revoke_reason = 'Direct provider identities cannot hold tenant privileged grants.',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_direct_provider_identities provider_identity
 WHERE active_grant.tenant_id = provider_identity.tenant_id
   AND active_grant.user_id = provider_identity.user_id
   AND active_grant.revoked_at IS NULL;

UPDATE com_privileged_access_requests request
   SET lifecycle_state = 'REVOKED',
       revoked_at = COALESCE(request.revoked_at, CURRENT_TIMESTAMP),
       version = request.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE request.tenant_id = role.tenant_id
   AND request.role_id = role.role_id
   AND request.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE')
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER');

UPDATE com_privileged_access_requests request
   SET lifecycle_state = 'REVOKED',
       revoked_at = COALESCE(request.revoked_at, CURRENT_TIMESTAMP),
       version = request.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_direct_provider_identities provider_identity
 WHERE request.tenant_id = provider_identity.tenant_id
   AND request.requester_user_id = provider_identity.user_id
   AND request.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE');

UPDATE com_privileged_role_eligibilities eligibility
   SET lifecycle_state = 'REVOKED',
       version = eligibility.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE eligibility.tenant_id = role.tenant_id
   AND eligibility.role_id = role.role_id
   AND eligibility.lifecycle_state = 'ACTIVE'
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER');

UPDATE com_privileged_access_policies policy
   SET activation_mode = 'DISABLED',
       lifecycle_state = 'RETIRED',
       version = policy.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM com_roles role
  LEFT JOIN sys_builtin_role_catalog catalog
    ON catalog.role_code = role.builtin_role_code
 WHERE policy.tenant_id = role.tenant_id
   AND policy.role_id = role.role_id
   AND (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER');

-- Role changes invalidate all sessions that could have observed the old
-- effective set, including group- and PIM-derived provider access.
UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_role_plane_affected_identities affected
 WHERE session.tenant_id = affected.tenant_id
   AND session.user_id = affected.user_id
   AND session.revoked_at IS NULL;

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_role_plane_affected_identities affected
 WHERE user_record.tenant_id = affected.tenant_id
   AND user_record.user_id = affected.user_id
   AND user_record.email_normalized <> 'admin@dwp.local';

-- Persist the same conflict policy used by application authorization so that
-- governance UIs and preflight evidence expose a single reason code.
INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code,
    lifecycle_state, enforcement, risk_level)
SELECT LEAST(provider.role_code, tenant.role_code),
       GREATEST(provider.role_code, tenant.role_code),
       'PROVIDER_TENANT_PLANE_SEPARATION',
       'ACTIVE',
       'DENY',
       'CRITICAL'
  FROM sys_builtin_role_catalog provider
 CROSS JOIN sys_builtin_role_catalog tenant
 WHERE provider.role_family = 'PROVIDER'
   AND tenant.role_family <> 'PROVIDER'
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    enforcement = 'DENY',
    risk_level = 'CRITICAL',
    updated_at = CURRENT_TIMESTAMP,
    version = sys_role_conflict_policies.version + 1;

CREATE OR REPLACE FUNCTION sys_assert_identity_role_plane_boundary(
    checked_tenant_id BIGINT,
    checked_user_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        WITH open_roles AS (
            SELECT role.code,
                   (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER') AS provider_role
              FROM com_role_members membership
              JOIN com_roles role
                ON role.tenant_id = membership.tenant_id
               AND role.role_id = membership.role_id
              LEFT JOIN sys_builtin_role_catalog catalog
                ON catalog.role_code = role.builtin_role_code
             WHERE membership.tenant_id = checked_tenant_id
               AND membership.user_id = checked_user_id
               AND role.status = 'ACTIVE'
            UNION
            SELECT role.code,
                   (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER') AS provider_role
              FROM com_group_role_assignments assignment
              JOIN com_group_members membership
                ON membership.tenant_id = assignment.tenant_id
               AND membership.group_id = assignment.group_id
              JOIN com_groups access_group
                ON access_group.tenant_id = membership.tenant_id
               AND access_group.group_id = membership.group_id
              JOIN com_roles role
                ON role.tenant_id = assignment.tenant_id
               AND role.role_id = assignment.role_id
              LEFT JOIN sys_builtin_role_catalog catalog
                ON catalog.role_code = role.builtin_role_code
             WHERE membership.tenant_id = checked_tenant_id
               AND membership.user_id = checked_user_id
               AND access_group.status = 'ACTIVE'
               AND role.status = 'ACTIVE'
               AND assignment.lifecycle_state = 'ACTIVE'
               AND assignment.assignment_type = 'ACTIVE'
               AND assignment.scope_type = 'TENANT'
               -- Ignore valid_from deliberately: a scheduled assignment must
               -- not become a latent cross-plane conflict without a mutation.
               AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
            UNION
            SELECT role.code,
                   (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER') AS provider_role
              FROM com_active_privileged_grants active_grant
              JOIN com_roles role
                ON role.tenant_id = active_grant.tenant_id
               AND role.role_id = active_grant.role_id
              LEFT JOIN sys_builtin_role_catalog catalog
                ON catalog.role_code = role.builtin_role_code
             WHERE active_grant.tenant_id = checked_tenant_id
               AND active_grant.user_id = checked_user_id
               AND role.status = 'ACTIVE'
               AND active_grant.scope_type = 'TENANT'
               AND active_grant.revoked_at IS NULL
               AND active_grant.expires_at > CURRENT_TIMESTAMP
        )
        SELECT 1
          FROM open_roles
        HAVING BOOL_OR(provider_role) AND BOOL_OR(NOT provider_role)
    ) THEN
        RAISE EXCEPTION
            'Provider control-plane roles cannot coexist with tenant or workspace roles for tenant %, user %',
            checked_tenant_id, checked_user_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION sys_enforce_identity_role_plane_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM sys_assert_identity_role_plane_boundary(NEW.tenant_id, NEW.user_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_role_member_plane_boundary
AFTER INSERT OR UPDATE OF tenant_id, role_id, user_id
ON com_role_members
FOR EACH ROW EXECUTE FUNCTION sys_enforce_identity_role_plane_row();

CREATE TRIGGER trg_group_member_plane_boundary
AFTER INSERT OR UPDATE OF tenant_id, group_id, user_id
ON com_group_members
FOR EACH ROW EXECUTE FUNCTION sys_enforce_identity_role_plane_row();

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
    LOOP
        PERFORM sys_assert_identity_role_plane_boundary(NEW.tenant_id, member_user_id);
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_group_role_assignment_plane_boundary
AFTER INSERT OR UPDATE OF tenant_id, group_id, role_id, assignment_type,
        scope_type, valid_from, valid_to, lifecycle_state
ON com_group_role_assignments
FOR EACH ROW EXECUTE FUNCTION sys_enforce_group_role_plane_boundary();

CREATE TRIGGER trg_active_privileged_grant_plane_boundary
AFTER INSERT OR UPDATE OF tenant_id, user_id, role_id, scope_type,
        activated_at, expires_at, revoked_at
ON com_active_privileged_grants
FOR EACH ROW EXECUTE FUNCTION sys_enforce_identity_role_plane_row();

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
        LOOP
            PERFORM sys_assert_identity_role_plane_boundary(NEW.tenant_id, member_user_id);
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_group_activation_plane_boundary
AFTER UPDATE OF status ON com_groups
FOR EACH ROW EXECUTE FUNCTION sys_enforce_group_activation_plane_boundary();

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
    LOOP
        PERFORM sys_assert_identity_role_plane_boundary(NEW.tenant_id, affected_user_id);
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_role_activation_plane_boundary
AFTER UPDATE OF code, status, builtin_role_code ON com_roles
FOR EACH ROW EXECUTE FUNCTION sys_enforce_role_activation_plane_boundary();

CREATE OR REPLACE VIEW v_sys_role_plane_conflicts AS
WITH open_roles AS (
    SELECT membership.tenant_id, membership.user_id, role.code,
           (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER') AS provider_role
      FROM com_role_members membership
      JOIN com_roles role
        ON role.tenant_id = membership.tenant_id
       AND role.role_id = membership.role_id
      LEFT JOIN sys_builtin_role_catalog catalog
        ON catalog.role_code = role.builtin_role_code
     WHERE role.status = 'ACTIVE'
    UNION
    SELECT membership.tenant_id, membership.user_id, role.code,
           (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER') AS provider_role
      FROM com_group_role_assignments assignment
      JOIN com_group_members membership
        ON membership.tenant_id = assignment.tenant_id
       AND membership.group_id = assignment.group_id
      JOIN com_groups access_group
        ON access_group.tenant_id = membership.tenant_id
       AND access_group.group_id = membership.group_id
      JOIN com_roles role
        ON role.tenant_id = assignment.tenant_id
       AND role.role_id = assignment.role_id
      LEFT JOIN sys_builtin_role_catalog catalog
        ON catalog.role_code = role.builtin_role_code
     WHERE access_group.status = 'ACTIVE'
       AND role.status = 'ACTIVE'
       AND assignment.lifecycle_state = 'ACTIVE'
       AND assignment.assignment_type = 'ACTIVE'
       AND assignment.scope_type = 'TENANT'
       -- Future assignments participate in preflight so time alone cannot
       -- create a mixed identity after the write was accepted.
       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
    UNION
    SELECT active_grant.tenant_id, active_grant.user_id, role.code,
           (role.code ~ '^PROVIDER_' OR catalog.role_family = 'PROVIDER') AS provider_role
      FROM com_active_privileged_grants active_grant
      JOIN com_roles role
        ON role.tenant_id = active_grant.tenant_id
       AND role.role_id = active_grant.role_id
      LEFT JOIN sys_builtin_role_catalog catalog
        ON catalog.role_code = role.builtin_role_code
     WHERE role.status = 'ACTIVE'
       AND active_grant.scope_type = 'TENANT'
       AND active_grant.revoked_at IS NULL
       AND active_grant.expires_at > CURRENT_TIMESTAMP
)
SELECT tenant_id,
       user_id,
       ARRAY_AGG(DISTINCT code ORDER BY code) AS role_codes
  FROM open_roles
 GROUP BY tenant_id, user_id
HAVING BOOL_OR(provider_role) AND BOOL_OR(NOT provider_role);

COMMENT ON VIEW v_sys_role_plane_conflicts IS
    'Fail-closed invariant across direct, group-derived, and open privileged roles.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM v_sys_role_plane_conflicts) THEN
        RAISE EXCEPTION 'Provider and tenant role-plane conflicts remain after migration';
    END IF;
    -- The bootstrap row is local-only seed data and may be absent from a
    -- production estate. When present, however, it must be provider-only.
    IF EXISTS (
        SELECT 1 FROM com_users
         WHERE email_normalized = 'admin@dwp.local')
       AND (NOT EXISTS (
            SELECT 1
              FROM com_users user_record
              JOIN com_role_members membership
                ON membership.tenant_id = user_record.tenant_id
               AND membership.user_id = user_record.user_id
              JOIN com_roles role
                ON role.tenant_id = membership.tenant_id
               AND role.role_id = membership.role_id
             WHERE user_record.email_normalized = 'admin@dwp.local'
               AND role.code = 'PROVIDER_ADMIN')
            OR EXISTS (
            SELECT 1
              FROM com_users user_record
              JOIN com_role_members membership
                ON membership.tenant_id = user_record.tenant_id
               AND membership.user_id = user_record.user_id
              JOIN com_roles role
                ON role.tenant_id = membership.tenant_id
               AND role.role_id = membership.role_id
             WHERE user_record.email_normalized = 'admin@dwp.local'
               AND role.code !~ '^PROVIDER_')) THEN
        RAISE EXCEPTION 'admin@dwp.local must remain a provider-only bootstrap identity';
    END IF;
END;
$$;
