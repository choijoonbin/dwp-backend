-- App-governance separation of duties is a user-level invariant. A direct
-- responsibility and responsibilities inherited through one or more active
-- groups must therefore be evaluated together on every overlapping app scope.
-- This migration closes existing drift deterministically before installing the
-- race-safe invariant. Applied migrations are intentionally left untouched.

-- Fence every authority-changing write for the repair-to-trigger-install
-- window. Reads remain available, while no concurrent assignment, membership,
-- principal-state, or scope mutation can commit unvalidated drift between the
-- repair pass and the deferred invariant becoming visible.
LOCK TABLE
    com_admin_app_preset_assignments,
    com_admin_resource_set_members,
    com_admin_resource_sets,
    com_admin_role_assignments,
    com_admin_scoped_duty_assignments,
    com_group_members,
    com_groups,
    com_users
IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE sys_app_responsibility_sod_repairs (
    repair_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repair_run_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    conflict_user_id BIGINT NOT NULL,
    retained_assignment_id UUID NOT NULL,
    revoked_assignment_id UUID NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_key VARCHAR(255) NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    repaired_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_app_responsibility_sod_repair_user
        FOREIGN KEY (tenant_id, conflict_user_id)
        REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT ck_app_responsibility_sod_repair_distinct
        CHECK (retained_assignment_id <> revoked_assignment_id),
    CONSTRAINT uk_app_responsibility_sod_repair_assignment
        UNIQUE (repair_run_id, revoked_assignment_id)
);

CREATE INDEX idx_app_responsibility_sod_repair_tenant
    ON sys_app_responsibility_sod_repairs (
        tenant_id, conflict_user_id, repaired_at DESC);

CREATE VIEW auth_open_app_responsibility_subjects AS
SELECT assignment.tenant_id,
       user_record.user_id,
       assignment.admin_role_assignment_id,
       assignment.principal_type,
       assignment.principal_ref,
       assignment.responsibility_code,
       assignment.resource_set_id,
       assignment.lifecycle_state,
       assignment.valid_from,
       assignment.valid_to,
       assignment.created_at
  FROM com_admin_role_assignments assignment
  JOIN com_users user_record
    ON user_record.tenant_id = assignment.tenant_id
   AND user_record.user_id::text = assignment.principal_ref
   AND user_record.status = 'ACTIVE'
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = assignment.tenant_id
   AND resource_set.resource_set_id = assignment.resource_set_id
   AND resource_set.lifecycle_state = 'ACTIVE'
 WHERE assignment.principal_type = 'USER'
   AND assignment.principal_ref ~ '^[1-9][0-9]*$'
   AND assignment.responsibility_code IN (
       'APP_ACCESS_MANAGER', 'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER')
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
   AND (assignment.valid_to IS NULL
        OR assignment.valid_to > statement_timestamp())
UNION
SELECT assignment.tenant_id,
       membership.user_id,
       assignment.admin_role_assignment_id,
       assignment.principal_type,
       assignment.principal_ref,
       assignment.responsibility_code,
       assignment.resource_set_id,
       assignment.lifecycle_state,
       assignment.valid_from,
       assignment.valid_to,
       assignment.created_at
  FROM com_admin_role_assignments assignment
  JOIN com_groups access_group
    ON access_group.tenant_id = assignment.tenant_id
   AND access_group.group_id::text = assignment.principal_ref
   AND access_group.status = 'ACTIVE'
  JOIN com_group_members membership
    ON membership.tenant_id = access_group.tenant_id
   AND membership.group_id = access_group.group_id
  JOIN com_users user_record
    ON user_record.tenant_id = membership.tenant_id
   AND user_record.user_id = membership.user_id
   AND user_record.status = 'ACTIVE'
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = assignment.tenant_id
   AND resource_set.resource_set_id = assignment.resource_set_id
   AND resource_set.lifecycle_state = 'ACTIVE'
 WHERE assignment.principal_type = 'GROUP'
   AND assignment.principal_ref ~ '^[1-9][0-9]*$'
   AND assignment.responsibility_code IN (
       'APP_ACCESS_MANAGER', 'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER')
   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
   AND (assignment.valid_to IS NULL
        OR assignment.valid_to > statement_timestamp());

