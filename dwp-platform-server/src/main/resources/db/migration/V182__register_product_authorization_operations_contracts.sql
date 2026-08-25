-- Register the closed Auth database CHECK sets introduced by the independently
-- authenticated product-authorization lifecycle operations interface.

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES
    ('AUTH.PRODUCT_AUTHORIZATION.GOVERNANCE_OPERATION',
     'dwp-auth-server',
     'Product authorization governance operation',
     'Audited approval, activation, and rollback transitions for immutable authorization bundles.',
     'SYSTEM', 'CHECK',
     'auth_product_authorization_governance_event.operation',
     'STATE_MACHINE', 'ADMIN_ONLY'),
    ('AUTH.PRODUCT_AUTHORIZATION.CALLER_SERVICE_IDENTITY',
     'dwp-auth-server',
     'Product authorization caller service identity',
     'Closed provider/platform workloads allowed to assert lifecycle governance evidence.',
     'SYSTEM', 'CHECK',
     'auth_product_authorization_governance_event.caller_service_identity',
     'SECURITY', 'ADMIN_ONLY')
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    configuration_level = EXCLUDED.configuration_level,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = EXCLUDED.runtime_visibility,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
VALUES
    ('AUTH.PRODUCT_AUTHORIZATION.GOVERNANCE_OPERATION', 'APPROVE',
     'Approve', '{"ko":"승인","en":"Approve"}', 10, '{}', 'ACTIVE'),
    ('AUTH.PRODUCT_AUTHORIZATION.GOVERNANCE_OPERATION', 'ACTIVATE',
     'Activate', '{"ko":"활성화","en":"Activate"}', 20, '{}', 'ACTIVE'),
    ('AUTH.PRODUCT_AUTHORIZATION.GOVERNANCE_OPERATION', 'ROLLBACK',
     'Rollback', '{"ko":"롤백","en":"Rollback"}', 30, '{}', 'ACTIVE'),
    ('AUTH.PRODUCT_AUTHORIZATION.CALLER_SERVICE_IDENTITY', 'dwp-provider-server',
     'DWP provider server',
     '{"ko":"DWP 프로바이더 서버","en":"DWP provider server"}',
     10, '{}', 'ACTIVE'),
    ('AUTH.PRODUCT_AUTHORIZATION.CALLER_SERVICE_IDENTITY', 'dwp-platform-server',
     'DWP platform server',
     '{"ko":"DWP 플랫폼 서버","en":"DWP platform server"}',
     20, '{}', 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'AUTH.PRODUCT_AUTHORIZATION.GOVERNANCE_OPERATION'
   AND code NOT IN ('APPROVE', 'ACTIVATE', 'ROLLBACK')
   AND lifecycle_state <> 'RETIRED';

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'AUTH.PRODUCT_AUTHORIZATION.CALLER_SERVICE_IDENTITY'
   AND code NOT IN ('dwp-provider-server', 'dwp-platform-server')
   AND lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
VALUES
    ('AUTH.PRODUCT_AUTHORIZATION.GOVERNANCE_OPERATION',
     'dwp-auth-server', 'DATABASE_COLUMN',
     'auth_product_authorization_governance_event.operation', 'CHECK', 'ACTIVE'),
    ('AUTH.PRODUCT_AUTHORIZATION.CALLER_SERVICE_IDENTITY',
     'dwp-auth-server', 'DATABASE_COLUMN',
     'auth_product_authorization_governance_event.caller_service_identity',
     'CHECK', 'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = 'CHECK',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
