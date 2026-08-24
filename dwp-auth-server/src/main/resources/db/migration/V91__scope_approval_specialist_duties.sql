-- CORE-006 scoped specialist duties. Global roles remain permission packages;
-- this ledger binds an effective product duty to an explicit app resource set.

CREATE TABLE sys_admin_scoped_duty_catalog (
    duty_code VARCHAR(80) PRIMARY KEY,
    product_key VARCHAR(80) NOT NULL,
    legacy_role_code VARCHAR(50) NOT NULL,
    product_resource_key VARCHAR(255) NOT NULL
        REFERENCES sys_tenant_resource_templates(resource_key),
    resource_key VARCHAR(160) NOT NULL,
    audit_policy_exception BOOLEAN NOT NULL DEFAULT FALSE,
    risk_tier VARCHAR(10) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_scoped_duty_legacy_role
        FOREIGN KEY (legacy_role_code)
        REFERENCES sys_builtin_role_catalog(role_code),
    CONSTRAINT ck_scoped_duty_code
        CHECK (duty_code = UPPER(BTRIM(duty_code))
            AND duty_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_scoped_duty_product
        CHECK (product_key = LOWER(BTRIM(product_key))
            AND product_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_scoped_duty_resource
        CHECK (resource_key = UPPER(BTRIM(resource_key))
            AND resource_key ~ '^[A-Z][A-Z0-9._]{2,159}$'),
    CONSTRAINT ck_scoped_duty_risk CHECK (risk_tier IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_scoped_duty_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE sys_admin_scoped_duty_capabilities (
    duty_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_scoped_duty_catalog(duty_code),
    capability_contract_key VARCHAR(160) NOT NULL,
    permission_resource_key VARCHAR(255) NOT NULL
        REFERENCES sys_tenant_resource_templates(resource_key),
    permission_code VARCHAR(50) NOT NULL
        REFERENCES com_permissions(code),
    resolved_capability_code VARCHAR(306)
        GENERATED ALWAYS AS (permission_resource_key || ':' || permission_code) STORED,
    PRIMARY KEY (duty_code, capability_contract_key),
    CONSTRAINT uk_scoped_duty_resolved_capability
        UNIQUE (duty_code, permission_resource_key, permission_code),
    CONSTRAINT ck_scoped_duty_capability_key
        CHECK (capability_contract_key = LOWER(BTRIM(capability_contract_key))
            AND capability_contract_key ~ '^[a-z][a-z0-9.-]{2,159}$')
);

CREATE TABLE sys_admin_scoped_duty_conflicts (
    left_duty_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_scoped_duty_catalog(duty_code),
    right_duty_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_scoped_duty_catalog(duty_code),
    sod_policy_id VARCHAR(100) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (left_duty_code, right_duty_code),
    CONSTRAINT ck_scoped_duty_conflict_order CHECK (left_duty_code < right_duty_code),
    CONSTRAINT ck_scoped_duty_conflict_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_scoped_duty_sod_policy
        CHECK (sod_policy_id ~ '^SOD-[A-Z0-9-]+-V[0-9]+$')
);

CREATE TABLE com_admin_scoped_duty_assignments (
    scoped_duty_assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    principal_type VARCHAR(20) NOT NULL,
    principal_ref VARCHAR(160) NOT NULL,
    duty_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_scoped_duty_catalog(duty_code),
    resource_set_id UUID NOT NULL,
    responsibility_assignment_id UUID,
    assignment_source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL',
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    review_due_at TIMESTAMPTZ NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    revoked_by BIGINT,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_scoped_duty_assignment_set
        FOREIGN KEY (tenant_id, resource_set_id)
        REFERENCES com_admin_resource_sets(tenant_id, resource_set_id),
    CONSTRAINT fk_scoped_duty_responsibility
        FOREIGN KEY (responsibility_assignment_id)
        REFERENCES com_admin_role_assignments(admin_role_assignment_id),
    CONSTRAINT ck_scoped_duty_assignment_principal
        CHECK (principal_type IN ('USER', 'GROUP')),
    CONSTRAINT ck_scoped_duty_assignment_source
        CHECK (assignment_source IN (
            'MANUAL', 'GROUP', 'IAM', 'PROVISIONING', 'AGENT', 'MIGRATION')),
    CONSTRAINT ck_scoped_duty_assignment_state
        CHECK (lifecycle_state IN (
            'PENDING_APPROVAL', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_scoped_duty_assignment_window
        CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_scoped_duty_assignment_justification
        CHECK (length(btrim(justification)) >= 10),
    CONSTRAINT ck_scoped_duty_assignment_approval
        CHECK ((lifecycle_state = 'ACTIVE'
                    AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
            OR lifecycle_state <> 'ACTIVE'),
    CONSTRAINT ck_scoped_duty_assignment_revocation
        CHECK ((lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE', 'DENIED')
                    AND revoked_at IS NULL AND revoked_by IS NULL)
            OR (lifecycle_state IN ('REVOKED', 'EXPIRED') AND revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_scoped_duty_assignment_open
    ON com_admin_scoped_duty_assignments (
        tenant_id, principal_type, principal_ref, duty_code, resource_set_id)
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE');
CREATE INDEX idx_scoped_duty_assignment_principal
    ON com_admin_scoped_duty_assignments (
        tenant_id, principal_type, principal_ref, lifecycle_state, valid_to);
CREATE INDEX idx_scoped_duty_assignment_scope
    ON com_admin_scoped_duty_assignments (
        tenant_id, resource_set_id, duty_code, lifecycle_state);

CREATE TABLE com_admin_scoped_duty_reviews (
    scoped_duty_review_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    user_id BIGINT NOT NULL,
    source_role_code VARCHAR(50) NOT NULL,
    duty_code VARCHAR(80) NOT NULL
        REFERENCES sys_admin_scoped_duty_catalog(duty_code),
    reason_code VARCHAR(80) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_by BIGINT,
    resolved_at TIMESTAMPTZ,
    resolution_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_scoped_duty_review_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES com_users(tenant_id, user_id),
    CONSTRAINT ck_scoped_duty_review_state
        CHECK (lifecycle_state IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_scoped_duty_review_resolution
        CHECK ((lifecycle_state = 'OPEN' AND resolved_by IS NULL AND resolved_at IS NULL)
            OR lifecycle_state <> 'OPEN')
);
CREATE UNIQUE INDEX uk_scoped_duty_review_open
    ON com_admin_scoped_duty_reviews (tenant_id, user_id, duty_code, reason_code)
    WHERE lifecycle_state = 'OPEN';

CREATE VIEW auth_effective_scoped_duties AS
WITH duty_subjects AS (
    SELECT assignment.tenant_id, user_record.user_id,
           assignment.scoped_duty_assignment_id, assignment.duty_code,
           assignment.resource_set_id, assignment.responsibility_assignment_id,
           assignment.assignment_source, assignment.valid_to,
           assignment.version AS assignment_version,
           'USER'::VARCHAR AS subject_source_type,
           assignment.principal_ref AS subject_source_ref,
           CONCAT_WS(':', 'duty-user', assignment.version::text,
                     EXTRACT(EPOCH FROM assignment.updated_at)::text) AS subject_revision
      FROM com_admin_scoped_duty_assignments assignment
      JOIN com_users user_record
        ON user_record.tenant_id = assignment.tenant_id
       AND user_record.user_id::text = assignment.principal_ref
       AND user_record.status = 'ACTIVE'
     WHERE assignment.principal_type = 'USER'
       AND assignment.principal_ref ~ '^[0-9]+$'
       AND assignment.lifecycle_state = 'ACTIVE'
       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
    UNION ALL
    SELECT assignment.tenant_id, membership.user_id,
           assignment.scoped_duty_assignment_id, assignment.duty_code,
           assignment.resource_set_id, assignment.responsibility_assignment_id,
           assignment.assignment_source, assignment.valid_to,
           assignment.version AS assignment_version,
           'GROUP'::VARCHAR AS subject_source_type,
           CONCAT_WS(':', assignment.principal_ref,
                     membership.group_member_id::text) AS subject_source_ref,
           CONCAT_WS(':', 'duty-group', assignment.version::text,
                     EXTRACT(EPOCH FROM assignment.updated_at)::text,
                     EXTRACT(EPOCH FROM membership.updated_at)::text,
                     access_group.revision::text, access_group.version::text)
               AS subject_revision
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
       AND user_record.status = 'ACTIVE'
     WHERE assignment.principal_type = 'GROUP'
       AND assignment.principal_ref ~ '^[0-9]+$'
       AND assignment.lifecycle_state = 'ACTIVE'
       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
)
SELECT subject.tenant_id, subject.user_id,
       subject.scoped_duty_assignment_id, subject.duty_code,
       catalog.product_key, catalog.legacy_role_code,
       catalog.product_resource_key, catalog.resource_key,
       catalog.audit_policy_exception, capability.capability_contract_key,
       capability.resolved_capability_code,
       CASE
           WHEN conflict.left_duty_code = subject.duty_code
               THEN conflict.right_duty_code
           ELSE conflict.left_duty_code
       END AS conflicting_duty_code,
       subject.resource_set_id, resource_set.resource_set_key,
       member.resource_type AS member_resource_type,
       member.resource_key AS member_resource_key,
       CASE
           WHEN subject.valid_to IS NULL THEN responsibility.valid_to
           WHEN responsibility.valid_to IS NULL THEN subject.valid_to
           ELSE LEAST(subject.valid_to, responsibility.valid_to)
       END AS valid_to,
       subject.assignment_source, subject.assignment_version,
       resource_set.version AS resource_set_version,
       member.version AS resource_member_version,
       responsibility.version AS responsibility_version,
       subject.subject_source_type, subject.subject_source_ref,
       CONCAT_WS(':', subject.subject_revision,
                 'catalog', catalog.version::text,
                 'set', resource_set.version::text,
                 'member', member.version::text,
                 'responsibility', COALESCE(responsibility.version::text, 'none'))
           AS evidence_revision
  FROM duty_subjects subject
  JOIN sys_admin_scoped_duty_catalog catalog
    ON catalog.duty_code = subject.duty_code
   AND catalog.lifecycle_state = 'ACTIVE'
  JOIN sys_admin_scoped_duty_capabilities capability
    ON capability.duty_code = subject.duty_code
  LEFT JOIN sys_admin_scoped_duty_conflicts conflict
    ON conflict.lifecycle_state = 'ACTIVE'
   AND subject.duty_code IN (conflict.left_duty_code, conflict.right_duty_code)
  JOIN com_admin_resource_sets resource_set
    ON resource_set.tenant_id = subject.tenant_id
   AND resource_set.resource_set_id = subject.resource_set_id
   AND resource_set.lifecycle_state = 'ACTIVE'
  JOIN com_admin_resource_set_members member
    ON member.tenant_id = resource_set.tenant_id
   AND member.resource_set_id = resource_set.resource_set_id
   AND member.lifecycle_state = 'ACTIVE'
  LEFT JOIN com_admin_role_assignments responsibility
    ON responsibility.admin_role_assignment_id = subject.responsibility_assignment_id
   AND responsibility.tenant_id = subject.tenant_id
   AND responsibility.resource_set_id = subject.resource_set_id
   AND responsibility.responsibility_code = 'APP_CONFIG_ADMIN'
   AND responsibility.lifecycle_state = 'ACTIVE'
   AND (responsibility.valid_from IS NULL
        OR responsibility.valid_from <= CURRENT_TIMESTAMP)
   AND (responsibility.valid_to IS NULL
        OR responsibility.valid_to > CURRENT_TIMESTAMP)
   AND ((responsibility.principal_type = 'USER'
          AND responsibility.principal_ref = subject.user_id::text)
     OR (responsibility.principal_type = 'GROUP' AND EXISTS (
         SELECT 1
           FROM com_group_members responsibility_membership
           JOIN com_groups responsibility_group
             ON responsibility_group.tenant_id = responsibility_membership.tenant_id
            AND responsibility_group.group_id = responsibility_membership.group_id
            AND responsibility_group.status = 'ACTIVE'
          WHERE responsibility_membership.tenant_id = responsibility.tenant_id
            AND responsibility_membership.group_id::text = responsibility.principal_ref
            AND responsibility_membership.user_id = subject.user_id)))
 WHERE (catalog.audit_policy_exception
        OR responsibility.admin_role_assignment_id IS NOT NULL)
   AND (resource_set.resource_set_key = 'RS_APPROVALS' OR EXISTS (
       SELECT 1
         FROM com_admin_resource_set_members scoped_member
        WHERE scoped_member.tenant_id = subject.tenant_id
          AND scoped_member.resource_set_id = subject.resource_set_id
          AND scoped_member.lifecycle_state = 'ACTIVE'
          AND scoped_member.resource_key <> catalog.product_resource_key))
   AND EXISTS (
       SELECT 1
         FROM com_admin_resource_set_members product_member
        WHERE product_member.tenant_id = subject.tenant_id
          AND product_member.resource_set_id = subject.resource_set_id
          AND product_member.lifecycle_state = 'ACTIVE'
          AND product_member.resource_key = catalog.product_resource_key);

COMMENT ON VIEW auth_effective_scoped_duties IS
    'Fail-closed user projection of active scoped duties, product members, capability keys, and linked APP_CONFIG_ADMIN evidence.';

INSERT INTO sys_admin_scoped_duty_catalog (
    duty_code, product_key, legacy_role_code, product_resource_key, resource_key,
    audit_policy_exception, risk_tier)
VALUES
    ('APPROVAL_DESIGN_DRAFT', 'approvals', 'APPROVAL_DESIGNER', 'APP.APPROVALS',
     'ADMIN.APPROVAL_DESIGN', FALSE, 'MEDIUM'),
    ('APPROVAL_DESIGN_PUBLISH', 'approvals', 'APPROVAL_PUBLISHER', 'APP.APPROVALS',
     'ADMIN.APPROVAL_DESIGN', FALSE, 'HIGH'),
    ('APPROVAL_POLICY_DRAFT', 'approvals', 'APPROVAL_DESIGNER', 'APP.APPROVALS',
     'ADMIN.APPROVAL_POLICY', FALSE, 'MEDIUM'),
    ('APPROVAL_POLICY_PUBLISH', 'approvals', 'APPROVAL_PUBLISHER', 'APP.APPROVALS',
     'ADMIN.APPROVAL_POLICY', FALSE, 'HIGH'),
    ('APPROVAL_OPERATIONS_EXECUTE', 'approvals', 'APPROVAL_OPERATOR', 'APP.APPROVALS',
     'ADMIN.APPROVAL_OPERATIONS', FALSE, 'HIGH'),
    ('APPROVAL_OPERATIONS_AUDIT', 'approvals', 'AUDITOR', 'APP.APPROVALS',
     'ADMIN.APPROVAL_OPERATIONS', TRUE, 'HIGH'),
    ('APPROVAL_SIGNATURE_READ', 'approvals', 'APPROVAL_OPERATOR', 'APP.APPROVALS',
     'ADMIN.APPROVAL_SIGNATURE', FALSE, 'LOW');

INSERT INTO com_permissions (code, name)
VALUES ('PUBLISH', 'Publish')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_admin_scoped_duty_capabilities (
    duty_code, capability_contract_key, permission_resource_key, permission_code)
VALUES
    ('APPROVAL_DESIGN_DRAFT', 'approvals.design.read',
     'ADMIN.APPROVAL_DESIGN', 'VIEW'),
    ('APPROVAL_DESIGN_DRAFT', 'approvals.design.create',
     'ADMIN.APPROVAL_DESIGN', 'CREATE'),
    ('APPROVAL_DESIGN_DRAFT', 'approvals.design.update',
     'ADMIN.APPROVAL_DESIGN', 'UPDATE'),
    ('APPROVAL_DESIGN_PUBLISH', 'approvals.design.read',
     'ADMIN.APPROVAL_DESIGN', 'VIEW'),
    ('APPROVAL_DESIGN_PUBLISH', 'approvals.design.publish',
     'ADMIN.APPROVAL_DESIGN', 'PUBLISH'),
    ('APPROVAL_POLICY_DRAFT', 'approvals.policy.read',
     'ADMIN.APPROVAL_POLICY', 'VIEW'),
    ('APPROVAL_POLICY_DRAFT', 'approvals.policy.update',
     'ADMIN.APPROVAL_POLICY', 'UPDATE'),
    ('APPROVAL_POLICY_PUBLISH', 'approvals.policy.read',
     'ADMIN.APPROVAL_POLICY', 'VIEW'),
    ('APPROVAL_POLICY_PUBLISH', 'approvals.policy.publish',
     'ADMIN.APPROVAL_POLICY', 'PUBLISH'),
    ('APPROVAL_OPERATIONS_EXECUTE', 'approvals.operations.read',
     'ADMIN.APPROVAL_OPERATIONS', 'VIEW'),
    ('APPROVAL_OPERATIONS_EXECUTE', 'approvals.operations.execute',
     'ADMIN.APPROVAL_OPERATIONS', 'EXECUTE'),
    ('APPROVAL_OPERATIONS_AUDIT', 'approvals.audit.operations.read',
     'ADMIN.APPROVAL_OPERATIONS', 'VIEW'),
    ('APPROVAL_SIGNATURE_READ', 'approvals.signature.read',
     'ADMIN.APPROVAL_SIGNATURE', 'VIEW');

INSERT INTO sys_admin_scoped_duty_conflicts (
    left_duty_code, right_duty_code, sod_policy_id)
VALUES
    (LEAST('APPROVAL_DESIGN_DRAFT', 'APPROVAL_DESIGN_PUBLISH'),
     GREATEST('APPROVAL_DESIGN_DRAFT', 'APPROVAL_DESIGN_PUBLISH'),
     'SOD-APR-DESIGN-PUBLISH-V1'),
    (LEAST('APPROVAL_POLICY_DRAFT', 'APPROVAL_POLICY_PUBLISH'),
     GREATEST('APPROVAL_POLICY_DRAFT', 'APPROVAL_POLICY_PUBLISH'),
     'SOD-APR-POLICY-PUBLISH-V1'),
    (LEAST('APPROVAL_OPERATIONS_AUDIT', 'APPROVAL_OPERATIONS_EXECUTE'),
     GREATEST('APPROVAL_OPERATIONS_AUDIT', 'APPROVAL_OPERATIONS_EXECUTE'),
     'SOD-APR-OPS-AUDIT-V1');

-- Only users whose effective specialist role and one active, canonical
-- APP_CONFIG_ADMIN responsibility can be proven are backfilled. The duty keeps
-- a fail-closed link to that exact responsibility assignment.
WITH effective_specialist_roles AS (
    SELECT membership.tenant_id, membership.user_id, role.code AS role_code
      FROM com_role_members membership
      JOIN com_roles role
        ON role.tenant_id = membership.tenant_id
       AND role.role_id = membership.role_id
       AND role.status = 'ACTIVE'
     WHERE role.code IN (
         'APPROVAL_DESIGNER', 'APPROVAL_PUBLISHER', 'APPROVAL_OPERATOR', 'AUDITOR')
    UNION
    SELECT assignment.tenant_id, membership.user_id, role.code
      FROM com_group_role_assignments assignment
      JOIN com_group_members membership
        ON membership.tenant_id = assignment.tenant_id
       AND membership.group_id = assignment.group_id
      JOIN com_groups access_group
        ON access_group.tenant_id = membership.tenant_id
       AND access_group.group_id = membership.group_id
       AND access_group.status = 'ACTIVE'
      JOIN com_roles role
        ON role.tenant_id = assignment.tenant_id
       AND role.role_id = assignment.role_id
       AND role.status = 'ACTIVE'
     WHERE role.code IN (
         'APPROVAL_DESIGNER', 'APPROVAL_PUBLISHER', 'APPROVAL_OPERATOR', 'AUDITOR')
       AND assignment.lifecycle_state = 'ACTIVE'
       AND assignment.assignment_type = 'ACTIVE'
       AND assignment.scope_type = 'TENANT'
       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
),
effective_config AS (
    SELECT specialist.tenant_id, specialist.user_id, specialist.role_code,
           responsibility.admin_role_assignment_id,
           responsibility.resource_set_id, responsibility.valid_from,
           responsibility.valid_to, responsibility.review_due_at,
           responsibility.approved_by, responsibility.approved_at,
           responsibility.created_by,
           ROW_NUMBER() OVER (
               PARTITION BY specialist.tenant_id, specialist.user_id, specialist.role_code
               ORDER BY responsibility.valid_to DESC NULLS FIRST,
                        responsibility.admin_role_assignment_id) AS evidence_rank
      FROM effective_specialist_roles specialist
      JOIN com_admin_resource_sets resource_set
        ON resource_set.tenant_id = specialist.tenant_id
       AND resource_set.resource_set_key = 'RS_APPROVALS'
       AND resource_set.lifecycle_state = 'ACTIVE'
      JOIN com_admin_role_assignments responsibility
        ON responsibility.tenant_id = specialist.tenant_id
       AND responsibility.resource_set_id = resource_set.resource_set_id
       AND responsibility.responsibility_code = 'APP_CONFIG_ADMIN'
       AND responsibility.lifecycle_state = 'ACTIVE'
       AND (responsibility.valid_from IS NULL
            OR responsibility.valid_from <= CURRENT_TIMESTAMP)
       AND (responsibility.valid_to IS NULL
            OR responsibility.valid_to > CURRENT_TIMESTAMP)
       AND ((responsibility.principal_type = 'USER'
              AND responsibility.principal_ref = specialist.user_id::text)
         OR (responsibility.principal_type = 'GROUP' AND EXISTS (
             SELECT 1
               FROM com_group_members config_membership
               JOIN com_groups config_group
                 ON config_group.tenant_id = config_membership.tenant_id
                AND config_group.group_id = config_membership.group_id
                AND config_group.status = 'ACTIVE'
              WHERE config_membership.tenant_id = responsibility.tenant_id
                AND config_membership.group_id::text = responsibility.principal_ref
                AND config_membership.user_id = specialist.user_id)))
     WHERE specialist.role_code <> 'AUDITOR'
),
role_duties(role_code, duty_code) AS (
    VALUES
        ('APPROVAL_DESIGNER', 'APPROVAL_DESIGN_DRAFT'),
        ('APPROVAL_DESIGNER', 'APPROVAL_POLICY_DRAFT'),
        ('APPROVAL_PUBLISHER', 'APPROVAL_DESIGN_PUBLISH'),
        ('APPROVAL_PUBLISHER', 'APPROVAL_POLICY_PUBLISH'),
        ('APPROVAL_OPERATOR', 'APPROVAL_OPERATIONS_EXECUTE'),
        ('APPROVAL_OPERATOR', 'APPROVAL_SIGNATURE_READ')
)
INSERT INTO com_admin_scoped_duty_assignments (
    scoped_duty_assignment_id, tenant_id, principal_type, principal_ref,
    duty_code, resource_set_id, responsibility_assignment_id,
    assignment_source, lifecycle_state, valid_from, valid_to, review_due_at,
    justification, requested_by, approved_by, approved_at, decision_reason,
    created_by, updated_by)
SELECT gen_random_uuid(), evidence.tenant_id, 'USER', evidence.user_id::text,
       duty.duty_code, evidence.resource_set_id,
       evidence.admin_role_assignment_id, 'MIGRATION', 'ACTIVE',
       COALESCE(evidence.valid_from, CURRENT_TIMESTAMP), evidence.valid_to,
       evidence.review_due_at,
       'Backfilled from one proven Approval specialist role and scoped responsibility.',
       evidence.created_by, evidence.approved_by, evidence.approved_at,
       'CORE-006 scoped-duty migration with exact responsibility evidence.',
       evidence.created_by, evidence.created_by
 FROM effective_config evidence
  JOIN role_duties duty ON duty.role_code = evidence.role_code
 WHERE evidence.evidence_rank = 1
   AND NOT EXISTS (
       SELECT 1
         FROM effective_specialist_roles other_specialist
         JOIN role_duties other_duty
           ON other_duty.role_code = other_specialist.role_code
         JOIN sys_admin_scoped_duty_conflicts conflict
           ON conflict.lifecycle_state = 'ACTIVE'
          AND conflict.left_duty_code = LEAST(duty.duty_code, other_duty.duty_code)
          AND conflict.right_duty_code = GREATEST(duty.duty_code, other_duty.duty_code)
        WHERE other_specialist.tenant_id = evidence.tenant_id
          AND other_specialist.user_id = evidence.user_id
          AND other_duty.duty_code <> duty.duty_code)
ON CONFLICT DO NOTHING;

-- Every legacy specialist role without an effective scoped duty is review-only.
-- In particular AUDITOR never receives a scope merely from its tenant-wide role.
WITH effective_specialist_roles AS (
    SELECT membership.tenant_id, membership.user_id, role.code AS role_code
      FROM com_role_members membership
      JOIN com_roles role
        ON role.tenant_id = membership.tenant_id
       AND role.role_id = membership.role_id
       AND role.status = 'ACTIVE'
     WHERE role.code IN (
         'APPROVAL_DESIGNER', 'APPROVAL_PUBLISHER', 'APPROVAL_OPERATOR', 'AUDITOR')
    UNION
    SELECT assignment.tenant_id, membership.user_id, role.code
      FROM com_group_role_assignments assignment
      JOIN com_group_members membership
        ON membership.tenant_id = assignment.tenant_id
       AND membership.group_id = assignment.group_id
      JOIN com_groups access_group
        ON access_group.tenant_id = membership.tenant_id
       AND access_group.group_id = membership.group_id
       AND access_group.status = 'ACTIVE'
      JOIN com_roles role
        ON role.tenant_id = assignment.tenant_id
       AND role.role_id = assignment.role_id
       AND role.status = 'ACTIVE'
     WHERE role.code IN (
         'APPROVAL_DESIGNER', 'APPROVAL_PUBLISHER', 'APPROVAL_OPERATOR', 'AUDITOR')
       AND assignment.lifecycle_state = 'ACTIVE'
       AND assignment.assignment_type = 'ACTIVE'
       AND assignment.scope_type = 'TENANT'
       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
),
role_duties(role_code, duty_code) AS (
    VALUES
        ('APPROVAL_DESIGNER', 'APPROVAL_DESIGN_DRAFT'),
        ('APPROVAL_DESIGNER', 'APPROVAL_POLICY_DRAFT'),
        ('APPROVAL_PUBLISHER', 'APPROVAL_DESIGN_PUBLISH'),
        ('APPROVAL_PUBLISHER', 'APPROVAL_POLICY_PUBLISH'),
        ('APPROVAL_OPERATOR', 'APPROVAL_OPERATIONS_EXECUTE'),
        ('APPROVAL_OPERATOR', 'APPROVAL_SIGNATURE_READ'),
        ('AUDITOR', 'APPROVAL_OPERATIONS_AUDIT')
)
INSERT INTO com_admin_scoped_duty_reviews (
    tenant_id, user_id, source_role_code, duty_code, reason_code, evidence)
SELECT specialist.tenant_id, specialist.user_id, specialist.role_code,
       duty.duty_code,
       CASE WHEN specialist.role_code = 'AUDITOR'
            THEN 'EXPLICIT_AUDIT_SCOPE_REQUIRED'
            ELSE 'PROVABLE_APP_CONFIG_SCOPE_REQUIRED' END,
       jsonb_build_object('sourceRoleCode', specialist.role_code,
                          'migration', 'V91')
  FROM effective_specialist_roles specialist
  JOIN role_duties duty ON duty.role_code = specialist.role_code
 WHERE NOT EXISTS (
       SELECT 1
         FROM com_admin_scoped_duty_assignments scoped
        WHERE scoped.tenant_id = specialist.tenant_id
          AND scoped.principal_type = 'USER'
          AND scoped.principal_ref = specialist.user_id::text
          AND scoped.duty_code = duty.duty_code
          AND scoped.lifecycle_state = 'ACTIVE')
ON CONFLICT DO NOTHING;

-- Tenant-wide role conflicts remain ACTIVE while rollout states 000/100 still
-- accept the legacy permission path. Scoped duties are an independent exact
-- capability source, so disjoint assignments do not need conflicting global
-- roles. A later forward migration may retire the old conflicts only after the
-- legacy permission fallback has been removed and rollback rehearsed.

-- The service performs the same prospective check, while these deferred data
-- guards protect direct SQL, group membership, resource membership, and
-- concurrent mutations. The tenant advisory lock serializes each check.
CREATE FUNCTION dwp_assert_scoped_duty_sod(p_tenant_id BIGINT)
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
           AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE')
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
           AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE')
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
              -- Every set may carry the same product root as eligibility evidence;
              -- overlap between distinct sets is their shared governed child scope.
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

CREATE FUNCTION dwp_enforce_scoped_duty_sod()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM dwp_assert_scoped_duty_sod(COALESCE(NEW.tenant_id, OLD.tenant_id));
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_scoped_duty_assignment_sod
    AFTER INSERT OR UPDATE OR DELETE ON com_admin_scoped_duty_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_sod();
CREATE CONSTRAINT TRIGGER trg_scoped_duty_member_sod
    AFTER INSERT OR UPDATE OR DELETE ON com_admin_resource_set_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_sod();
CREATE CONSTRAINT TRIGGER trg_scoped_duty_group_member_sod
    AFTER INSERT OR UPDATE OR DELETE ON com_group_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_sod();
CREATE CONSTRAINT TRIGGER trg_scoped_duty_group_sod
    AFTER UPDATE ON com_groups
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_sod();
CREATE CONSTRAINT TRIGGER trg_scoped_duty_user_sod
    AFTER UPDATE ON com_users
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_sod();
CREATE CONSTRAINT TRIGGER trg_scoped_duty_resource_set_sod
    AFTER UPDATE ON com_admin_resource_sets
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_sod();

CREATE FUNCTION dwp_enforce_scoped_duty_catalog_sod()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM dwp_assert_scoped_duty_sod(tenant.tenant_id)
      FROM com_tenants tenant;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_scoped_duty_conflict_policy_sod
    AFTER INSERT OR UPDATE OR DELETE ON sys_admin_scoped_duty_conflicts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_catalog_sod();
CREATE CONSTRAINT TRIGGER trg_scoped_duty_catalog_lifecycle_sod
    AFTER UPDATE ON sys_admin_scoped_duty_catalog
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION dwp_enforce_scoped_duty_catalog_sod();

COMMENT ON TABLE com_admin_scoped_duty_assignments IS
    'Versioned principal-to-specialist-duty assignments bound to explicit app resource sets.';
COMMENT ON TABLE com_admin_scoped_duty_reviews IS
    'Fail-closed review queue for legacy specialist roles without provable scoped duty evidence.';