CREATE FUNCTION auth_app_responsibility_sod_conflicts(p_tenant_id BIGINT)
RETURNS TABLE (
    tenant_id BIGINT,
    user_id BIGINT,
    left_assignment_id UUID,
    right_assignment_id UUID,
    resource_type VARCHAR(30),
    resource_key VARCHAR(255))
LANGUAGE sql
STABLE
AS $$
    SELECT DISTINCT left_subject.tenant_id,
           left_subject.user_id,
           left_subject.admin_role_assignment_id,
           right_subject.admin_role_assignment_id,
           left_member.resource_type,
           left_member.resource_key
      FROM auth_open_app_responsibility_subjects left_subject
      JOIN auth_open_app_responsibility_subjects right_subject
        ON right_subject.tenant_id = left_subject.tenant_id
       AND right_subject.user_id = left_subject.user_id
       AND right_subject.admin_role_assignment_id
           > left_subject.admin_role_assignment_id
       AND COALESCE(left_subject.valid_from, '-infinity'::TIMESTAMPTZ)
           < COALESCE(right_subject.valid_to, 'infinity'::TIMESTAMPTZ)
       AND COALESCE(right_subject.valid_from, '-infinity'::TIMESTAMPTZ)
           < COALESCE(left_subject.valid_to, 'infinity'::TIMESTAMPTZ)
      JOIN com_admin_resource_set_members left_member
        ON left_member.tenant_id = left_subject.tenant_id
       AND left_member.resource_set_id = left_subject.resource_set_id
       AND left_member.lifecycle_state = 'ACTIVE'
      JOIN com_admin_resource_set_members right_member
        ON right_member.tenant_id = right_subject.tenant_id
       AND right_member.resource_set_id = right_subject.resource_set_id
       AND right_member.resource_type = left_member.resource_type
       AND right_member.resource_key = left_member.resource_key
       AND right_member.lifecycle_state = 'ACTIVE'
     WHERE left_subject.tenant_id = p_tenant_id
       AND ((left_subject.responsibility_code = 'APP_ACCESS_MANAGER'
             AND right_subject.responsibility_code IN (
                 'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER'))
         OR (right_subject.responsibility_code = 'APP_ACCESS_MANAGER'
             AND left_subject.responsibility_code IN (
                 'APP_ACCESS_APPROVER', 'APP_ACCESS_REVIEWER')))
$$;

CREATE FUNCTION dwp_repair_app_responsibility_sod()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    repair_run UUID := gen_random_uuid();
    conflict RECORD;
    retained_assignment UUID;
    revoked_assignment UUID;
    repaired INTEGER := 0;
