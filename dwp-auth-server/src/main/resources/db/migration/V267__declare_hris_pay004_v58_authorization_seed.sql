-- Declare the immutable PAY-004 v58 carrier and catalog-only readiness for the
-- retirement workbench. The feature remains baseline OFF and this migration
-- creates no assignment, enrollment, activation, permission, duty, or grant.

INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces', 58,
    '556f2cc1554f0aaab6f88e50c34d64ca5cdd95af9b2d55072f6e6745f4652cc2',
    'product-surfaces-v1.bundle-v58.generated.json', 'DRAFT', FALSE)
ON CONFLICT (bundle_key, version) DO NOTHING;

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_PAY_RETIREMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 58, 'OFF', 'PAY')
ON CONFLICT (feature_key, policy_version) DO NOTHING;

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.operations.pay-retirement.page',
     'HRIS_PAY_RETIREMENT_WORKBENCH'),
    ('route.hcm.operations.pay-retirement.data',
     'HRIS_PAY_RETIREMENT_WORKBENCH')
ON CONFLICT (route_contract_key) DO NOTHING;

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.operations.pay-retirement.page',
     'HRIS_PAY_RETIREMENT_WORKBENCH', 1,
     'hcm', 'APP.HCM', 58, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.pay-retirement.data',
     'HRIS_PAY_RETIREMENT_WORKBENCH', 1,
     'hcm', 'APP.HCM', 58, 'DATA', 'PRODUCT', 'hcm.operations')
ON CONFLICT (route_contract_key, feature_key, policy_version) DO NOTHING;

-- Idempotence is allowed only for the exact immutable tuples. Any reused key
-- with semantic drift aborts the whole migration, including earlier inserts.
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces' AND version = 58) <> 1
       OR NOT EXISTS (
        SELECT 1 FROM auth_product_authorization_seed_release
         WHERE bundle_key = 'product-surfaces'
           AND version = 58
           AND checksum =
               '556f2cc1554f0aaab6f88e50c34d64ca5cdd95af9b2d55072f6e6745f4652cc2'
           AND auth_seed_artifact =
               'product-surfaces-v1.bundle-v58.generated.json'
           AND intended_bundle_status = 'DRAFT'
           AND automatic_import_enabled = FALSE
    ) THEN
        RAISE EXCEPTION 'V267 conflict: product-authorization v58 release differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH') <> 1
       OR NOT EXISTS (
        SELECT 1 FROM sys_product_feature_activation_policies
         WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
           AND policy_version = 1
           AND product_key = 'hcm'
           AND app_resource_key = 'APP.HCM'
           AND minimum_bundle_version = 58
           AND baseline_state = 'OFF'
           AND lifecycle_state = 'PUBLISHED'
           AND owner_key = 'PAY'
    ) THEN
        RAISE EXCEPTION 'V267 conflict: PAY retirement feature policy differs from the sealed tuple';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_owners
         WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH') <> 2
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.operations.pay-retirement.page'),
                 ('route.hcm.operations.pay-retirement.data')
             ) AS expected(route_contract_key)
             LEFT JOIN sys_product_feature_route_owners owner_row
               ON owner_row.route_contract_key = expected.route_contract_key
              AND owner_row.feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
            WHERE owner_row.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V267 conflict: PAY retirement route owners differ from the sealed tuples';
    END IF;

    IF (SELECT COUNT(*) FROM sys_product_feature_route_bindings
         WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH') <> 2
       OR EXISTS (
           SELECT 1
             FROM (VALUES
                 ('route.hcm.operations.pay-retirement.page', 'PAGE'),
                 ('route.hcm.operations.pay-retirement.data', 'DATA')
             ) AS expected(route_contract_key, route_kind)
             LEFT JOIN sys_product_feature_route_bindings binding
               ON binding.route_contract_key = expected.route_contract_key
              AND binding.feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
              AND binding.policy_version = 1
              AND binding.product_key = 'hcm'
              AND binding.app_resource_key = 'APP.HCM'
              AND binding.minimum_bundle_version = 58
              AND binding.route_kind = expected.route_kind
              AND binding.subject_type = 'PRODUCT'
              AND binding.surface_key = 'hcm.operations'
              AND binding.lifecycle_state = 'ACTIVE'
            WHERE binding.route_contract_key IS NULL
       )
    THEN
        RAISE EXCEPTION 'V267 conflict: PAY retirement route bindings differ from the sealed tuples';
    END IF;

    IF EXISTS (
        SELECT 1 FROM auth_product_feature_activation_changes
         WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
    ) OR EXISTS (
        SELECT 1 FROM com_tenant_feature_enrollment_changes
         WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
    ) THEN
        RAISE EXCEPTION 'V267 conflict: PAY retirement feature must remain unactivated and unenrolled';
    END IF;
END;
$$;
