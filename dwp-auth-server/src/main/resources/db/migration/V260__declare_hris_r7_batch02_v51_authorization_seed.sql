-- Declare the immutable R7 Batch-02 v51 carrier and its tenant-governed HRM-007/SYS-003
-- feature catalog. Everything remains default OFF: this migration creates no global
-- activation change and no tenant enrollment change.
INSERT INTO auth_product_authorization_seed_release (
    bundle_key, version, checksum, auth_seed_artifact,
    intended_bundle_status, automatic_import_enabled)
VALUES (
    'product-surfaces',
    51,
    '3beda5c13a3af0e265ace4533a3afcee3691c2b7486465dcf46156e58ae5ed2c',
    'product-surfaces-v1.bundle-v51.generated.json',
    'DRAFT',
    FALSE
);

ALTER TABLE sys_product_feature_route_bindings
    DROP CONSTRAINT ck_product_feature_route_bundle;
ALTER TABLE sys_product_feature_route_bindings
    ADD CONSTRAINT ck_product_feature_route_bundle
        CHECK (minimum_bundle_version >= 50);
ALTER TABLE sys_product_feature_route_bindings
    DROP CONSTRAINT ck_product_feature_route_surface;
ALTER TABLE sys_product_feature_route_bindings
    ADD CONSTRAINT ck_product_feature_route_surface
        CHECK (surface_key IN ('hcm.team', 'hcm.operations', 'hcm.management'));

INSERT INTO sys_product_feature_activation_policies (
    feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, baseline_state, owner_key)
VALUES
    ('HRIS_HRM_APPOINTMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 51, 'OFF', 'HRM'),
    ('HRIS_APPROVAL_WORKFLOW_AND_DELEGATION_ADAPTERS', 1, 'hcm', 'APP.HCM', 51, 'OFF', 'SYS');

INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)
VALUES
    ('route.hcm.team.people-changes.page', 'HRIS_HRM_APPOINTMENT_WORKBENCH'),
    ('route.hcm.team.people-changes.data', 'HRIS_HRM_APPOINTMENT_WORKBENCH'),
    ('route.hcm.operations.people-appointments.page', 'HRIS_HRM_APPOINTMENT_WORKBENCH'),
    ('route.hcm.operations.people-appointments.data', 'HRIS_HRM_APPOINTMENT_WORKBENCH'),
    ('route.hcm.configuration.people-employment.page', 'HRIS_HRM_APPOINTMENT_WORKBENCH'),
    ('route.hcm.configuration.people-employment.data', 'HRIS_HRM_APPOINTMENT_WORKBENCH'),
    ('route.hcm.configuration.workflow.page', 'HRIS_APPROVAL_WORKFLOW_AND_DELEGATION_ADAPTERS'),
    ('route.hcm.team.delegation.page', 'HRIS_APPROVAL_WORKFLOW_AND_DELEGATION_ADAPTERS');

INSERT INTO sys_product_feature_route_bindings (
    route_contract_key, feature_key, policy_version, product_key, app_resource_key,
    minimum_bundle_version, route_kind, subject_type, surface_key)
VALUES
    ('route.hcm.team.people-changes.page', 'HRIS_HRM_APPOINTMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 51, 'PAGE', 'PRODUCT', 'hcm.team'),
    ('route.hcm.team.people-changes.data', 'HRIS_HRM_APPOINTMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 51, 'DATA', 'PRODUCT', 'hcm.team'),
    ('route.hcm.operations.people-appointments.page', 'HRIS_HRM_APPOINTMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 51, 'PAGE', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.operations.people-appointments.data', 'HRIS_HRM_APPOINTMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 51, 'DATA', 'PRODUCT', 'hcm.operations'),
    ('route.hcm.configuration.people-employment.page', 'HRIS_HRM_APPOINTMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 51, 'PAGE', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.people-employment.data', 'HRIS_HRM_APPOINTMENT_WORKBENCH', 1, 'hcm', 'APP.HCM', 51, 'DATA', 'PRODUCT', 'hcm.management'),
    ('route.hcm.configuration.workflow.page', 'HRIS_APPROVAL_WORKFLOW_AND_DELEGATION_ADAPTERS', 1, 'hcm', 'APP.HCM', 51, 'PAGE', 'PRODUCT', 'hcm.management'),
    ('route.hcm.team.delegation.page', 'HRIS_APPROVAL_WORKFLOW_AND_DELEGATION_ADAPTERS', 1, 'hcm', 'APP.HCM', 51, 'PAGE', 'PRODUCT', 'hcm.team');