BEGIN
    LOOP
        SELECT finding.*,
               CASE WHEN ROW(
                       CASE left_assignment.lifecycle_state
                           WHEN 'ACTIVE' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END,
                       CASE left_assignment.principal_type
                           WHEN 'USER' THEN 0 ELSE 1 END,
                       left_assignment.created_at,
                       left_assignment.admin_role_assignment_id)
                   <= ROW(
                       CASE right_assignment.lifecycle_state
                           WHEN 'ACTIVE' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END,
                       CASE right_assignment.principal_type
                           WHEN 'USER' THEN 0 ELSE 1 END,
                       right_assignment.created_at,
                       right_assignment.admin_role_assignment_id)
                   THEN finding.left_assignment_id
                   ELSE finding.right_assignment_id END AS winner_id,
               CASE WHEN ROW(
                       CASE left_assignment.lifecycle_state
                           WHEN 'ACTIVE' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END,
                       CASE left_assignment.principal_type
                           WHEN 'USER' THEN 0 ELSE 1 END,
                       left_assignment.created_at,
                       left_assignment.admin_role_assignment_id)
                   <= ROW(
                       CASE right_assignment.lifecycle_state
                           WHEN 'ACTIVE' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END,
                       CASE right_assignment.principal_type
                           WHEN 'USER' THEN 0 ELSE 1 END,
                       right_assignment.created_at,
                       right_assignment.admin_role_assignment_id)
                   THEN finding.right_assignment_id
                   ELSE finding.left_assignment_id END AS loser_id
          INTO conflict
          FROM (
              SELECT tenant.tenant_id
                FROM com_tenants tenant
               WHERE EXISTS (
                   SELECT 1
                     FROM auth_app_responsibility_sod_conflicts(tenant.tenant_id))
               ORDER BY tenant.tenant_id
               LIMIT 1
          ) conflicted_tenant
          CROSS JOIN LATERAL auth_app_responsibility_sod_conflicts(
              conflicted_tenant.tenant_id) finding
          JOIN com_admin_role_assignments left_assignment
            ON left_assignment.admin_role_assignment_id = finding.left_assignment_id
          JOIN com_admin_role_assignments right_assignment
            ON right_assignment.admin_role_assignment_id = finding.right_assignment_id
         ORDER BY finding.user_id, finding.resource_type, finding.resource_key,
                  finding.left_assignment_id, finding.right_assignment_id
         LIMIT 1;

        EXIT WHEN NOT FOUND;
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'dwp-app-responsibility-sod:' || conflict.tenant_id::text, 0));
        retained_assignment := conflict.winner_id;
        revoked_assignment := conflict.loser_id;

        UPDATE com_admin_scoped_duty_assignments duty
           SET lifecycle_state = 'REVOKED',
               revoked_at = statement_timestamp(),
               revoked_by = NULL,
               revocation_reason =
                   'EFFECTIVE_USER_SOD_REPAIR_V208',
               version = duty.version + 1,
               updated_at = statement_timestamp(),
               updated_by = NULL
         WHERE duty.app_preset_assignment_id IN (
                   SELECT aggregate.app_preset_assignment_id
                     FROM com_admin_app_preset_assignments aggregate
                    WHERE aggregate.responsibility_assignment_id = revoked_assignment)
           AND duty.lifecycle_state IN (
               'PENDING_APPROVAL', 'APPROVED', 'ACTIVE');

        UPDATE com_admin_app_preset_assignments aggregate
           SET lifecycle_state = 'REVOKED',
               revoked_at = statement_timestamp(),
               revoked_by = NULL,
               revocation_reason =
                   'EFFECTIVE_USER_SOD_REPAIR_V208',
               version = aggregate.version + 1,
               updated_at = statement_timestamp(),
               updated_by = NULL
         WHERE aggregate.responsibility_assignment_id = revoked_assignment
           AND aggregate.lifecycle_state IN (
               'PENDING_APPROVAL', 'APPROVED', 'ACTIVE');

        UPDATE com_admin_role_assignments assignment
           SET lifecycle_state = 'REVOKED',
               revoked_at = statement_timestamp(),
               revoked_by = NULL,
               revocation_reason =
                   'EFFECTIVE_USER_SOD_REPAIR_V208',
               version = assignment.version + 1,
               updated_at = statement_timestamp(),
               updated_by = NULL
         WHERE assignment.admin_role_assignment_id = revoked_assignment
           AND assignment.lifecycle_state IN (
               'PENDING_APPROVAL', 'APPROVED', 'ACTIVE');

        IF FOUND THEN
            INSERT INTO sys_app_responsibility_sod_repairs (
                repair_run_id, tenant_id, conflict_user_id,
                retained_assignment_id, revoked_assignment_id,
                resource_type, resource_key, reason_code)
            VALUES (
                repair_run, conflict.tenant_id, conflict.user_id,
                retained_assignment, revoked_assignment,
                conflict.resource_type, conflict.resource_key,
                'EFFECTIVE_USER_CROSS_PRINCIPAL_SOD')
            ON CONFLICT (repair_run_id, revoked_assignment_id) DO NOTHING;
            repaired := repaired + 1;
        END IF;
    END LOOP;

    UPDATE com_users user_record
       SET access_revision = user_record.access_revision + 1,
           version = user_record.version + 1,
           updated_at = statement_timestamp(),
           updated_by = NULL
     WHERE EXISTS (
         SELECT 1
           FROM sys_app_responsibility_sod_repairs repair
           JOIN com_admin_role_assignments revoked
             ON revoked.admin_role_assignment_id = repair.revoked_assignment_id
          WHERE repair.repair_run_id = repair_run
            AND repair.tenant_id = user_record.tenant_id
            AND ((revoked.principal_type = 'USER'
                  AND revoked.principal_ref = user_record.user_id::text)
              OR (revoked.principal_type = 'GROUP' AND EXISTS (
                  SELECT 1
                    FROM com_group_members membership
                   WHERE membership.tenant_id = revoked.tenant_id
                     AND membership.group_id::text = revoked.principal_ref
                     AND membership.user_id = user_record.user_id))));

    UPDATE sys_auth_sessions session
       SET revoked_at = COALESCE(session.revoked_at, statement_timestamp()),
           updated_at = statement_timestamp(),
           updated_by = NULL
     WHERE session.revoked_at IS NULL
       AND EXISTS (
           SELECT 1
             FROM sys_app_responsibility_sod_repairs repair
             JOIN com_admin_role_assignments revoked
               ON revoked.admin_role_assignment_id = repair.revoked_assignment_id
            WHERE repair.repair_run_id = repair_run
              AND repair.tenant_id = session.tenant_id
              AND ((revoked.principal_type = 'USER'
                    AND revoked.principal_ref = session.user_id::text)
                OR (revoked.principal_type = 'GROUP' AND EXISTS (
                    SELECT 1
                      FROM com_group_members membership
                     WHERE membership.tenant_id = revoked.tenant_id
                       AND membership.group_id::text = revoked.principal_ref
                       AND membership.user_id = session.user_id))));

    RETURN repaired;
