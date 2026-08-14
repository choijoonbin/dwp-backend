INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES (
    'PLATFORM.HOME_OVERVIEW.SECTION_STATUS',
    'dwp-platform-server',
    'Home overview section status',
    'Truthful availability boundary for each independently composed home section.',
    'SYSTEM',
    'TYPED_CONTRACT',
    'HomeOverviewDtos.SectionStatus',
    'PROTOCOL',
    'RUNTIME')
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
    ('PLATFORM.HOME_OVERVIEW.SECTION_STATUS', 'AVAILABLE', 'Available',
     '{"ko":"사용 가능","en":"Available"}', 10,
     '{"renderData":true,"retryable":false}', 'ACTIVE'),
    ('PLATFORM.HOME_OVERVIEW.SECTION_STATUS', 'FORBIDDEN', 'Forbidden',
     '{"ko":"권한 없음","en":"Forbidden"}', 20,
     '{"renderData":false,"retryable":false}', 'ACTIVE'),
    ('PLATFORM.HOME_OVERVIEW.SECTION_STATUS', 'UNAVAILABLE', 'Unavailable',
     '{"ko":"일시적으로 사용할 수 없음","en":"Unavailable"}', 30,
     '{"renderData":false,"retryable":true}', 'ACTIVE')
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
    ('PLATFORM.HOME_OVERVIEW.SECTION_STATUS', 'dwp-platform-server',
     'API_CONTRACT', 'HomeOverviewDtos.SectionStatus', 'TYPED_CONTRACT', 'ACTIVE'),
    ('PLATFORM.HOME_OVERVIEW.SECTION_STATUS', 'dwp-frontend',
     'BEHAVIOR', 'home-overview-api.HomeSectionStatus', 'TYPED_CONTRACT', 'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
