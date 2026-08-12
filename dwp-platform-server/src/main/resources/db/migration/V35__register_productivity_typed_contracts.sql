INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.PRODUCTIVITY_CONNECTOR.PROVIDER_TYPE', 'dwp-platform-server',
     'Productivity provider type', 'Supported upstream productivity provider protocols.',
     'SYSTEM', 'TYPED_CONTRACT', 'ProductivityTypes.ProviderType', 'PROTOCOL'),
    ('PLATFORM.PRODUCTIVITY_CONNECTOR.AUTH_MODE', 'dwp-platform-server',
     'Productivity authentication mode', 'Supported authorization modes for productivity providers.',
     'SYSTEM', 'TYPED_CONTRACT', 'ProductivityTypes.AuthMode', 'SECURITY');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.PRODUCTIVITY_CONNECTOR.PROVIDER_TYPE', 'MICROSOFT_GRAPH', 'Microsoft Graph',
     '{"ko":"Microsoft Graph","en":"Microsoft Graph"}', 10,
     '{"provider":"MICROSOFT_365"}'),
    ('PLATFORM.PRODUCTIVITY_CONNECTOR.AUTH_MODE', 'DELEGATED', 'Delegated authorization',
     '{"ko":"사용자 위임 인증","en":"Delegated authorization"}', 10,
     '{"userConsent":true}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.PRODUCTIVITY_CONNECTOR.PROVIDER_TYPE', 'dwp-platform-server', 'API_CONTRACT',
     'ProductivityTypes.ProviderType', 'TYPED_CONTRACT'),
    ('PLATFORM.PRODUCTIVITY_CONNECTOR.AUTH_MODE', 'dwp-platform-server', 'API_CONTRACT',
     'ProductivityTypes.AuthMode', 'TYPED_CONTRACT');