END;
$$;

-- Preserve the effective row with the strongest lifecycle, then prefer a
-- direct user assignment over inherited group authority, then the oldest row.
-- Every closed row retains an immutable repair receipt and invalidates affected
-- permission snapshots/sessions.
SELECT dwp_repair_app_responsibility_sod();

CREATE FUNCTION dwp_reject_app_responsibility_sod_repair_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'App responsibility SoD repair evidence is immutable';
END;
$$;

CREATE TRIGGER trg_app_responsibility_sod_repair_immutable
    BEFORE UPDATE OR DELETE ON sys_app_responsibility_sod_repairs
    FOR EACH ROW EXECUTE FUNCTION
        dwp_reject_app_responsibility_sod_repair_mutation();

CREATE FUNCTION dwp_assert_app_responsibility_sod(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    conflict RECORD;
BEGIN
    IF p_tenant_id IS NULL THEN
        RETURN;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'dwp-app-responsibility-sod:' || p_tenant_id::text, 0));
    SELECT finding.*
      INTO conflict
      FROM auth_app_responsibility_sod_conflicts(p_tenant_id) finding
     ORDER BY finding.user_id, finding.resource_type, finding.resource_key,
              finding.left_assignment_id, finding.right_assignment_id
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE =
                'Application responsibility effective-user separation-of-duties conflict',
            DETAIL = format(
                'tenant=%s user=%s left=%s right=%s resource=%s:%s',
                conflict.tenant_id, conflict.user_id,
                conflict.left_assignment_id, conflict.right_assignment_id,
                conflict.resource_type, conflict.resource_key);
    END IF;
END;
$$;

CREATE FUNCTION dwp_enforce_app_responsibility_sod()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM dwp_assert_app_responsibility_sod(NEW.tenant_id);
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM dwp_assert_app_responsibility_sod(OLD.tenant_id);
        RETURN OLD;
    END IF;
    PERFORM dwp_assert_app_responsibility_sod(OLD.tenant_id);
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        PERFORM dwp_assert_app_responsibility_sod(NEW.tenant_id);
    END IF;
    RETURN NEW;
