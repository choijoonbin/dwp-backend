-- Declare the immutable R7 Batch-04 PAY-003 v54 carrier and its tenant-governed
-- statutory-insurance workbenches. The feature remains default OFF: this
-- migration creates no global activation, tenant enrollment, assignment, role
-- grant, or broad PAYROLL_ADMIN authority.

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('ACTION.HRIS_STATUTORY_FILING', 'ACTION',
     'HRIS statutory country-pack activation', 'core.people')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_admin_responsibility_catalog (
    responsibility_code, display_name, description, resource_type, risk_tier,
    allowed_principal_types, label_i18n, sort_order, lifecycle_state)
VALUES
    ('HRIS_DUTY_STATUTORY_OPERATOR', 'HRIS statutory operator',
     'Activates an independently approved country pack for an assigned legal-entity and pay-group population.',
     'APP', 'L3', '["USER","GROUP"]'::jsonb,
     '{"ko":"법정 급여 팩 운영 담당자","en":"HRIS statutory operator"}'::jsonb,
     120, 'ACTIVE')
ON CONFLICT (responsibility_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    resource_type = EXCLUDED.resource_type,
    risk_tier = EXCLUDED.risk_tier,
    allowed_principal_types = EXCLUDED.allowed_principal_types,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_admin_scoped_duty_catalog (
    duty_code, product_key, legacy_role_code, product_resource_key, resource_key,
    audit_policy_exception, risk_tier, lifecycle_state)
VALUES
    ('HRIS_DUTY_STATUTORY_OPERATOR', 'hcm', 'PAYROLL_ADMIN', 'APP.HCM',
     'ACTION.HRIS_STATUTORY_FILING', FALSE, 'HIGH', 'ACTIVE')
ON CONFLICT (duty_code) DO UPDATE SET
    product_key = EXCLUDED.product_key,
    legacy_role_code = EXCLUDED.legacy_role_code,
    product_resource_key = EXCLUDED.product_resource_key,
    resource_key = EXCLUDED.resource_key,
    audit_policy_exception = EXCLUDED.audit_policy_exception,
    risk_tier = EXCLUDED.risk_tier,
    lifecycle_state = 'ACTIVE',
    version = sys_admin_scoped_duty_catalog.version + 1,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_admin_scoped_duty_capabilities (
    duty_code, capability_contract_key, permission_resource_key, permission_code)
VALUES
    ('HRIS_DUTY_STATUTORY_OPERATOR', 'hcm.operations.statutory.execute',
     'ACTION.HRIS_STATUTORY_FILING', 'EXECUTE')
ON CONFLICT (duty_code, capability_contract_key) DO UPDATE SET
    permission_resource_key = EXCLUDED.permission_resource_key,
    permission_code = EXCLUDED.permission_code;

-- PACK_INSTALLER_NE_STATUTORY_APPROVER is an object-state SoD predicate owned
-- by Payroll. No static duty conflict is invented here: the owner compares the
-- immutable installer and approved-receipt approver facts at decision time.
INSERT INTO sys_admin_app_preset_catalog (
    preset_code, product_key, product_resource_key, display_name, description,
    responsibility_code, risk_tier, lifecycle_state, version)
VALUES
    ('HRIS_STATUTORY_OPERATOR', 'hcm', 'APP.HCM',
     'HRIS statutory operator',
     'Activates approved country packs for an independently assigned payroll population.',
     'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1)
ON CONFLICT (preset_code) DO UPDATE SET
    product_key = EXCLUDED.product_key,
    product_resource_key = EXCLUDED.product_resource_key,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    responsibility_code = EXCLUDED.responsibility_code,
    risk_tier = EXCLUDED.risk_tier,
    lifecycle_state = 'ACTIVE',
    version = sys_admin_app_preset_catalog.version + 1,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_admin_app_preset_duties (preset_code, duty_code, sort_order)
VALUES ('HRIS_STATUTORY_OPERATOR', 'HRIS_DUTY_STATUTORY_OPERATOR', 10)
ON CONFLICT (preset_code, duty_code) DO UPDATE SET
    sort_order = EXCLUDED.sort_order;

-- V262 admitted the exact HRIS contract-issuer APP.HCM-only population boundary.
-- PAY-003 uses the same independently owner-derived population shape, so extend
-- that closed set with only the statutory duty/capability/resource tuple. Every
-- other specialist duty still requires a non-product resource-set member.
CREATE OR REPLACE VIEW auth_effective_scoped_duties AS
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
   AND (resource_set.resource_set_key = 'RS_APPROVALS'
        OR (subject.duty_code = 'HRIS_DUTY_CONTRACT_ISSUER'
            AND catalog.product_key = 'hcm'
            AND catalog.product_resource_key = 'APP.HCM'
            AND catalog.resource_key = 'ACTION.HRIS_EMPLOYMENT_CONTRACT'
            AND capability.capability_contract_key = 'hcm.operations.contract.issue'
            AND capability.resolved_capability_code =
                'ACTION.HRIS_EMPLOYMENT_CONTRACT:ISSUE')
        OR (subject.duty_code = 'HRIS_DUTY_STATUTORY_OPERATOR'
            AND catalog.product_key = 'hcm'
            AND catalog.product_resource_key = 'APP.HCM'
            AND catalog.resource_key = 'ACTION.HRIS_STATUTORY_FILING'
            AND capability.capability_contract_key =
                'hcm.operations.statutory.execute'
            AND capability.resolved_capability_code =
                'ACTION.HRIS_STATUTORY_FILING:EXECUTE')
        OR EXISTS (
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
    'Fail-closed user projection of active scoped duties, including exact HRIS contract-issuer and statutory-operator boundaries whose populations are independently owner-derived.';

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key = 'ACTION.HRIS_STATUTORY_FILING'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    54,
    '1a199b3ec84b6d6c16aaec57c9be0b09092c5f81e0c5566f893c4dbe0e0deed4',
    'product-surfaces-v1.bundle-v54.generated.json',
    'DRAFT',
    FALSE
);

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_PAYROLL_STATUTORY_INSURANCE', 1, 'hcm', 'APP.HCM', 54, 'OFF', 'PAY');

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.configuration.statutory.page', 'HRIS_PAYROLL_STATUTORY_INSURANCE'),
    ('route.hcm.configuration.statutory.data', 'HRIS_PAYROLL_STATUTORY_INSURANCE'),
    ('route.hcm.operations.pay-tax-social-insurance.page', 'HRIS_PAYROLL_STATUTORY_INSURANCE'),
    ('route.hcm.operations.pay-tax-social-insurance.data', 'HRIS_PAYROLL_STATUTORY_INSURANCE'),
    ('route.hcm.operations.country-pack-activate.action', 'HRIS_PAYROLL_STATUTORY_INSURANCE');

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.configuration.statutory.page', 'HRIS_PAYROLL_STATUTORY_INSURANCE', 1,
     'hcm', 'APP.HCM', 54, 'PAGE', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.statutory.data', 'HRIS_PAYROLL_STATUTORY_INSURANCE', 1,
     'hcm', 'APP.HCM', 54, 'DATA', 'PRODUCT', 'hcm.management'),
    ('route.hcm.operations.pay-tax-social-insurance.page', 'HRIS_PAYROLL_STATUTORY_INSURANCE', 1,
     'hcm', 'APP.HCM', 54, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.pay-tax-social-insurance.data', 'HRIS_PAYROLL_STATUTORY_INSURANCE', 1,
     'hcm', 'APP.HCM', 54, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.country-pack-activate.action', 'HRIS_PAYROLL_STATUTORY_INSURANCE', 1,
     'hcm', 'APP.HCM', 54, 'ACTION', 'PRODUCT', 'hcm.operations');
