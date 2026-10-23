-- Declare the immutable PER-008 v59 carrier and catalog-only readiness for
-- team performance and governed feedback. The feature remains baseline OFF.
-- This migration creates no activation, enrollment, role permission, preset,
-- duty assignment, population assignment, legacy-role backfill, or grant.

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('DATA.HRIS_TEAM_WORKBENCH', 'DATA',
     'HRIS team workbench data', 'core.people'),
    ('ACTION.HRIS_PERFORMANCE_FEEDBACK', 'ACTION',
     'HRIS performance feedback actions', 'core.people'),
    ('DATA.HRIS_PERFORMANCE_FEEDBACK', 'DATA',
     'HRIS performance feedback data', 'core.people')
ON CONFLICT (resource_key) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_catalog (
    duty_code, product_key, legacy_role_code, product_resource_key, resource_key,
    audit_policy_exception, risk_tier, lifecycle_state)
VALUES
    ('HRIS_DUTY_TEAM_WORKBENCH_READER', 'hcm', 'TALENT_ADMIN', 'APP.HCM',
     'DATA.HRIS_TEAM_WORKBENCH', FALSE, 'HIGH', 'ACTIVE'),
    ('HRIS_DUTY_SELF_PERFORMANCE_READER', 'hcm', 'WORKSPACE_MEMBER', 'APP.HCM',
     'DATA.HR_TALENT', FALSE, 'MEDIUM', 'ACTIVE'),
    ('HRIS_DUTY_FEEDBACK_REQUESTER', 'hcm', 'WORKSPACE_MEMBER', 'APP.HCM',
     'ACTION.HRIS_PERFORMANCE_FEEDBACK', FALSE, 'MEDIUM', 'ACTIVE'),
    ('HRIS_DUTY_FEEDBACK_PROVIDER', 'hcm', 'WORKSPACE_MEMBER', 'APP.HCM',
     'ACTION.HRIS_PERFORMANCE_FEEDBACK', FALSE, 'HIGH', 'ACTIVE'),
    ('HRIS_DUTY_FEEDBACK_AGGREGATE_READER', 'hcm', 'TALENT_ADMIN', 'APP.HCM',
     'DATA.HRIS_PERFORMANCE_FEEDBACK', FALSE, 'HIGH', 'ACTIVE')
ON CONFLICT (duty_code) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_capabilities (
    duty_code, capability_contract_key, permission_resource_key, permission_code)
VALUES
    ('HRIS_DUTY_TEAM_WORKBENCH_READER', 'hcm.team.workbench.view',
     'DATA.HRIS_TEAM_WORKBENCH', 'VIEW'),
    ('HRIS_DUTY_SELF_PERFORMANCE_READER', 'hcm.personal.performance.view',
     'DATA.HR_TALENT', 'VIEW'),
    ('HRIS_DUTY_FEEDBACK_REQUESTER', 'per.feedback.request.create',
     'ACTION.HRIS_PERFORMANCE_FEEDBACK', 'CREATE'),
    ('HRIS_DUTY_FEEDBACK_PROVIDER', 'per.feedback.assigned.submit',
     'ACTION.HRIS_PERFORMANCE_FEEDBACK', 'UPDATE'),
    ('HRIS_DUTY_FEEDBACK_AGGREGATE_READER', 'per.feedback.aggregate.view',
     'DATA.HRIS_PERFORMANCE_FEEDBACK', 'VIEW')
ON CONFLICT (duty_code, capability_contract_key) DO NOTHING;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key IN (
           'DATA.HRIS_TEAM_WORKBENCH',
           'ACTION.HRIS_PERFORMANCE_FEEDBACK',
           'DATA.HRIS_PERFORMANCE_FEEDBACK')
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO NOTHING;

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces', 59,
    '56582749cebf382c5c2669d83a30fb4c81fcb532db2878b15e6a83ced6898094',
    'product-surfaces-v1.bundle-v59.generated.json', 'DRAFT', FALSE)
ON CONFLICT (bundle_key, version) DO NOTHING;

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1, 'hcm', 'APP.HCM', 59, 'OFF', 'PER')
ON CONFLICT (feature_key, policy_version) DO NOTHING;

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.team.performance.page', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.team.performance.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.personal.performance-feedback.page', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.performance.feedback-request-options.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.performance.feedback-request-create.action', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.personal.feedback-assignments-mine.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.personal.feedback-assignment-detail.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.personal.feedback-response-submit.action', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'),
    ('route.hcm.performance.feedback-release.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK')