END;
$$;

-- Identity bulk loads are common. Retain the tenant serialization boundary for
-- race safety, but run the full conflict join only when the materialized or
-- reactivated user can actually receive an open control responsibility.
CREATE FUNCTION dwp_enforce_app_responsibility_user_sod()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'dwp-app-responsibility-sod:' || OLD.tenant_id::text, 0));
    END IF;
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'dwp-app-responsibility-sod:' || NEW.tenant_id::text, 0));
    ELSIF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'dwp-app-responsibility-sod:' || NEW.tenant_id::text, 0));
    END IF;

    IF NEW.status = 'ACTIVE' AND EXISTS (
        SELECT 1
          FROM com_admin_role_assignments assignment
         WHERE assignment.tenant_id = NEW.tenant_id
           AND assignment.responsibility_code IN (
               'APP_ACCESS_MANAGER', 'APP_ACCESS_APPROVER',
               'APP_ACCESS_REVIEWER')
           AND assignment.lifecycle_state IN (
               'PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
           AND (assignment.valid_to IS NULL
                OR assignment.valid_to > statement_timestamp())
           AND (
               (assignment.principal_type = 'USER'
                AND assignment.principal_ref = NEW.user_id::text)
               OR (assignment.principal_type = 'GROUP' AND EXISTS (
                   SELECT 1
                     FROM com_groups access_group
                     JOIN com_group_members membership
                       ON membership.tenant_id = access_group.tenant_id
                      AND membership.group_id = access_group.group_id
                    WHERE access_group.tenant_id = assignment.tenant_id
                      AND access_group.group_id::text = assignment.principal_ref
                      AND access_group.status = 'ACTIVE'
                      AND membership.user_id = NEW.user_id)))
    ) THEN
        PERFORM dwp_assert_app_responsibility_sod(NEW.tenant_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_app_responsibility_effective_user_sod
    AFTER INSERT OR UPDATE OR DELETE ON com_admin_role_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_app_responsibility_sod();

CREATE CONSTRAINT TRIGGER trg_app_responsibility_group_membership_sod
    AFTER INSERT OR UPDATE OR DELETE ON com_group_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_app_responsibility_sod();

CREATE CONSTRAINT TRIGGER trg_app_responsibility_group_state_sod
    AFTER UPDATE ON com_groups
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION dwp_enforce_app_responsibility_sod();

CREATE CONSTRAINT TRIGGER trg_app_responsibility_user_state_sod
    AFTER UPDATE ON com_users
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status
          OR OLD.user_id IS DISTINCT FROM NEW.user_id
          OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id)
    EXECUTE FUNCTION dwp_enforce_app_responsibility_user_sod();

-- principal_ref is intentionally polymorphic and therefore cannot carry a USER
-- foreign key. Reject latent orphan USER rows when that identity is later
-- materialized as an active principal.
CREATE CONSTRAINT TRIGGER trg_app_responsibility_user_insert_sod
    AFTER INSERT ON com_users
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION dwp_enforce_app_responsibility_user_sod();

CREATE CONSTRAINT TRIGGER trg_app_responsibility_scope_member_sod
    AFTER INSERT OR UPDATE OR DELETE ON com_admin_resource_set_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_app_responsibility_sod();

CREATE CONSTRAINT TRIGGER trg_app_responsibility_scope_state_sod
    AFTER UPDATE ON com_admin_resource_sets
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.lifecycle_state IS DISTINCT FROM NEW.lifecycle_state)
    EXECUTE FUNCTION dwp_enforce_app_responsibility_sod();

COMMENT ON TABLE sys_app_responsibility_sod_repairs IS
    'Immutable V208 evidence for deterministic effective-user app-responsibility SoD repair.';
COMMENT ON VIEW auth_open_app_responsibility_subjects IS
    'Open control responsibilities expanded to active effective users across direct and group principals.';
