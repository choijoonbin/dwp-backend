-- Declare the immutable HRM-012 v57 carrier and catalog-only readiness for the
-- workforce data-quality workbench. The feature remains baseline OFF and this
-- migration creates no assignment, enrollment, activation, or permission grant.

INSERT INTO com_permissions (code, name, created_by, updated_by)
VALUES ('EXECUTE', 'Execute', 1, 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('ACTION.HRIS_INTEGRATION_EXECUTE', 'ACTION',
     'HRIS workforce ingestion execution', 'core.people')
ON CONFLICT (resource_key) DO NOTHING;

INSERT INTO sys_admin_responsibility_catalog (
    responsibility_code, display_name, description, resource_type, risk_tier,
    allowed_principal_types, label_i18n, sort_order, lifecycle_state)
VALUES
    ('HRIS_DUTY_INTEGRATION_EXECUTOR', 'HRIS workforce ingestion executor',
     'Executes governed workforce ingestion only for an independently assigned connector and legal-entity population.',
     'APP', 'L3', '["USER","GROUP"]'::jsonb,
     '{"ko":"인사 데이터 연계 실행 담당자","en":"HRIS workforce ingestion executor"}'::jsonb,
     130, 'ACTIVE')
ON CONFLICT (responsibility_code) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_catalog (
    duty_code, product_key, legacy_role_code, product_resource_key, resource_key,
    audit_policy_exception, risk_tier, lifecycle_state)
VALUES
    ('HRIS_DUTY_INTEGRATION_EXECUTOR', 'hcm', 'HR_ADMIN', 'APP.HCM',
     'ACTION.HRIS_INTEGRATION_EXECUTE', FALSE, 'HIGH', 'ACTIVE')
ON CONFLICT (duty_code) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_capabilities (
    duty_code, capability_contract_key, permission_resource_key, permission_code)
VALUES
    ('HRIS_DUTY_INTEGRATION_EXECUTOR',
     'hcm.integration.workforce-ingestion.execute',
     'ACTION.HRIS_INTEGRATION_EXECUTE', 'EXECUTE')
ON CONFLICT (duty_code, capability_contract_key) DO NOTHING;

INSERT INTO sys_admin_app_preset_catalog (
    preset_code, product_key, product_resource_key, display_name, description,
    responsibility_code, risk_tier, lifecycle_state, version)
VALUES
    ('HRIS_INTEGRATION_EXECUTOR', 'hcm', 'APP.HCM',
     'HRIS workforce ingestion executor',
     'Executes governed workforce ingestion for independently assigned connector and legal-entity populations.',
     'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1)
ON CONFLICT (preset_code) DO NOTHING;

INSERT INTO sys_admin_app_preset_duties (preset_code, duty_code, sort_order)
VALUES ('HRIS_INTEGRATION_EXECUTOR', 'HRIS_DUTY_INTEGRATION_EXECUTOR', 10)
ON CONFLICT (preset_code, duty_code) DO NOTHING;

-- Preserve every V263 branch and predicate and append only the exact HRM-012
-- duty/capability/resource tuple. Connector/legal-entity scope remains owner-
-- derived; the catalog row does not itself assign or grant any population.
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
    'Fail-closed user projection of active scoped duties, including exact HRIS contract-issuer, statutory-operator, and workforce-ingestion boundaries whose populations are independently owner-derived.';

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO NOTHING;

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces', 57,
    'f7d6e2ef92f563747ed062e496638ea83498a33414fc12586c794f517e626d6e',
    'product-surfaces-v1.bundle-v57.generated.json', 'DRAFT', FALSE)
ON CONFLICT (bundle_key, version) DO NOTHING;

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_HRM_DATA_QUALITY_WORKBENCH', 1, 'hcm', 'APP.HCM', 57, 'OFF', 'HRM')
ON CONFLICT (feature_key, policy_version) DO NOTHING;

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.operations.people-data-quality.page',
     'HRIS_HRM_DATA_QUALITY_WORKBENCH'),
    ('route.hcm.operations.people-data-quality.data',
     'HRIS_HRM_DATA_QUALITY_WORKBENCH'),
    ('route.hcm.operations.workforce-ingestion.action',
     'HRIS_HRM_DATA_QUALITY_WORKBENCH')
ON CONFLICT (route_contract_key) DO NOTHING;

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.operations.people-data-quality.page',
     'HRIS_HRM_DATA_QUALITY_WORKBENCH', 1,
     'hcm', 'APP.HCM', 57, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.people-data-quality.data',
     'HRIS_HRM_DATA_QUALITY_WORKBENCH', 1,
     'hcm', 'APP.HCM', 57, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.workforce-ingestion.action',
     'HRIS_HRM_DATA_QUALITY_WORKBENCH', 1,
     'hcm', 'APP.HCM', 57, 'ACTION', 'PRODUCT', 'hcm.operations')
ON CONFLICT (route_contract_key, feature_key, policy_version) DO NOTHING;

