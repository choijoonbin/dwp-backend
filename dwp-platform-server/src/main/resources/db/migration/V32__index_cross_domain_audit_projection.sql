CREATE INDEX idx_sys_audit_event_tenant_source_time
    ON sys_audit_events (tenant_id, source_service, occurred_at DESC);

COMMENT ON INDEX idx_sys_audit_event_tenant_source_time IS
    'Supports cross-domain event-envelope and correlation exploration by source service.';

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'dwp-platform-server',
     'Event envelope domain', 'Canonical product domains used for cross-service correlation.',
     'SYSTEM', 'TYPED_CONTRACT', 'EventEnvelopeDtos.Envelope.domain', 'PROTOCOL'),
    ('PLATFORM.EVENT_ENVELOPE.CLASSIFICATION', 'dwp-platform-server',
     'Event data classification', 'Handling classification derived for governed audit evidence.',
     'SYSTEM', 'TYPED_CONTRACT', 'EventEnvelopeDtos.Envelope.classification', 'SECURITY');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'IDENTITY_ACCESS', 'Identity and access',
     '{"ko":"신원 및 접근","en":"Identity & access"}', 10, '{}'),
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'PEOPLE_WORKFORCE', 'People and workforce',
     '{"ko":"인사 및 조직","en":"People & workforce"}', 20, '{}'),
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'PLATFORM_WORKSPACE', 'Platform and workspace',
     '{"ko":"플랫폼 및 워크스페이스","en":"Platform & workspace"}', 30, '{}'),
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'PROVIDER_OPERATIONS', 'Provider operations',
     '{"ko":"프로바이더 운영","en":"Provider operations"}', 40, '{}'),
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'AI_AUTOMATION', 'AI and automation',
     '{"ko":"AI 및 자동화","en":"AI & automation"}', 50, '{}'),
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'DATA_GOVERNANCE', 'Data governance',
     '{"ko":"데이터 거버넌스","en":"Data governance"}', 60, '{}'),
    ('PLATFORM.EVENT_ENVELOPE.CLASSIFICATION', 'INTERNAL', 'Internal',
     '{"ko":"내부","en":"Internal"}', 10, '{"tone":"neutral"}'),
    ('PLATFORM.EVENT_ENVELOPE.CLASSIFICATION', 'CONFIDENTIAL', 'Confidential',
     '{"ko":"기밀","en":"Confidential"}', 20, '{"tone":"warning"}'),
    ('PLATFORM.EVENT_ENVELOPE.CLASSIFICATION', 'RESTRICTED', 'Restricted',
     '{"ko":"제한","en":"Restricted"}', 30, '{"tone":"critical"}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'dwp-platform-server', 'API_CONTRACT',
     'EventEnvelopeDtos.Envelope.domain', 'TYPED_CONTRACT'),
    ('PLATFORM.EVENT_ENVELOPE.DOMAIN', 'dwp-frontend', 'UI_SELECTION',
     'audit.eventCorrelation.domain', 'TYPED_CONTRACT'),
    ('PLATFORM.EVENT_ENVELOPE.CLASSIFICATION', 'dwp-platform-server', 'API_CONTRACT',
     'EventEnvelopeDtos.Envelope.classification', 'TYPED_CONTRACT'),
    ('PLATFORM.EVENT_ENVELOPE.CLASSIFICATION', 'dwp-frontend', 'UI_SELECTION',
     'audit.eventCorrelation.classification', 'TYPED_CONTRACT');
