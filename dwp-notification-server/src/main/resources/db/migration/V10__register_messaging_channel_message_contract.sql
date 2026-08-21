SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES (
    '10000000-0000-0000-0000-000000000010', NULL, 'PROVIDER', 'dwp',
    'MESSAGING.CHANNEL_MESSAGE', 'messaging', 'Collaboration Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES (
    '11000000-0000-0000-0000-000000000010', NULL,
    '10000000-0000-0000-0000-000000000010', 1, 'messaging.message.sent.v1',
    1, 1, 'NORMAL', 'INFORMATIONAL', 'INTERNAL',
    '{"audienceMode":"DIRECT","interruptionLevel":"PASSIVE","userConfigurable":true,"previewPolicy":"CLASSIFICATION_AWARE"}'::jsonb,
    'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000013', NULL,
     '11000000-0000-0000-0000-000000000010', 'IN_APP', 'ko-KR', 1,
     '{{conversationName}}의 새 메시지', '{{senderName}}: {{messagePreview}}',
     '{{senderName}}님이 {{conversationName}} 대화에 새 메시지를 보냈습니다.',
     '{"label":"대화 열기","route":"/messages/inbox?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-channel-ko-v1'),
    ('12000000-0000-0000-0000-000000000014', NULL,
     '11000000-0000-0000-0000-000000000010', 'IN_APP', 'en-US', 1,
     'New message in {{conversationName}}', '{{senderName}}: {{messagePreview}}',
     '{{senderName}} sent a new message in {{conversationName}}.',
     '{"label":"Open conversation","route":"/messages/inbox?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-channel-en-v1')
ON CONFLICT DO NOTHING;
