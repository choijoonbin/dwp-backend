-- Governed application-administrator presets bind one scoped responsibility and
-- one-or-more product specialist duties to one independently approved aggregate.

-- Services is the canonical governed product root. The historical
-- APP.EMPLOYEE_SERVICES key remains a compatibility alias; no permission is
-- inferred or granted by registering this template.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement, lifecycle_state)
VALUES ('APP.SERVICES', 'APP', 'Services', 'core.workspace', 'ACTIVE')
ON CONFLICT (resource_key) DO NOTHING;

-- Preset approval and entitlement activation are deliberately separate. The
-- APPROVED state is still non-effective and remains inside every open/SoD
-- uniqueness boundary until an independent fulfiller activates it.
ALTER TABLE com_admin_role_assignments
    DROP CONSTRAINT ck_admin_role_assignment_state,
    DROP CONSTRAINT ck_admin_role_assignment_revocation,
    DROP CONSTRAINT ck_admin_role_assignment_approval;
ALTER TABLE com_admin_role_assignments
    ADD CONSTRAINT ck_admin_role_assignment_state CHECK (lifecycle_state IN (
        'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED')),
    ADD CONSTRAINT ck_admin_role_assignment_revocation CHECK (
        (lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DENIED')
            AND revoked_at IS NULL AND revoked_by IS NULL)
        OR (lifecycle_state IN ('REVOKED', 'EXPIRED') AND revoked_at IS NOT NULL)),
    ADD CONSTRAINT ck_admin_role_assignment_approval CHECK (
        (lifecycle_state IN ('APPROVED', 'ACTIVE')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE'));

ALTER TABLE com_admin_scoped_duty_assignments
    DROP CONSTRAINT ck_scoped_duty_assignment_state,
    DROP CONSTRAINT ck_scoped_duty_assignment_revocation,
    DROP CONSTRAINT ck_scoped_duty_assignment_approval;
ALTER TABLE com_admin_scoped_duty_assignments
    ADD CONSTRAINT ck_scoped_duty_assignment_state CHECK (lifecycle_state IN (
        'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED')),
    ADD CONSTRAINT ck_scoped_duty_assignment_revocation CHECK (
        (lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DENIED')
            AND revoked_at IS NULL AND revoked_by IS NULL)
        OR (lifecycle_state IN ('REVOKED', 'EXPIRED') AND revoked_at IS NOT NULL)),
    ADD CONSTRAINT ck_scoped_duty_assignment_approval CHECK (
        (lifecycle_state IN ('APPROVED', 'ACTIVE')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE'));
DROP INDEX uk_scoped_duty_assignment_open;
CREATE UNIQUE INDEX uk_scoped_duty_assignment_open
    ON com_admin_scoped_duty_assignments (
        tenant_id, principal_type, principal_ref, duty_code, resource_set_id)
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE');

CREATE OR REPLACE FUNCTION dwp_assert_scoped_duty_sod(p_tenant_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    conflict_user_id BIGINT;
    conflict_left UUID;
    conflict_right UUID;
    conflict_policy VARCHAR(100);
BEGIN
    IF p_tenant_id IS NULL THEN
        RETURN;
    END IF;
    PERFORM pg_advisory_xact_lock(
        hashtextextended('dwp-scoped-duty:' || p_tenant_id::text, 0));

    WITH effective_assignments AS (
        SELECT assignment.tenant_id, user_record.user_id,
               assignment.scoped_duty_assignment_id, assignment.duty_code,
               assignment.resource_set_id, catalog.product_resource_key,
               assignment.valid_from, assignment.valid_to
          FROM com_admin_scoped_duty_assignments assignment
          JOIN com_users user_record
            ON user_record.tenant_id = assignment.tenant_id
           AND user_record.user_id::text = assignment.principal_ref
           AND user_record.status IN ('ACTIVE', 'INVITED')
          JOIN sys_admin_scoped_duty_catalog catalog
            ON catalog.duty_code = assignment.duty_code
           AND catalog.lifecycle_state = 'ACTIVE'
          JOIN com_admin_resource_sets resource_set
            ON resource_set.tenant_id = assignment.tenant_id
           AND resource_set.resource_set_id = assignment.resource_set_id
           AND resource_set.lifecycle_state = 'ACTIVE'
         WHERE assignment.tenant_id = p_tenant_id
           AND assignment.principal_type = 'USER'
           AND assignment.principal_ref ~ '^[0-9]+$'
           AND assignment.lifecycle_state IN (
               'PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
           AND (assignment.valid_to IS NULL
                OR assignment.valid_to > CURRENT_TIMESTAMP)
        UNION
        SELECT assignment.tenant_id, membership.user_id,
               assignment.scoped_duty_assignment_id, assignment.duty_code,
               assignment.resource_set_id, catalog.product_resource_key,
               assignment.valid_from, assignment.valid_to
          FROM com_admin_scoped_duty_assignments assignment
          JOIN com_group_members membership
            ON membership.tenant_id = assignment.tenant_id
           AND membership.group_id::text = assignment.principal_ref
          JOIN com_groups access_group
            ON access_group.tenant_id = membership.tenant_id
           AND access_group.group_id = membership.group_id
           AND access_group.status = 'ACTIVE'
          JOIN com_users user_record
            ON user_record.tenant_id = membership.tenant_id
           AND user_record.user_id = membership.user_id
           AND user_record.status IN ('ACTIVE', 'INVITED')
          JOIN sys_admin_scoped_duty_catalog catalog
            ON catalog.duty_code = assignment.duty_code
           AND catalog.lifecycle_state = 'ACTIVE'
          JOIN com_admin_resource_sets resource_set
            ON resource_set.tenant_id = assignment.tenant_id
           AND resource_set.resource_set_id = assignment.resource_set_id
           AND resource_set.lifecycle_state = 'ACTIVE'
         WHERE assignment.tenant_id = p_tenant_id
           AND assignment.principal_type = 'GROUP'
           AND assignment.principal_ref ~ '^[0-9]+$'
           AND assignment.lifecycle_state IN (
               'PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
           AND (assignment.valid_to IS NULL
                OR assignment.valid_to > CURRENT_TIMESTAMP)
    )
    SELECT left_assignment.user_id,
           left_assignment.scoped_duty_assignment_id,
           right_assignment.scoped_duty_assignment_id,
           policy.sod_policy_id
      INTO conflict_user_id, conflict_left, conflict_right, conflict_policy
      FROM effective_assignments left_assignment
      JOIN effective_assignments right_assignment
        ON right_assignment.tenant_id = left_assignment.tenant_id
       AND right_assignment.user_id = left_assignment.user_id
       AND right_assignment.scoped_duty_assignment_id
           > left_assignment.scoped_duty_assignment_id
       AND COALESCE(left_assignment.valid_from, '-infinity'::TIMESTAMPTZ)
           < COALESCE(right_assignment.valid_to, 'infinity'::TIMESTAMPTZ)
       AND COALESCE(right_assignment.valid_from, '-infinity'::TIMESTAMPTZ)
           < COALESCE(left_assignment.valid_to, 'infinity'::TIMESTAMPTZ)
      JOIN sys_admin_scoped_duty_conflicts policy
        ON policy.lifecycle_state = 'ACTIVE'
       AND policy.left_duty_code = LEAST(
           left_assignment.duty_code, right_assignment.duty_code)
       AND policy.right_duty_code = GREATEST(
           left_assignment.duty_code, right_assignment.duty_code)
     WHERE left_assignment.resource_set_id = right_assignment.resource_set_id
        OR EXISTS (
           SELECT 1
             FROM com_admin_resource_set_members left_member
             JOIN com_admin_resource_set_members right_member
               ON right_member.tenant_id = left_member.tenant_id
              AND right_member.resource_type = left_member.resource_type
              AND right_member.resource_key = left_member.resource_key
              AND right_member.lifecycle_state = 'ACTIVE'
              AND right_member.resource_set_id = right_assignment.resource_set_id
            WHERE left_member.tenant_id = left_assignment.tenant_id
              AND left_member.resource_set_id = left_assignment.resource_set_id
              AND left_member.lifecycle_state = 'ACTIVE'
              AND left_member.resource_key <> left_assignment.product_resource_key
              AND right_member.resource_key <> right_assignment.product_resource_key)
     ORDER BY left_assignment.user_id,
              left_assignment.scoped_duty_assignment_id,
              right_assignment.scoped_duty_assignment_id
     LIMIT 1;

    IF conflict_user_id IS NOT NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'Scoped duty separation-of-duties conflict',
            DETAIL = format(
                'tenant=%s user=%s left=%s right=%s policy=%s',
                p_tenant_id, conflict_user_id, conflict_left,
                conflict_right, conflict_policy);
    END IF;
END;
$$;

CREATE TABLE sys_admin_app_preset_catalog (
    preset_code VARCHAR(80) PRIMARY KEY,
    product_key VARCHAR(80) NOT NULL,
    product_resource_key VARCHAR(255) NOT NULL
        REFERENCES sys_tenant_resource_templates(resource_key),
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    responsibility_code VARCHAR(50) NOT NULL
        REFERENCES sys_admin_responsibility_catalog(responsibility_code),
    risk_tier VARCHAR(10) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_app_preset_code CHECK (
        preset_code = UPPER(BTRIM(preset_code))
        AND preset_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_app_preset_product CHECK (
        product_key = LOWER(BTRIM(product_key))
        AND product_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_app_preset_risk CHECK (risk_tier IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_app_preset_state CHECK (
        lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_app_preset_version CHECK (version > 0)
);

CREATE TABLE sys_admin_app_preset_duties (
    preset_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_app_preset_catalog(preset_code),
    duty_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_scoped_duty_catalog(duty_code),
    sort_order INTEGER NOT NULL,
    PRIMARY KEY (preset_code, duty_code),
    UNIQUE (preset_code, sort_order),
    CONSTRAINT ck_app_preset_duty_order CHECK (sort_order > 0)
);

INSERT INTO sys_admin_app_preset_catalog (
    preset_code, product_key, product_resource_key, display_name, description,
    responsibility_code, risk_tier)
VALUES
    ('APPROVAL_DESIGNER', 'approvals', 'APP.APPROVALS', 'Approval designer',
     'Drafts Approval workflows and policy without publication authority.',
     'APP_CONFIG_ADMIN', 'MEDIUM'),
    ('APPROVAL_PUBLISHER', 'approvals', 'APP.APPROVALS', 'Approval publisher',
     'Publishes independently reviewed Approval workflows and policy.',
     'APP_CONFIG_ADMIN', 'HIGH'),
    ('APPROVAL_OPERATOR', 'approvals', 'APP.APPROVALS', 'Approval operator',
     'Operates Approval cases and reads signature integration status.',
     'APP_CONFIG_ADMIN', 'HIGH'),
    ('APPROVAL_AUDITOR', 'approvals', 'APP.APPROVALS', 'Approval auditor',
     'Reads Approval operational evidence without execution authority.',
     'APP_ACCESS_REVIEWER', 'HIGH');

-- Product coverage is explicit and fail closed. These catalog placeholders do
-- not grant anything: a later reviewed migration must add product duties and
-- move one narrowly separated preset to ACTIVE.
INSERT INTO sys_admin_app_preset_catalog (
    preset_code, product_key, product_resource_key, display_name, description,
    responsibility_code, risk_tier, lifecycle_state)
VALUES
    ('COMMUNICATIONS_PRESET_CATALOG_PENDING', 'communications',
     'APP.COMMUNICATIONS', 'Communications specialist presets pending',
     'No scoped specialist capability contract has been approved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('SERVICES_PRESET_CATALOG_PENDING', 'services',
     'APP.SERVICES', 'Services specialist presets pending',
     'No scoped specialist capability contract has been approved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('HCM_PRESET_CATALOG_PENDING', 'hcm',
     'APP.HCM', 'HCM specialist presets pending',
     'HCM duties require target-population and field-bound capability approval.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('DWAION_PRESET_CATALOG_PENDING', 'dwaion',
     'APP.ASK', 'DWAI ON specialist presets pending',
     'No scoped specialist capability contract has been approved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('NOTIFICATIONS_PRESET_CATALOG_PENDING', 'notifications',
     'APP.NOTIFICATIONS', 'Notification specialist presets pending',
     'Template, policy, operations, and audit duties must remain separated.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('SPACES_PRESET_CATALOG_PENDING', 'spaces',
     'APP.SPACES', 'Spaces specialist presets pending',
     'Governance, template, compliance, and access review duties are unapproved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('CALENDAR_PRESET_CATALOG_PENDING', 'calendar',
     'APP.CALENDAR', 'Calendar specialist presets pending',
     'No scoped specialist capability contract has been approved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('WORKPLACE_PRESET_CATALOG_PENDING', 'workplace',
     'APP.WORKPLACE', 'Workplace specialist presets pending',
     'No scoped specialist capability contract has been approved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('MAIL_PRESET_CATALOG_PENDING', 'mail',
     'APP.MAIL', 'Mail specialist presets pending',
     'No scoped specialist capability contract has been approved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT'),
    ('MESSAGING_PRESET_CATALOG_PENDING', 'messaging',
     'APP.MESSAGING', 'Messaging specialist presets pending',
     'No scoped specialist capability contract has been approved.',
     'APP_CONFIG_ADMIN', 'HIGH', 'DRAFT');

INSERT INTO sys_admin_app_preset_duties (preset_code, duty_code, sort_order)
VALUES
    ('APPROVAL_DESIGNER', 'APPROVAL_DESIGN_DRAFT', 10),
    ('APPROVAL_DESIGNER', 'APPROVAL_POLICY_DRAFT', 20),
    ('APPROVAL_PUBLISHER', 'APPROVAL_DESIGN_PUBLISH', 10),
    ('APPROVAL_PUBLISHER', 'APPROVAL_POLICY_PUBLISH', 20),
    ('APPROVAL_OPERATOR', 'APPROVAL_OPERATIONS_EXECUTE', 10),
    ('APPROVAL_OPERATOR', 'APPROVAL_SIGNATURE_READ', 20),
    ('APPROVAL_AUDITOR', 'APPROVAL_OPERATIONS_AUDIT', 10);

CREATE TABLE com_admin_app_preset_assignments (
    app_preset_assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    preset_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_app_preset_catalog(preset_code),
    preset_catalog_version BIGINT NOT NULL,
    principal_type VARCHAR(20) NOT NULL,
    principal_ref VARCHAR(160) NOT NULL,
    resource_set_id UUID NOT NULL,
    responsibility_assignment_id UUID NOT NULL
        REFERENCES com_admin_role_assignments(admin_role_assignment_id),
    assignment_source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    request_channel VARCHAR(24) NOT NULL DEFAULT 'GOVERNANCE',
    idempotency_key VARCHAR(160),
    request_fingerprint CHAR(64),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL',
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ NOT NULL,
    review_due_at TIMESTAMPTZ NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    activated_by BIGINT,
    activated_at TIMESTAMPTZ,
    activation_reason VARCHAR(1000),
    revoked_by BIGINT,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    event_sequence BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_app_preset_assignment_set
        FOREIGN KEY (tenant_id, resource_set_id)
        REFERENCES com_admin_resource_sets(tenant_id, resource_set_id),
    CONSTRAINT ck_app_preset_assignment_principal
        CHECK (principal_type IN ('USER', 'GROUP')),
    CONSTRAINT ck_app_preset_assignment_source
        CHECK (assignment_source IN ('MANUAL', 'IAM', 'PROVISIONING', 'MIGRATION')),
    CONSTRAINT ck_app_preset_request_channel
        CHECK (request_channel IN ('GOVERNANCE', 'SELF_SERVICE')),
    CONSTRAINT ck_app_preset_idempotency CHECK (
        (request_channel = 'SELF_SERVICE'
            AND idempotency_key IS NOT NULL
            AND length(btrim(idempotency_key)) BETWEEN 8 AND 160
            AND request_fingerprint ~ '^[0-9a-f]{64}$')
        OR (request_channel = 'GOVERNANCE'
            AND idempotency_key IS NULL AND request_fingerprint IS NULL)),
    CONSTRAINT ck_app_preset_assignment_state CHECK (lifecycle_state IN (
        'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_app_preset_assignment_window CHECK (
        valid_to > COALESCE(valid_from, created_at)
        AND review_due_at > created_at AND review_due_at <= valid_to),
    CONSTRAINT ck_app_preset_assignment_justification
        CHECK (length(btrim(justification)) >= 10),
    CONSTRAINT ck_app_preset_assignment_approval CHECK (
        (lifecycle_state IN ('APPROVED', 'ACTIVE')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE')),
    CONSTRAINT ck_app_preset_assignment_activation CHECK (
        (lifecycle_state = 'APPROVED' AND activated_by IS NULL
            AND activated_at IS NULL AND valid_from IS NULL)
        OR (lifecycle_state = 'ACTIVE' AND activated_by IS NOT NULL
            AND activated_at IS NOT NULL AND valid_from IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE')),
    CONSTRAINT ck_app_preset_assignment_revocation CHECK (
        (lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DENIED')
            AND revoked_at IS NULL AND revoked_by IS NULL)
        OR (lifecycle_state IN ('REVOKED', 'EXPIRED') AND revoked_at IS NOT NULL)),
    CONSTRAINT ck_app_preset_event_sequence CHECK (event_sequence >= 0),
    CONSTRAINT ck_app_preset_assignment_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_app_preset_assignment_open
    ON com_admin_app_preset_assignments (
        tenant_id, principal_type, principal_ref, preset_code, resource_set_id)
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE');
CREATE INDEX idx_app_preset_assignment_queue
    ON com_admin_app_preset_assignments (
        tenant_id, lifecycle_state, review_due_at, updated_at DESC);
CREATE UNIQUE INDEX uk_app_preset_self_service_idempotency
    ON com_admin_app_preset_assignments (tenant_id, requested_by, idempotency_key)
    WHERE request_channel = 'SELF_SERVICE';

ALTER TABLE com_admin_scoped_duty_assignments
    ADD COLUMN app_preset_assignment_id UUID
        REFERENCES com_admin_app_preset_assignments(app_preset_assignment_id);
CREATE INDEX idx_scoped_duty_preset_assignment
    ON com_admin_scoped_duty_assignments (
        tenant_id, app_preset_assignment_id, lifecycle_state);

-- Existing V91 grants are deliberately not converted to an atomic preset. They
-- remain effective under their proven evidence while a governor reviews them.
INSERT INTO com_admin_scoped_duty_reviews (
    tenant_id, user_id, source_role_code, duty_code, reason_code, evidence)
SELECT assignment.tenant_id, assignment.principal_ref::BIGINT,
       catalog.legacy_role_code, assignment.duty_code,
       'PRESET_WORKFLOW_REVIEW_REQUIRED',
       jsonb_build_object(
           'migration', 'V92',
           'principalType', assignment.principal_type,
           'scopedDutyAssignmentId', assignment.scoped_duty_assignment_id,
           'resourceSetId', assignment.resource_set_id,
           'assignmentSource', assignment.assignment_source,
           'assignmentVersion', assignment.version)
  FROM com_admin_scoped_duty_assignments assignment
  JOIN sys_admin_scoped_duty_catalog catalog
    ON catalog.duty_code = assignment.duty_code
 WHERE assignment.principal_type = 'USER'
   AND assignment.principal_ref ~ '^[0-9]+$'
   AND assignment.lifecycle_state = 'ACTIVE'
   AND assignment.app_preset_assignment_id IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO com_admin_scoped_duty_reviews (
    tenant_id, user_id, source_role_code, duty_code, reason_code, evidence)
SELECT assignment.tenant_id, membership.user_id,
       catalog.legacy_role_code, assignment.duty_code,
       'PRESET_WORKFLOW_REVIEW_REQUIRED',
       jsonb_build_object(
           'migration', 'V92',
           'principalType', assignment.principal_type,
           'principalGroupId', assignment.principal_ref,
           'scopedDutyAssignmentId', assignment.scoped_duty_assignment_id,
           'resourceSetId', assignment.resource_set_id,
           'assignmentSource', assignment.assignment_source,
           'assignmentVersion', assignment.version)
  FROM com_admin_scoped_duty_assignments assignment
  JOIN sys_admin_scoped_duty_catalog catalog
    ON catalog.duty_code = assignment.duty_code
  JOIN com_group_members membership
    ON membership.tenant_id = assignment.tenant_id
   AND membership.group_id::text = assignment.principal_ref
  JOIN com_groups access_group
    ON access_group.tenant_id = membership.tenant_id
   AND access_group.group_id = membership.group_id
   AND access_group.status = 'ACTIVE'
  JOIN com_users user_record
    ON user_record.tenant_id = membership.tenant_id
   AND user_record.user_id = membership.user_id
   AND user_record.status = 'ACTIVE'
 WHERE assignment.principal_type = 'GROUP'
   AND assignment.principal_ref ~ '^[0-9]+$'
   AND assignment.lifecycle_state = 'ACTIVE'
   AND assignment.app_preset_assignment_id IS NULL
ON CONFLICT DO NOTHING;

-- This deferred invariant is the final defence against partial success. The
-- aggregate, its responsibility, and its exact catalog duty set must commit in
-- one matching lifecycle state and on one principal/scope/validity boundary.
CREATE FUNCTION dwp_assert_app_preset_consistency(p_assignment_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    aggregate_record com_admin_app_preset_assignments%ROWTYPE;
    preset_record sys_admin_app_preset_catalog%ROWTYPE;
    responsibility_record com_admin_role_assignments%ROWTYPE;
    expected_duties INTEGER;
    matching_duties INTEGER;
BEGIN
    IF p_assignment_id IS NULL THEN
        RETURN;
    END IF;
    SELECT * INTO aggregate_record
      FROM com_admin_app_preset_assignments
     WHERE app_preset_assignment_id = p_assignment_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT * INTO preset_record
      FROM sys_admin_app_preset_catalog
     WHERE preset_code = aggregate_record.preset_code;
    SELECT * INTO responsibility_record
      FROM com_admin_role_assignments
     WHERE admin_role_assignment_id = aggregate_record.responsibility_assignment_id;

    IF responsibility_record.tenant_id IS DISTINCT FROM aggregate_record.tenant_id
       OR responsibility_record.principal_type IS DISTINCT FROM aggregate_record.principal_type
       OR responsibility_record.principal_ref IS DISTINCT FROM aggregate_record.principal_ref
       OR responsibility_record.resource_set_id IS DISTINCT FROM aggregate_record.resource_set_id
       OR responsibility_record.responsibility_code IS DISTINCT FROM preset_record.responsibility_code
       OR responsibility_record.lifecycle_state IS DISTINCT FROM aggregate_record.lifecycle_state
       OR responsibility_record.valid_to IS DISTINCT FROM aggregate_record.valid_to
       OR responsibility_record.review_due_at IS DISTINCT FROM aggregate_record.review_due_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'App preset responsibility is not atomically aligned';
    END IF;

    SELECT COUNT(*) INTO expected_duties
      FROM sys_admin_app_preset_duties mapping
      JOIN sys_admin_scoped_duty_catalog duty
        ON duty.duty_code = mapping.duty_code
       AND duty.product_key = preset_record.product_key
       AND duty.product_resource_key = preset_record.product_resource_key
     WHERE mapping.preset_code = aggregate_record.preset_code;
    SELECT COUNT(*) INTO matching_duties
      FROM com_admin_scoped_duty_assignments duty
      JOIN sys_admin_app_preset_duties expected
        ON expected.preset_code = aggregate_record.preset_code
       AND expected.duty_code = duty.duty_code
     WHERE duty.app_preset_assignment_id = p_assignment_id
       AND duty.tenant_id = aggregate_record.tenant_id
       AND duty.principal_type = aggregate_record.principal_type
       AND duty.principal_ref = aggregate_record.principal_ref
       AND duty.resource_set_id = aggregate_record.resource_set_id
       AND duty.responsibility_assignment_id = aggregate_record.responsibility_assignment_id
       AND duty.lifecycle_state = aggregate_record.lifecycle_state
       AND duty.valid_to IS NOT DISTINCT FROM aggregate_record.valid_to
       AND duty.review_due_at IS NOT DISTINCT FROM aggregate_record.review_due_at;
    IF expected_duties = 0 OR matching_duties <> expected_duties OR NOT EXISTS (
        SELECT 1 FROM com_admin_resource_set_members member
         WHERE member.tenant_id = aggregate_record.tenant_id
           AND member.resource_set_id = aggregate_record.resource_set_id
           AND member.resource_type = 'APP'
           AND member.resource_key = preset_record.product_resource_key
           AND member.lifecycle_state = 'ACTIVE') OR EXISTS (
        SELECT 1 FROM com_admin_scoped_duty_assignments duty
         WHERE duty.app_preset_assignment_id = p_assignment_id
           AND NOT EXISTS (
               SELECT 1 FROM sys_admin_app_preset_duties expected
                WHERE expected.preset_code = aggregate_record.preset_code
                  AND expected.duty_code = duty.duty_code)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'App preset duty package is incomplete or not atomically aligned';
    END IF;
END;
$$;

CREATE FUNCTION dwp_enforce_app_preset_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM dwp_assert_app_preset_consistency(
        COALESCE(NEW.app_preset_assignment_id, OLD.app_preset_assignment_id));
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION dwp_enforce_app_preset_responsibility_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    aggregate_id UUID;
BEGIN
    FOR aggregate_id IN
        SELECT app_preset_assignment_id
          FROM com_admin_app_preset_assignments
         WHERE responsibility_assignment_id = COALESCE(
             NEW.admin_role_assignment_id, OLD.admin_role_assignment_id)
    LOOP
        PERFORM dwp_assert_app_preset_consistency(aggregate_id);
    END LOOP;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_app_preset_aggregate_consistency
    AFTER INSERT OR UPDATE ON com_admin_app_preset_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_app_preset_consistency();
CREATE CONSTRAINT TRIGGER trg_app_preset_duty_consistency
    AFTER INSERT OR UPDATE OR DELETE ON com_admin_scoped_duty_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_app_preset_consistency();
CREATE CONSTRAINT TRIGGER trg_app_preset_responsibility_consistency
    AFTER UPDATE OR DELETE ON com_admin_role_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_app_preset_responsibility_consistency();

COMMENT ON TABLE sys_admin_app_preset_catalog IS
    'Versioned product-specific minimum app-admin packages; no broad all-admin package exists.';
COMMENT ON TABLE com_admin_app_preset_assignments IS
    'Governed aggregate linking one responsibility and an exact specialist-duty package.';