ON CONFLICT (route_contract_key) DO NOTHING;

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.team.performance.page', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'PAGE', 'PRODUCT', 'hcm.team'),
    ('route.hcm.team.performance.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'DATA', 'PRODUCT', 'hcm.team'),
    ('route.hcm.personal.performance-feedback.page', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'PAGE', 'PRODUCT', 'hcm.personal'),
    ('route.hcm.performance.feedback-request-options.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.performance.feedback-request-create.action', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'ACTION', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.personal.feedback-assignments-mine.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'DATA', 'PRODUCT', 'hcm.personal'),
    ('route.hcm.personal.feedback-assignment-detail.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'DATA', 'PRODUCT', 'hcm.personal'),
    ('route.hcm.personal.feedback-response-submit.action', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'ACTION', 'PRODUCT', 'hcm.personal'),
    ('route.hcm.performance.feedback-release.data', 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK', 1,
     'hcm', 'APP.HCM', 59, 'DATA', 'PRODUCT', 'hcm.operations')
ON CONFLICT (route_contract_key, feature_key, policy_version) DO NOTHING;

-- Exact reapplication is a no-op. Every reused key with semantic drift aborts
-- the transaction, and catalog-only readiness must remain authority-neutral.
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM sys_tenant_resource_templates
         WHERE resource_key IN (
             'DATA.HRIS_TEAM_WORKBENCH',
             'ACTION.HRIS_PERFORMANCE_FEEDBACK',
             'DATA.HRIS_PERFORMANCE_FEEDBACK')) <> 3
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('DATA.HRIS_TEAM_WORKBENCH', 'DATA',
                  'HRIS team workbench data'),
                 ('ACTION.HRIS_PERFORMANCE_FEEDBACK', 'ACTION',
                  'HRIS performance feedback actions'),
                 ('DATA.HRIS_PERFORMANCE_FEEDBACK', 'DATA',
                  'HRIS performance feedback data')
             ) AS expected(resource_key, resource_type, display_name)
             LEFT JOIN sys_tenant_resource_templates template
               ON template.resource_key = expected.resource_key
              AND template.resource_type = expected.resource_type
              AND template.display_name = expected.display_name
              AND template.required_entitlement = 'core.people'
              AND template.lifecycle_state = 'ACTIVE'
            WHERE template.resource_key IS NULL
       )
       OR NOT EXISTS (
           SELECT 1
             FROM sys_tenant_resource_templates
            WHERE resource_key = 'DATA.HR_TALENT'
              AND resource_type = 'DATA'
              AND display_name = 'Talent, learning, and employee journeys'
              AND required_entitlement = 'core.people'
              AND lifecycle_state = 'ACTIVE'
       )
    THEN
        RAISE EXCEPTION 'V268 conflict: PER-008 resource templates differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_scoped_duty_catalog
         WHERE duty_code IN (
             'HRIS_DUTY_TEAM_WORKBENCH_READER',
             'HRIS_DUTY_SELF_PERFORMANCE_READER',
             'HRIS_DUTY_FEEDBACK_REQUESTER',
             'HRIS_DUTY_FEEDBACK_PROVIDER',
             'HRIS_DUTY_FEEDBACK_AGGREGATE_READER')) <> 5
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('HRIS_DUTY_TEAM_WORKBENCH_READER', 'TALENT_ADMIN',
                  'DATA.HRIS_TEAM_WORKBENCH', 'HIGH'),
                 ('HRIS_DUTY_SELF_PERFORMANCE_READER', 'WORKSPACE_MEMBER',
                  'DATA.HR_TALENT', 'MEDIUM'),
                 ('HRIS_DUTY_FEEDBACK_REQUESTER', 'WORKSPACE_MEMBER',
                  'ACTION.HRIS_PERFORMANCE_FEEDBACK', 'MEDIUM'),
                 ('HRIS_DUTY_FEEDBACK_PROVIDER', 'WORKSPACE_MEMBER',
                  'ACTION.HRIS_PERFORMANCE_FEEDBACK', 'HIGH'),
                 ('HRIS_DUTY_FEEDBACK_AGGREGATE_READER', 'TALENT_ADMIN',
                  'DATA.HRIS_PERFORMANCE_FEEDBACK', 'HIGH')
             ) AS expected(duty_code, legacy_role_code, resource_key, risk_tier)
             LEFT JOIN sys_admin_scoped_duty_catalog duty
               ON duty.duty_code = expected.duty_code
              AND duty.product_key = 'hcm'
              AND duty.legacy_role_code = expected.legacy_role_code
              AND duty.product_resource_key = 'APP.HCM'
              AND duty.resource_key = expected.resource_key
              AND duty.audit_policy_exception = FALSE
              AND duty.risk_tier = expected.risk_tier
              AND duty.lifecycle_state = 'ACTIVE'
              AND duty.version = 0
            WHERE duty.duty_code IS NULL
       )
    THEN
        RAISE EXCEPTION 'V268 conflict: PER-008 scoped duties differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_admin_scoped_duty_capabilities
         WHERE duty_code IN (
             'HRIS_DUTY_TEAM_WORKBENCH_READER',
             'HRIS_DUTY_SELF_PERFORMANCE_READER',
             'HRIS_DUTY_FEEDBACK_REQUESTER',
             'HRIS_DUTY_FEEDBACK_PROVIDER',
             'HRIS_DUTY_FEEDBACK_AGGREGATE_READER')) <> 5
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('HRIS_DUTY_TEAM_WORKBENCH_READER', 'hcm.team.workbench.view',
                  'DATA.HRIS_TEAM_WORKBENCH', 'VIEW'),
                 ('HRIS_DUTY_SELF_PERFORMANCE_READER', 'hcm.personal.performance.view',
                  'DATA.HR_TALENT', 'VIEW'),
                 ('HRIS_DUTY_FEEDBACK_REQUESTER', 'per.feedback.request.create',
                  'ACTION.HRIS_PERFORMANCE_FEEDBACK', 'CREATE'),
                 ('HRIS_DUTY_FEEDBACK_PROVIDER', 'per.feedback.assigned.submit',
                  'ACTION.HRIS_PERFORMANCE_FEEDBACK', 'UPDATE'),
                 ('HRIS_DUTY_FEEDBACK_AGGREGATE_READER', 'per.feedback.aggregate.view',
                  'DATA.HRIS_PERFORMANCE_FEEDBACK', 'VIEW')
             ) AS expected(duty_code, capability_key, resource_key, permission_code)
             LEFT JOIN sys_admin_scoped_duty_capabilities capability
               ON capability.duty_code = expected.duty_code
              AND capability.capability_contract_key = expected.capability_key
              AND capability.permission_resource_key = expected.resource_key
              AND capability.permission_code = expected.permission_code
              AND capability.resolved_capability_code =
                  expected.resource_key || ':' || expected.permission_code
            WHERE capability.duty_code IS NULL
       )
    THEN
        RAISE EXCEPTION 'V268 conflict: PER-008 duty capabilities differ from the sealed tuples';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM com_tenants tenant
         CROSS JOIN (VALUES
             ('DATA', 'DATA.HRIS_TEAM_WORKBENCH', 'HRIS team workbench data'),
             ('ACTION', 'ACTION.HRIS_PERFORMANCE_FEEDBACK',
              'HRIS performance feedback actions'),
             ('DATA', 'DATA.HRIS_PERFORMANCE_FEEDBACK',
              'HRIS performance feedback data')
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
        RAISE EXCEPTION 'V268 conflict: PER-008 tenant resources differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces' AND version = 59) <> 1
       OR NOT EXISTS (
        SELECT 1 FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces'
           AND version = 59
           AND checksum =
               '56582749cebf382c5c2669d83a30fb4c81fcb532db2878b15e6a83ced6898094'
           AND auth_seed_artifact =
               'product-surfaces-v1.bundle-v59.generated.json'
           AND intended_bundle_status = 'DRAFT'
           AND automatic_import_enabled = FALSE
    ) THEN
        RAISE EXCEPTION 'V268 conflict: product-authorization v59 release differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK') <> 1
       OR NOT EXISTS (
        SELECT 1 FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
           AND policy_version = 1
           AND product_key = 'hcm'
           AND app_resource_key = 'APP.HCM'
           AND minimum_bundle_version = 59
           AND baseline_state = 'OFF'
           AND lifecycle_state = 'PUBLISHED'
           AND owner_key = 'PER'
    ) THEN
        RAISE EXCEPTION 'V268 conflict: PER-008 feature policy differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_owners
         WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK') <> 9
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.team.performance.page'),
                 ('route.hcm.team.performance.data'),
                 ('route.hcm.personal.performance-feedback.page'),
                 ('route.hcm.performance.feedback-request-options.data'),
                 ('route.hcm.performance.feedback-request-create.action'),
                 ('route.hcm.personal.feedback-assignments-mine.data'),
                 ('route.hcm.personal.feedback-assignment-detail.data'),
                 ('route.hcm.personal.feedback-response-submit.action'),
                 ('route.hcm.performance.feedback-release.data')
             ) AS expected(route_contract_key)
             LEFT JOIN sys_product_feature_route_owners owner_row
               ON owner_row.route_contract_key = expected.route_contract_key
              AND owner_row.feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
            WHERE owner_row.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V268 conflict: PER-008 route owners differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_bindings
         WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK') <> 9
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.team.performance.page', 'PAGE', 'hcm.team'),
                 ('route.hcm.team.performance.data', 'DATA', 'hcm.team'),
                 ('route.hcm.personal.performance-feedback.page', 'PAGE', 'hcm.personal'),
                 ('route.hcm.performance.feedback-request-options.data', 'DATA', 'hcm.operations'),
                 ('route.hcm.performance.feedback-request-create.action', 'ACTION', 'hcm.operations'),
                 ('route.hcm.personal.feedback-assignments-mine.data', 'DATA', 'hcm.personal'),
                 ('route.hcm.personal.feedback-assignment-detail.data', 'DATA', 'hcm.personal'),
                 ('route.hcm.personal.feedback-response-submit.action', 'ACTION', 'hcm.personal'),
                 ('route.hcm.performance.feedback-release.data', 'DATA', 'hcm.operations')
             ) AS expected(route_contract_key, route_kind, surface_key)
             LEFT JOIN sys_product_feature_route_bindings binding
               ON binding.route_contract_key = expected.route_contract_key
              AND binding.feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
              AND binding.policy_version = 1
              AND binding.product_key = 'hcm'
              AND binding.app_resource_key = 'APP.HCM'
              AND binding.minimum_bundle_version = 59
              AND binding.route_kind = expected.route_kind
              AND binding.subject_type = 'PRODUCT'
              AND binding.surface_key = expected.surface_key
              AND binding.lifecycle_state = 'ACTIVE'
            WHERE binding.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V268 conflict: PER-008 route bindings differ from the sealed tuples';
    END IF;

    IF EXISTS (
        SELECT 1 FROM sys_tenant_role_permission_templates
         WHERE resource_key IN (
             'DATA.HRIS_TEAM_WORKBENCH',
             'ACTION.HRIS_PERFORMANCE_FEEDBACK',
             'DATA.HRIS_PERFORMANCE_FEEDBACK')
    ) OR EXISTS (
        SELECT 1
          FROM com_role_permissions role_permission
          JOIN com_resources resource
            ON resource.resource_id = role_permission.resource_id
         WHERE resource.key IN (
             'DATA.HRIS_TEAM_WORKBENCH',
             'ACTION.HRIS_PERFORMANCE_FEEDBACK',
             'DATA.HRIS_PERFORMANCE_FEEDBACK')
    ) OR EXISTS (
        SELECT 1 FROM com_admin_scoped_duty_assignments
         WHERE duty_code IN (
             'HRIS_DUTY_TEAM_WORKBENCH_READER',
             'HRIS_DUTY_SELF_PERFORMANCE_READER',
             'HRIS_DUTY_FEEDBACK_REQUESTER',
             'HRIS_DUTY_FEEDBACK_PROVIDER',
             'HRIS_DUTY_FEEDBACK_AGGREGATE_READER')
    ) OR EXISTS (
        SELECT 1 FROM sys_admin_app_preset_duties
         WHERE duty_code IN (
             'HRIS_DUTY_TEAM_WORKBENCH_READER',
             'HRIS_DUTY_SELF_PERFORMANCE_READER',
             'HRIS_DUTY_FEEDBACK_REQUESTER',
             'HRIS_DUTY_FEEDBACK_PROVIDER',
             'HRIS_DUTY_FEEDBACK_AGGREGATE_READER')
    ) OR EXISTS (
        SELECT 1 FROM com_admin_resource_set_members
         WHERE resource_key IN (
             'DATA.HRIS_TEAM_WORKBENCH',
             'ACTION.HRIS_PERFORMANCE_FEEDBACK',
             'DATA.HRIS_PERFORMANCE_FEEDBACK')
    ) OR EXISTS (
        SELECT 1
          FROM com_principal_resource_grants grant_record
          JOIN com_resources resource
            ON resource.resource_id = grant_record.resource_id
         WHERE resource.key IN (
             'DATA.HRIS_TEAM_WORKBENCH',
             'ACTION.HRIS_PERFORMANCE_FEEDBACK',
             'DATA.HRIS_PERFORMANCE_FEEDBACK')
    ) OR EXISTS (
        SELECT 1 FROM sys_builtin_role_catalog
         WHERE role_code IN (
             'HRIS_EMPLOYEE',
             'HRIS_LINE_MANAGER',
             'HRIS_PERFORMANCE_OPERATOR',
             'EMPLOYEE',
             'MANAGER')
    ) OR EXISTS (
        SELECT 1 FROM com_roles
         WHERE builtin_role_code IN (
             'HRIS_EMPLOYEE',
             'HRIS_LINE_MANAGER',
             'HRIS_PERFORMANCE_OPERATOR',
             'EMPLOYEE',
             'MANAGER')
    ) OR EXISTS (
        SELECT 1 FROM auth_product_feature_activation_changes
         WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
    ) OR EXISTS (
        SELECT 1 FROM com_tenant_feature_enrollment_changes
         WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
    ) THEN
        RAISE EXCEPTION 'V268 conflict: PER-008 must remain catalog-only with zero derived authority';
    END IF;
END;
$$;
