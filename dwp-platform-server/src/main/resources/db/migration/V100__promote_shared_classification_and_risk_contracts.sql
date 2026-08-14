INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES
    ('PLATFORM.DATA_CLASSIFICATION',
     'dwp-platform-contracts',
     'Data classification',
     'Shared information classification used by audit envelopes and connector boundaries.',
     'SYSTEM', 'TYPED_CONTRACT', 'DataClassification', 'SECURITY', 'RUNTIME'),
    ('PLATFORM.EXECUTION_RISK_TIER',
     'dwp-platform-contracts',
     'Execution risk tier',
     'Shared execution risk contract used by agent plans and governed administrative actions.',
     'SYSTEM', 'TYPED_CONTRACT', 'RiskTier', 'SECURITY', 'RUNTIME')
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
    ('PLATFORM.DATA_CLASSIFICATION', 'PUBLIC', 'Public',
     '{"ko":"공개","en":"Public"}', 10,
     '{"rank":0,"restrictedProcessing":false}', 'ACTIVE'),
    ('PLATFORM.DATA_CLASSIFICATION', 'INTERNAL', 'Internal',
     '{"ko":"내부","en":"Internal"}', 20,
     '{"rank":1,"restrictedProcessing":false}', 'ACTIVE'),
    ('PLATFORM.DATA_CLASSIFICATION', 'CONFIDENTIAL', 'Confidential',
     '{"ko":"기밀","en":"Confidential"}', 30,
     '{"rank":2,"restrictedProcessing":true}', 'ACTIVE'),
    ('PLATFORM.DATA_CLASSIFICATION', 'RESTRICTED', 'Restricted',
     '{"ko":"제한","en":"Restricted"}', 40,
     '{"rank":3,"restrictedProcessing":true}', 'ACTIVE'),
    ('PLATFORM.EXECUTION_RISK_TIER', 'L0', 'L0',
     '{"ko":"L0 - 조회","en":"L0 - Read only"}', 10,
     '{"approvalRequired":false,"executionAllowed":true}', 'ACTIVE'),
    ('PLATFORM.EXECUTION_RISK_TIER', 'L1', 'L1',
     '{"ko":"L1 - 낮은 위험","en":"L1 - Low risk"}', 20,
     '{"approvalRequired":false,"executionAllowed":true}', 'ACTIVE'),
    ('PLATFORM.EXECUTION_RISK_TIER', 'L2', 'L2',
     '{"ko":"L2 - 승인 필요","en":"L2 - Approval required"}', 30,
     '{"approvalRequired":true,"executionAllowed":true}', 'ACTIVE'),
    ('PLATFORM.EXECUTION_RISK_TIER', 'L3', 'L3',
     '{"ko":"L3 - 실행 차단","en":"L3 - Execution blocked"}', 40,
     '{"approvalRequired":true,"executionAllowed":false}', 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
VALUES
    ('PLATFORM.DATA_CLASSIFICATION', 'dwp-platform-contracts',
     'API_CONTRACT', 'DataClassification', 'TYPED_CONTRACT', 'ACTIVE'),
    ('PLATFORM.EXECUTION_RISK_TIER', 'dwp-platform-contracts',
     'API_CONTRACT', 'RiskTier', 'TYPED_CONTRACT', 'ACTIVE'),
    ('PLATFORM.EXECUTION_RISK_TIER', 'dwp-agent-runtime',
     'API_CONTRACT', 'agent-plan/riskTier', 'TYPED_CONTRACT', 'ACTIVE'),
    ('PLATFORM.EXECUTION_RISK_TIER', 'dwp-frontend',
     'BEHAVIOR', 'agent-plan-api/AgentRiskTier', 'TYPED_CONTRACT', 'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
