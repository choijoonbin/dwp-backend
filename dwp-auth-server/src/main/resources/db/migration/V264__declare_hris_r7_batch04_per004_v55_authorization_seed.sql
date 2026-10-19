-- Catalog-only PER-004 admission. No activation, tenant enrollment or grant.
-- The six existing shared Platform ACTION owners remain unchanged.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces', 55,
    '7fb2121a1209b87234bcb389a9bd4c4addca3c4e8c8776a9aac70d278c74d4e6',
    'product-surfaces-v1.bundle-v55.generated.json', 'DRAFT', FALSE
);

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_PERFORMANCE_CONFIGURATION', 1, 'hcm', 'APP.HCM', 55, 'OFF', 'PER');

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.operations.performance-forms-scales.page', 'HRIS_PERFORMANCE_CONFIGURATION'),
    ('route.hcm.operations.performance-forms-scales.data', 'HRIS_PERFORMANCE_CONFIGURATION'),
    ('route.hcm.configuration.settings-performance.page', 'HRIS_PERFORMANCE_CONFIGURATION'),
    ('route.hcm.configuration.settings-performance.data', 'HRIS_PERFORMANCE_CONFIGURATION');

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.operations.performance-forms-scales.page', 'HRIS_PERFORMANCE_CONFIGURATION', 1,
     'hcm', 'APP.HCM', 55, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.performance-forms-scales.data', 'HRIS_PERFORMANCE_CONFIGURATION', 1,
     'hcm', 'APP.HCM', 55, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.configuration.settings-performance.page', 'HRIS_PERFORMANCE_CONFIGURATION', 1,
     'hcm', 'APP.HCM', 55, 'PAGE', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.settings-performance.data', 'HRIS_PERFORMANCE_CONFIGURATION', 1,
     'hcm', 'APP.HCM', 55, 'DATA', 'PRODUCT', 'hcm.management');
