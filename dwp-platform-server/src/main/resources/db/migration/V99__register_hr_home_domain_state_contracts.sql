INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES
    ('PEOPLE.HR_HOME.DOMAIN_AVAILABILITY',
     'dwp-people-server',
     'HR home domain availability',
     'Truthful data availability contract for independently loaded HR home domains.',
     'SYSTEM', 'TYPED_CONTRACT', 'HrDtos.HomeAvailability', 'PROTOCOL', 'RUNTIME'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN',
     'dwp-people-server',
     'HR home data origin',
     'Provenance contract attached to every independently loaded HR home domain.',
     'SYSTEM', 'TYPED_CONTRACT', 'HrDtos.HomeDataOrigin', 'PROTOCOL', 'RUNTIME')
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
    ('PEOPLE.HR_HOME.DOMAIN_AVAILABILITY', 'AVAILABLE', 'Available',
     '{"ko":"사용 가능","en":"Available"}', 10,
     '{"renderData":true,"retryable":false}', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DOMAIN_AVAILABILITY', 'UNAVAILABLE', 'Unavailable',
     '{"ko":"일시적으로 사용할 수 없음","en":"Unavailable"}', 20,
     '{"renderData":false,"retryable":true}', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'SOURCE', 'Source system',
     '{"ko":"원천 시스템","en":"Source system"}', 10,
     '{"provenanceKnown":true,"containsReferenceData":false}', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'MANUAL', 'Manually maintained',
     '{"ko":"수기 관리","en":"Manually maintained"}', 20,
     '{"provenanceKnown":true,"containsReferenceData":false}', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'REFERENCE', 'Reference data',
     '{"ko":"참조 데이터","en":"Reference data"}', 30,
     '{"provenanceKnown":true,"containsReferenceData":true}', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'MIXED', 'Mixed sources',
     '{"ko":"혼합 출처","en":"Mixed sources"}', 40,
     '{"provenanceKnown":true,"containsReferenceData":true}', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'NONE', 'No source data',
     '{"ko":"원천 데이터 없음","en":"No source data"}', 50,
     '{"provenanceKnown":true,"containsReferenceData":false}', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'UNKNOWN', 'Unknown source',
     '{"ko":"출처 미확인","en":"Unknown source"}', 60,
     '{"provenanceKnown":false,"containsReferenceData":false}', 'ACTIVE')
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
    ('PEOPLE.HR_HOME.DOMAIN_AVAILABILITY', 'dwp-people-server',
     'API_CONTRACT', 'HrDtos.HomeAvailability', 'TYPED_CONTRACT', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DOMAIN_AVAILABILITY', 'dwp-frontend',
     'BEHAVIOR', 'hr-api.HrHomeDomainState.availability', 'TYPED_CONTRACT', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'dwp-people-server',
     'API_CONTRACT', 'HrDtos.HomeDataOrigin', 'TYPED_CONTRACT', 'ACTIVE'),
    ('PEOPLE.HR_HOME.DATA_ORIGIN', 'dwp-frontend',
     'BEHAVIOR', 'hr-api.HrHomeDomainState.dataOrigin', 'TYPED_CONTRACT', 'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
