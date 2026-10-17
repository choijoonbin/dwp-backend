-- Declare the immutable R7 Batch-03 HRM-009 v52 carrier and its tenant-governed
-- certificate workbench. The feature remains default OFF: this migration creates
-- no global activation and no tenant enrollment.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    52,
    'b33fbf968e71f97b09fa497fdfad7d2f6854199170b79fd1602a0ea604977346',
    'product-surfaces-v1.bundle-v52.generated.json',
    'DRAFT',
    FALSE
);

ALTER TABLE sys_product_feature_route_bindings
    DROP CONSTRAINT ck_product_feature_route_surface;
ALTER TABLE sys_product_feature_route_bindings
    ADD CONSTRAINT ck_product_feature_route_surface
        CHECK (surface_key IN (
            'hcm.personal', 'hcm.team', 'hcm.operations', 'hcm.management'));

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_HRM_CERTIFICATE_WORKBENCH', 1, 'hcm', 'APP.HCM', 52, 'OFF', 'HRM');

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.personal.requests.page', 'HRIS_HRM_CERTIFICATE_WORKBENCH'),
    ('route.hcm.personal.requests.data', 'HRIS_HRM_CERTIFICATE_WORKBENCH'),
    ('route.hcm.operations.certificates-documents.page', 'HRIS_HRM_CERTIFICATE_WORKBENCH'),
    ('route.hcm.operations.certificates-documents.data', 'HRIS_HRM_CERTIFICATE_WORKBENCH'),
    ('route.hcm.operations.certificate-issue.action', 'HRIS_HRM_CERTIFICATE_WORKBENCH');

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.personal.requests.page', 'HRIS_HRM_CERTIFICATE_WORKBENCH', 1,
     'hcm', 'APP.HCM', 52, 'PAGE', 'PRODUCT', 'hcm.personal'),
    ('route.hcm.personal.requests.data', 'HRIS_HRM_CERTIFICATE_WORKBENCH', 1,
     'hcm', 'APP.HCM', 52, 'DATA', 'PRODUCT', 'hcm.personal'),
    ('route.hcm.operations.certificates-documents.page', 'HRIS_HRM_CERTIFICATE_WORKBENCH', 1,
     'hcm', 'APP.HCM', 52, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.certificates-documents.data', 'HRIS_HRM_CERTIFICATE_WORKBENCH', 1,
     'hcm', 'APP.HCM', 52, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.certificate-issue.action', 'HRIS_HRM_CERTIFICATE_WORKBENCH', 1,
     'hcm', 'APP.HCM', 52, 'ACTION', 'PRODUCT', 'hcm.operations');