-- Every conflict above is accepted only when the existing row is the exact
-- immutable V266 tuple. Reapplying this script is therefore a complete no-op;
-- a reused key with any semantic drift aborts the migration fail-closed.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM com_permissions
         WHERE code = 'EXECUTE' AND name = 'Execute'
    ) THEN
        RAISE EXCEPTION 'V266 conflict: EXECUTE permission differs from the sealed tuple';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM sys_tenant_resource_templates
         WHERE resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
           AND resource_type = 'ACTION'
           AND display_name = 'HRIS workforce ingestion execution'
           AND required_entitlement = 'core.people'
           AND lifecycle_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'V266 conflict: workforce-ingestion resource template differs from the sealed tuple';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM sys_admin_responsibility_catalog
         WHERE responsibility_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
           AND display_name = 'HRIS workforce ingestion executor'
           AND description = 'Executes governed workforce ingestion only for an independently assigned connector and legal-entity population.'
           AND resource_type = 'APP'
           AND risk_tier = 'L3'
           AND allowed_principal_types = '["USER","GROUP"]'::jsonb
           AND label_i18n = '{"ko":"인사 데이터 연계 실행 담당자","en":"HRIS workforce ingestion executor"}'::jsonb
           AND sort_order = 130
           AND lifecycle_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'V266 conflict: integration-executor responsibility differs from the sealed tuple';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM sys_admin_scoped_duty_catalog
         WHERE duty_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
           AND product_key = 'hcm'
           AND legacy_role_code = 'HR_ADMIN'
           AND product_resource_key = 'APP.HCM'
           AND resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
           AND audit_policy_exception = FALSE
           AND risk_tier = 'HIGH'
           AND lifecycle_state = 'ACTIVE'
           AND version = 0
    ) THEN
        RAISE EXCEPTION 'V266 conflict: integration-executor duty differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_scoped_duty_capabilities
         WHERE duty_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR') <> 1
       OR NOT EXISTS (
        SELECT 1 FROM sys_admin_scoped_duty_capabilities
         WHERE duty_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
           AND capability_contract_key = 'hcm.integration.workforce-ingestion.execute'
           AND permission_resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
           AND permission_code = 'EXECUTE'
           AND resolved_capability_code = 'ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE'
    ) THEN
        RAISE EXCEPTION 'V266 conflict: integration-executor capability differs from the sealed tuple';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM sys_admin_app_preset_catalog
         WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR'
           AND product_key = 'hcm'
           AND product_resource_key = 'APP.HCM'
           AND display_name = 'HRIS workforce ingestion executor'
           AND description = 'Executes governed workforce ingestion for independently assigned connector and legal-entity populations.'
           AND responsibility_code = 'APP_CONFIG_ADMIN'
           AND risk_tier = 'HIGH'
           AND lifecycle_state = 'ACTIVE'
           AND version = 1
    ) THEN
        RAISE EXCEPTION 'V266 conflict: integration-executor preset differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_app_preset_duties
         WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR') <> 1
       OR NOT EXISTS (
        SELECT 1 FROM sys_admin_app_preset_duties
         WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR'
           AND duty_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
           AND sort_order = 10
    ) THEN
        RAISE EXCEPTION 'V266 conflict: integration-executor preset duty differs from the sealed tuple';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM com_tenants tenant
          LEFT JOIN com_resources resource
            ON resource.tenant_id = tenant.tenant_id
           AND resource.type = 'ACTION'
           AND resource.key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
         WHERE resource.resource_id IS NULL
            OR resource.name <> 'HRIS workforce ingestion execution'
            OR resource.enabled IS DISTINCT FROM TRUE
            OR resource.created_by IS DISTINCT FROM 1
            OR resource.updated_by IS DISTINCT FROM 1
    ) THEN
        RAISE EXCEPTION 'V266 conflict: tenant workforce-ingestion resource differs from the sealed tuple';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces'
           AND version = 57
           AND checksum = 'f7d6e2ef92f563747ed062e496638ea83498a33414fc12586c794f517e626d6e'
           AND auth_seed_artifact = 'product-surfaces-v1.bundle-v57.generated.json'
           AND intended_bundle_status = 'DRAFT'
           AND automatic_import_enabled = FALSE
    ) THEN
        RAISE EXCEPTION 'V266 conflict: product-authorization v57 release differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH') <> 1
       OR NOT EXISTS (
        SELECT 1 FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH'
           AND policy_version = 1
           AND product_key = 'hcm'
           AND app_resource_key = 'APP.HCM'
           AND minimum_bundle_version = 57
           AND baseline_state = 'OFF'
           AND lifecycle_state = 'PUBLISHED'
           AND owner_key = 'HRM'
    ) THEN
        RAISE EXCEPTION 'V266 conflict: HRM data-quality feature policy differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_owners
         WHERE feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH') <> 3
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.operations.people-data-quality.page'),
                 ('route.hcm.operations.people-data-quality.data'),
                 ('route.hcm.operations.workforce-ingestion.action')
             ) AS expected(route_contract_key)
             LEFT JOIN sys_product_feature_route_owners owner_row
               ON owner_row.route_contract_key = expected.route_contract_key
              AND owner_row.feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH'
            WHERE owner_row.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V266 conflict: HRM data-quality route owners differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_bindings
         WHERE feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH') <> 3
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.operations.people-data-quality.page', 'PAGE'),
                 ('route.hcm.operations.people-data-quality.data', 'DATA'),
                 ('route.hcm.operations.workforce-ingestion.action', 'ACTION')
             ) AS expected(route_contract_key, route_kind)
             LEFT JOIN sys_product_feature_route_bindings binding
               ON binding.route_contract_key = expected.route_contract_key
              AND binding.feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH'
              AND binding.policy_version = 1
              AND binding.product_key = 'hcm'
              AND binding.app_resource_key = 'APP.HCM'
              AND binding.minimum_bundle_version = 57
              AND binding.route_kind = expected.route_kind
              AND binding.subject_type = 'PRODUCT'
              AND binding.surface_key = 'hcm.operations'
              AND binding.lifecycle_state = 'ACTIVE'
            WHERE binding.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V266 conflict: HRM data-quality route bindings differ from the sealed tuples';
    END IF;
END;
$$;
