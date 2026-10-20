-- Catalog-only SYS-006 admission. No activation, tenant enrollment or grant.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces', 56,
    '4c1e82c4ac70f7bbc4aba7a41d77b8fd238da0478501cbdfed97ebd4b5dd0930',
    'product-surfaces-v1.bundle-v56.generated.json', 'DRAFT', FALSE
);

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_AUTOMATION_CONTROL_PLANE', 1, 'hcm', 'APP.HCM', 56, 'OFF', 'SYS');

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.configuration.automation-catalog.data', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-definition-create.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-run-queue.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-run-detail.data', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-run-retry.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-version-create.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-version-validate.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-version-submit.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-version-approve.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-version-activate.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-schedule-create.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-schedule-submit.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-schedule-approve.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-schedule-activate.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-schedule-suspend.action', 'HRIS_AUTOMATION_CONTROL_PLANE'),
    ('route.hcm.configuration.automation-run-cancel.action', 'HRIS_AUTOMATION_CONTROL_PLANE');

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.configuration.automation-catalog.data', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'DATA', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-definition-create.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-run-queue.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-run-detail.data', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'DATA', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-run-retry.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-version-create.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-version-validate.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-version-submit.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-version-approve.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-version-activate.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-schedule-create.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-schedule-submit.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-schedule-approve.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-schedule-activate.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-schedule-suspend.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.automation-run-cancel.action', 'HRIS_AUTOMATION_CONTROL_PLANE', 1,
     'hcm', 'APP.HCM', 56, 'ACTION', 'PRODUCT', 'hcm.management');
