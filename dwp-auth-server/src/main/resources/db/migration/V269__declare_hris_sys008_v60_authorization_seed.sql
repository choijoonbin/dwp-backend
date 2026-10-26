-- Declare the immutable SYS-008 v60 carrier and catalog-only readiness for the
-- HRIS integration control plane. The feature remains baseline OFF. This migration
-- creates no tenant activation, enrollment, assignment, population, or permission grant.

INSERT INTO com_permissions (code, name, created_by, updated_by)
VALUES
    ('UPDATE', 'Update', 1, 1),
    ('EXECUTE', 'Execute', 1, 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('CONFIG.HRIS_INTEGRATION', 'CONFIG',
     'HRIS integration configuration', 'core.people'),
    ('ACTION.HRIS_INTEGRATION_CONTROL_PLANE', 'ACTION',
     'HRIS integration control-plane execution', 'core.people')
ON CONFLICT (resource_key) DO NOTHING;

INSERT INTO sys_admin_responsibility_catalog (
    responsibility_code, display_name, description, resource_type, risk_tier,
    allowed_principal_types, label_i18n, sort_order, lifecycle_state)
VALUES
    ('HRIS_DUTY_INTEGRATION_AUTHOR', 'HRIS integration author',
     'Authors and validates immutable connector definitions and mapping versions for independently assigned connector and legal-entity populations.',
     'APP', 'L3', '["USER","GROUP"]'::jsonb,
     '{"ko":"HRIS 연계 작성 담당자","en":"HRIS integration author"}'::jsonb,
     140, 'ACTIVE'),
    ('HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR',
     'HRIS integration control-plane executor',
     'Independently approves, activates, executes, reconciles, retries, cancels, or compensates governed HRIS connector work for assigned connector and legal-entity populations.',
     'APP', 'L3', '["USER","GROUP"]'::jsonb,
     '{"ko":"HRIS 연계 통제 실행 담당자","en":"HRIS integration control-plane executor"}'::jsonb,
     150, 'ACTIVE')
ON CONFLICT (responsibility_code) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_catalog (
    duty_code, product_key, legacy_role_code, product_resource_key, resource_key,
    audit_policy_exception, risk_tier, lifecycle_state)
VALUES
    ('HRIS_DUTY_INTEGRATION_AUTHOR', 'hcm', 'HR_ADMIN', 'APP.HCM',
     'CONFIG.HRIS_INTEGRATION', FALSE, 'HIGH', 'ACTIVE'),
    ('HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR', 'hcm', 'HR_ADMIN', 'APP.HCM',
     'ACTION.HRIS_INTEGRATION_CONTROL_PLANE', FALSE, 'HIGH', 'ACTIVE')
ON CONFLICT (duty_code) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_capabilities (
    duty_code, capability_contract_key, permission_resource_key, permission_code)
VALUES
    ('HRIS_DUTY_INTEGRATION_AUTHOR', 'hcm.integration.definition.update',
     'CONFIG.HRIS_INTEGRATION', 'UPDATE'),
    ('HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR',
     'hcm.integration.control-plane.execute',
     'ACTION.HRIS_INTEGRATION_CONTROL_PLANE', 'EXECUTE')
ON CONFLICT (duty_code, capability_contract_key) DO NOTHING;

INSERT INTO sys_admin_app_preset_catalog (
    preset_code, product_key, product_resource_key, display_name, description,
    responsibility_code, risk_tier, lifecycle_state, version)
VALUES
    ('HRIS_INTEGRATION_AUTHOR', 'hcm', 'APP.HCM',
     'HRIS integration author',
     'Authors and validates immutable connector definitions and mapping versions for independently assigned connector and legal-entity populations.',
     'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1),
    ('HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR', 'hcm', 'APP.HCM',
     'HRIS integration control-plane executor',
     'Independently approves, activates, executes, reconciles, retries, cancels, or compensates governed HRIS connector work for assigned connector and legal-entity populations.',
     'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1)
ON CONFLICT (preset_code) DO NOTHING;

INSERT INTO sys_admin_app_preset_duties (preset_code, duty_code, sort_order)
VALUES
    ('HRIS_INTEGRATION_AUTHOR', 'HRIS_DUTY_INTEGRATION_AUTHOR', 10),
    ('HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR',
     'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR', 10)
ON CONFLICT (preset_code, duty_code) DO NOTHING;

-- Preserve the complete V268 projection and append only the two exact SYS-008
-- duty/capability branches. Neither branch aliases hcm.integration.execute nor
-- hcm.integration.workforce-ingestion.execute.
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
        OR (subject.duty_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
            AND catalog.product_key = 'hcm'
            AND catalog.product_resource_key = 'APP.HCM'
            AND catalog.resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
            AND capability.capability_contract_key =
                'hcm.integration.workforce-ingestion.execute'
            AND capability.resolved_capability_code =
                'ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE')
        OR (subject.duty_code = 'HRIS_DUTY_INTEGRATION_AUTHOR'
            AND catalog.product_key = 'hcm'
            AND catalog.product_resource_key = 'APP.HCM'
            AND catalog.resource_key = 'CONFIG.HRIS_INTEGRATION'
            AND capability.capability_contract_key =
                'hcm.integration.definition.update'
            AND capability.resolved_capability_code =
                'CONFIG.HRIS_INTEGRATION:UPDATE')
        OR (subject.duty_code = 'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR'
            AND catalog.product_key = 'hcm'
            AND catalog.product_resource_key = 'APP.HCM'
            AND catalog.resource_key = 'ACTION.HRIS_INTEGRATION_CONTROL_PLANE'
            AND capability.capability_contract_key =
                'hcm.integration.control-plane.execute'
            AND capability.resolved_capability_code =
                'ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE')
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
    'Fail-closed user projection of active scoped duties, including exact HRIS contract-issuer, statutory-operator, workforce-ingestion, integration-author, and integration-control-plane boundaries whose populations are independently owner-derived.';

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key IN (
           'CONFIG.HRIS_INTEGRATION',
           'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO NOTHING;

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces', 60,
    '405ac7c88d30a5730faa40c730968fffc873bd1a3679e4997532f0ea9ff1c47c',
    'product-surfaces-v1.bundle-v60.generated.json', 'DRAFT', FALSE)
ON CONFLICT (bundle_key, version) DO NOTHING;

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_INTEGRATION_CONTROL_PLANE', 1, 'hcm', 'APP.HCM', 60, 'OFF', 'SYS')
ON CONFLICT (feature_key, policy_version) DO NOTHING;

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.configuration.settings-integrations.page', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.dwp-integrations.data', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-catalog.data', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-definition-create.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-test.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-mapping-dry-run.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-execution-create.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-execution-detail.data', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-definition-validate.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-definition-submit.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-definition-approve.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-definition-activate.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-mapping-version-create.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-mapping-version-validate.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-mapping-version-submit.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-mapping-version-approve.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-mapping-version-activate.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-execution-reconcile.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-execution-retry.action', 'HRIS_INTEGRATION_CONTROL_PLANE'),
    ('route.hcm.configuration.connector-execution-cancel.action', 'HRIS_INTEGRATION_CONTROL_PLANE')
ON CONFLICT (route_contract_key) DO NOTHING;

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.configuration.settings-integrations.page', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'PAGE', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.dwp-integrations.data', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'DATA', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-catalog.data', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'DATA', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-definition-create.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-test.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-mapping-dry-run.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-execution-create.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-execution-detail.data', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'DATA', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-definition-validate.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-definition-submit.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-definition-approve.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-definition-activate.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-mapping-version-create.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-mapping-version-validate.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-mapping-version-submit.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-mapping-version-approve.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-mapping-version-activate.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-execution-reconcile.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-execution-retry.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.connector-execution-cancel.action', 'HRIS_INTEGRATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 60, 'ACTION', 'PRODUCT', 'hcm.management')
ON CONFLICT (route_contract_key, feature_key, policy_version) DO NOTHING;

-- Exact reapplication is a no-op. A reused key with any semantic drift aborts
-- the transaction; catalog readiness must remain authority-neutral.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM com_permissions
         WHERE code = 'UPDATE' AND name = 'Update'
    ) OR NOT EXISTS (
        SELECT 1 FROM com_permissions
         WHERE code = 'EXECUTE' AND name = 'Execute'
    ) THEN
        RAISE EXCEPTION 'V269 conflict: permissions differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_tenant_resource_templates
         WHERE resource_key IN (
             'CONFIG.HRIS_INTEGRATION',
             'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')) <> 2
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('CONFIG.HRIS_INTEGRATION', 'CONFIG',
                  'HRIS integration configuration'),
                 ('ACTION.HRIS_INTEGRATION_CONTROL_PLANE', 'ACTION',
                  'HRIS integration control-plane execution')
             ) AS expected(resource_key, resource_type, display_name)
             LEFT JOIN sys_tenant_resource_templates template
               ON template.resource_key = expected.resource_key
              AND template.resource_type = expected.resource_type
              AND template.display_name = expected.display_name
              AND template.required_entitlement = 'core.people'
              AND template.lifecycle_state = 'ACTIVE'
            WHERE template.resource_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: resource templates differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_responsibility_catalog
         WHERE responsibility_code IN (
             'HRIS_DUTY_INTEGRATION_AUTHOR',
             'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')) <> 2
       OR NOT EXISTS (
           SELECT 1 FROM sys_admin_responsibility_catalog
            WHERE responsibility_code = 'HRIS_DUTY_INTEGRATION_AUTHOR'
              AND display_name = 'HRIS integration author'
              AND description = 'Authors and validates immutable connector definitions and mapping versions for independently assigned connector and legal-entity populations.'
              AND resource_type = 'APP'
              AND risk_tier = 'L3'
              AND allowed_principal_types = '["USER","GROUP"]'::jsonb
              AND label_i18n = '{"ko":"HRIS 연계 작성 담당자","en":"HRIS integration author"}'::jsonb
              AND sort_order = 140
              AND lifecycle_state = 'ACTIVE'
       ) OR NOT EXISTS (
           SELECT 1 FROM sys_admin_responsibility_catalog
            WHERE responsibility_code =
                      'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR'
              AND display_name = 'HRIS integration control-plane executor'
              AND description = 'Independently approves, activates, executes, reconciles, retries, cancels, or compensates governed HRIS connector work for assigned connector and legal-entity populations.'
              AND resource_type = 'APP'
              AND risk_tier = 'L3'
              AND allowed_principal_types = '["USER","GROUP"]'::jsonb
              AND label_i18n = '{"ko":"HRIS 연계 통제 실행 담당자","en":"HRIS integration control-plane executor"}'::jsonb
              AND sort_order = 150
              AND lifecycle_state = 'ACTIVE'
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: responsibilities differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_scoped_duty_catalog
         WHERE duty_code IN (
             'HRIS_DUTY_INTEGRATION_AUTHOR',
             'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')) <> 2
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('HRIS_DUTY_INTEGRATION_AUTHOR',
                  'CONFIG.HRIS_INTEGRATION'),
                 ('HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR',
                  'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
             ) AS expected(duty_code, resource_key)
             LEFT JOIN sys_admin_scoped_duty_catalog duty
               ON duty.duty_code = expected.duty_code
              AND duty.product_key = 'hcm'
              AND duty.legacy_role_code = 'HR_ADMIN'
              AND duty.product_resource_key = 'APP.HCM'
              AND duty.resource_key = expected.resource_key
              AND duty.audit_policy_exception = FALSE
              AND duty.risk_tier = 'HIGH'
              AND duty.lifecycle_state = 'ACTIVE'
              AND duty.version = 0
            WHERE duty.duty_code IS NULL
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: scoped duties differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_scoped_duty_capabilities
         WHERE duty_code IN (
             'HRIS_DUTY_INTEGRATION_AUTHOR',
             'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')) <> 2
       OR NOT EXISTS (
           SELECT 1 FROM sys_admin_scoped_duty_capabilities
            WHERE duty_code = 'HRIS_DUTY_INTEGRATION_AUTHOR'
              AND capability_contract_key =
                  'hcm.integration.definition.update'
              AND permission_resource_key = 'CONFIG.HRIS_INTEGRATION'
              AND permission_code = 'UPDATE'
              AND resolved_capability_code =
                  'CONFIG.HRIS_INTEGRATION:UPDATE'
       ) OR NOT EXISTS (
           SELECT 1 FROM sys_admin_scoped_duty_capabilities
            WHERE duty_code =
                      'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR'
              AND capability_contract_key =
                  'hcm.integration.control-plane.execute'
              AND permission_resource_key =
                  'ACTION.HRIS_INTEGRATION_CONTROL_PLANE'
              AND permission_code = 'EXECUTE'
              AND resolved_capability_code =
                  'ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE'
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: duty capabilities differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_app_preset_catalog
         WHERE preset_code IN (
             'HRIS_INTEGRATION_AUTHOR',
             'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR')) <> 2
       OR NOT EXISTS (
           SELECT 1 FROM sys_admin_app_preset_catalog
            WHERE preset_code = 'HRIS_INTEGRATION_AUTHOR'
              AND product_key = 'hcm'
              AND product_resource_key = 'APP.HCM'
              AND display_name = 'HRIS integration author'
              AND description = 'Authors and validates immutable connector definitions and mapping versions for independently assigned connector and legal-entity populations.'
              AND responsibility_code = 'APP_CONFIG_ADMIN'
              AND risk_tier = 'HIGH'
              AND lifecycle_state = 'ACTIVE'
              AND version = 1
       ) OR NOT EXISTS (
           SELECT 1 FROM sys_admin_app_preset_catalog
            WHERE preset_code = 'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR'
              AND product_key = 'hcm'
              AND product_resource_key = 'APP.HCM'
              AND display_name = 'HRIS integration control-plane executor'
              AND description = 'Independently approves, activates, executes, reconciles, retries, cancels, or compensates governed HRIS connector work for assigned connector and legal-entity populations.'
              AND responsibility_code = 'APP_CONFIG_ADMIN'
              AND risk_tier = 'HIGH'
              AND lifecycle_state = 'ACTIVE'
              AND version = 1
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: presets differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_app_preset_duties
         WHERE preset_code IN (
             'HRIS_INTEGRATION_AUTHOR',
             'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR')) <> 2
       OR NOT EXISTS (
           SELECT 1 FROM sys_admin_app_preset_duties
            WHERE preset_code = 'HRIS_INTEGRATION_AUTHOR'
              AND duty_code = 'HRIS_DUTY_INTEGRATION_AUTHOR'
              AND sort_order = 10
       ) OR NOT EXISTS (
           SELECT 1 FROM sys_admin_app_preset_duties
            WHERE preset_code = 'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR'
              AND duty_code =
                  'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR'
              AND sort_order = 10
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: preset duties differ from the sealed tuples';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM com_tenants tenant
         CROSS JOIN (VALUES
             ('CONFIG', 'CONFIG.HRIS_INTEGRATION',
              'HRIS integration configuration'),
             ('ACTION', 'ACTION.HRIS_INTEGRATION_CONTROL_PLANE',
              'HRIS integration control-plane execution')
         ) AS expected(resource_type, resource_key, display_name)
          LEFT JOIN com_resources resource
            ON resource.tenant_id = tenant.tenant_id
           AND resource.type = expected.resource_type
           AND resource.key = expected.resource_key
           AND resource.name = expected.display_name
           AND resource.enabled = TRUE
           AND resource.created_by = 1
           AND resource.updated_by = 1
         WHERE resource.resource_id IS NULL
    ) THEN
        RAISE EXCEPTION 'V269 conflict: tenant resources differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces' AND version = 60) <> 1
       OR NOT EXISTS (
           SELECT 1 FROM auth_product_authorization_seed_release
            WHERE bundle_key = 'product-surfaces'
              AND version = 60
              AND checksum =
                  '405ac7c88d30a5730faa40c730968fffc873bd1a3679e4997532f0ea9ff1c47c'
              AND auth_seed_artifact =
                  'product-surfaces-v1.bundle-v60.generated.json'
              AND intended_bundle_status = 'DRAFT'
              AND automatic_import_enabled = FALSE
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: v60 release differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE') <> 1
       OR NOT EXISTS (
           SELECT 1 FROM sys_product_feature_activation_policies
            WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
              AND policy_version = 1
              AND product_key = 'hcm'
              AND app_resource_key = 'APP.HCM'
              AND minimum_bundle_version = 60
              AND baseline_state = 'OFF'
              AND lifecycle_state = 'PUBLISHED'
              AND owner_key = 'SYS'
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: feature policy differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_owners
         WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE') <> 20
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.configuration.settings-integrations.page'),
                 ('route.hcm.configuration.dwp-integrations.data'),
                 ('route.hcm.configuration.connector-catalog.data'),
                 ('route.hcm.configuration.connector-definition-create.action'),
                 ('route.hcm.configuration.connector-test.action'),
                 ('route.hcm.configuration.connector-mapping-dry-run.action'),
                 ('route.hcm.configuration.connector-execution-create.action'),
                 ('route.hcm.configuration.connector-execution-detail.data'),
                 ('route.hcm.configuration.connector-definition-validate.action'),
                 ('route.hcm.configuration.connector-definition-submit.action'),
                 ('route.hcm.configuration.connector-definition-approve.action'),
                 ('route.hcm.configuration.connector-definition-activate.action'),
                 ('route.hcm.configuration.connector-mapping-version-create.action'),
                 ('route.hcm.configuration.connector-mapping-version-validate.action'),
                 ('route.hcm.configuration.connector-mapping-version-submit.action'),
                 ('route.hcm.configuration.connector-mapping-version-approve.action'),
                 ('route.hcm.configuration.connector-mapping-version-activate.action'),
                 ('route.hcm.configuration.connector-execution-reconcile.action'),
                 ('route.hcm.configuration.connector-execution-retry.action'),
                 ('route.hcm.configuration.connector-execution-cancel.action')
             ) AS expected(route_contract_key)
             LEFT JOIN sys_product_feature_route_owners owner_row
               ON owner_row.route_contract_key = expected.route_contract_key
              AND owner_row.feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
            WHERE owner_row.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: route owners differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_bindings
         WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE') <> 20
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.configuration.settings-integrations.page', 'PAGE'),
                 ('route.hcm.configuration.dwp-integrations.data', 'DATA'),
                 ('route.hcm.configuration.connector-catalog.data', 'DATA'),
                 ('route.hcm.configuration.connector-definition-create.action', 'ACTION'),
                 ('route.hcm.configuration.connector-test.action', 'ACTION'),
                 ('route.hcm.configuration.connector-mapping-dry-run.action', 'ACTION'),
                 ('route.hcm.configuration.connector-execution-create.action', 'ACTION'),
                 ('route.hcm.configuration.connector-execution-detail.data', 'DATA'),
                 ('route.hcm.configuration.connector-definition-validate.action', 'ACTION'),
                 ('route.hcm.configuration.connector-definition-submit.action', 'ACTION'),
                 ('route.hcm.configuration.connector-definition-approve.action', 'ACTION'),
                 ('route.hcm.configuration.connector-definition-activate.action', 'ACTION'),
                 ('route.hcm.configuration.connector-mapping-version-create.action', 'ACTION'),
                 ('route.hcm.configuration.connector-mapping-version-validate.action', 'ACTION'),
                 ('route.hcm.configuration.connector-mapping-version-submit.action', 'ACTION'),
                 ('route.hcm.configuration.connector-mapping-version-approve.action', 'ACTION'),
                 ('route.hcm.configuration.connector-mapping-version-activate.action', 'ACTION'),
                 ('route.hcm.configuration.connector-execution-reconcile.action', 'ACTION'),
                 ('route.hcm.configuration.connector-execution-retry.action', 'ACTION'),
                 ('route.hcm.configuration.connector-execution-cancel.action', 'ACTION')
             ) AS expected(route_contract_key, route_kind)
             LEFT JOIN sys_product_feature_route_bindings binding
               ON binding.route_contract_key = expected.route_contract_key
              AND binding.feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
              AND binding.policy_version = 1
              AND binding.product_key = 'hcm'
              AND binding.app_resource_key = 'APP.HCM'
              AND binding.minimum_bundle_version = 60
              AND binding.route_kind = expected.route_kind
              AND binding.subject_type = 'PRODUCT'
              AND binding.surface_key = 'hcm.management'
              AND binding.lifecycle_state = 'ACTIVE'
            WHERE binding.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V269 conflict: route bindings differ from the sealed tuples';
    END IF;

    IF EXISTS (
        SELECT 1 FROM sys_tenant_role_permission_templates
         WHERE resource_key IN (
             'CONFIG.HRIS_INTEGRATION',
             'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
    ) OR EXISTS (
        SELECT 1
          FROM com_role_permissions role_permission
          JOIN com_resources resource
            ON resource.resource_id = role_permission.resource_id
         WHERE resource.key IN (
             'CONFIG.HRIS_INTEGRATION',
             'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
    ) OR EXISTS (
        SELECT 1 FROM com_admin_scoped_duty_assignments
         WHERE duty_code IN (
             'HRIS_DUTY_INTEGRATION_AUTHOR',
             'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
    ) OR EXISTS (
        SELECT 1 FROM com_admin_resource_set_members
         WHERE resource_key IN (
             'CONFIG.HRIS_INTEGRATION',
             'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
    ) OR EXISTS (
        SELECT 1 FROM com_admin_app_preset_assignments
         WHERE preset_code IN (
             'HRIS_INTEGRATION_AUTHOR',
             'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR')
    ) OR EXISTS (
        SELECT 1
          FROM com_principal_resource_grants grant_record
          JOIN com_resources resource
            ON resource.resource_id = grant_record.resource_id
         WHERE resource.key IN (
             'CONFIG.HRIS_INTEGRATION',
             'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
    ) OR EXISTS (
        SELECT 1 FROM auth_product_feature_activation_changes
         WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
    ) OR EXISTS (
        SELECT 1 FROM com_tenant_feature_enrollment_changes
         WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
    ) THEN
        RAISE EXCEPTION 'V269 conflict: SYS-008 must remain catalog-only with zero derived authority';
    END IF;
END;
$$;
