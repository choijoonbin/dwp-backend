INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES (
    'NOTIFICATION.SYS_AUDIT_OUTBOX.STATUS',
    'dwp-notification-server',
    'Notification audit outbox status',
    'Durable delivery lifecycle for notification-domain audit evidence.',
    'SYSTEM',
    'CHECK',
    'sys_audit_outbox.status',
    'STATE_MACHINE',
    'ADMIN_ONLY')
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
    ('NOTIFICATION.SYS_AUDIT_OUTBOX.STATUS', 'PENDING', 'Pending',
     '{"ko":"대기","en":"Pending"}', 10, '{}', 'ACTIVE'),
    ('NOTIFICATION.SYS_AUDIT_OUTBOX.STATUS', 'SENDING', 'Sending',
     '{"ko":"전송 중","en":"Sending"}', 20, '{}', 'ACTIVE'),
    ('NOTIFICATION.SYS_AUDIT_OUTBOX.STATUS', 'FAILED', 'Failed',
     '{"ko":"재시도 대기","en":"Failed"}', 30, '{}', 'ACTIVE'),
    ('NOTIFICATION.SYS_AUDIT_OUTBOX.STATUS', 'PUBLISHED', 'Published',
     '{"ko":"전달 완료","en":"Published"}', 40, '{}', 'ACTIVE'),
    ('NOTIFICATION.SYS_AUDIT_OUTBOX.STATUS', 'DEAD', 'Dead letter',
     '{"ko":"격리","en":"Dead letter"}', 50, '{}', 'ACTIVE')
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
VALUES (
    'NOTIFICATION.SYS_AUDIT_OUTBOX.STATUS',
    'dwp-notification-server',
    'DATABASE_COLUMN',
    'sys_audit_outbox.status',
    'CHECK',
    'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = 'CHECK',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
