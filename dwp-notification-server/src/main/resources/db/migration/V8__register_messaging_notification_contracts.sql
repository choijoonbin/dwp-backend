SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES
    ('10000000-0000-0000-0000-000000000007', NULL, 'PROVIDER', 'dwp',
     'MESSAGING.DIRECT_MESSAGE', 'messaging', 'Collaboration Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000008', NULL, 'PROVIDER', 'dwp',
     'MESSAGING.MENTION', 'messaging', 'Collaboration Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000009', NULL, 'PROVIDER', 'dwp',
     'MESSAGING.THREAD_REPLY', 'messaging', 'Collaboration Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES
    ('11000000-0000-0000-0000-000000000007', NULL,
     '10000000-0000-0000-0000-000000000007', 1, 'messaging.message.sent.v1',
     1, 1, 'NORMAL', 'INFORMATIONAL', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","userConfigurable":true,"previewPolicy":"CLASSIFICATION_AWARE"}'::jsonb,
     'ACTIVE'),
    ('11000000-0000-0000-0000-000000000008', NULL,
     '10000000-0000-0000-0000-000000000008', 1, 'messaging.message.sent.v1',
     1, 1, 'HIGH', 'ACTIONABLE', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","userConfigurable":true,"previewPolicy":"CLASSIFICATION_AWARE"}'::jsonb,
     'ACTIVE'),
    ('11000000-0000-0000-0000-000000000009', NULL,
     '10000000-0000-0000-0000-000000000009', 1, 'messaging.message.sent.v1',
     1, 1, 'NORMAL', 'INFORMATIONAL', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","userConfigurable":true,"previewPolicy":"CLASSIFICATION_AWARE"}'::jsonb,
     'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000007', NULL,
     '11000000-0000-0000-0000-000000000007', 'IN_APP', 'ko-KR', 1,
     '{{senderName}}님의 새 메시지', '{{messagePreview}}',
     '{{senderName}}님이 {{conversationName}} 대화에 새 메시지를 보냈습니다.',
     '{"label":"대화 열기","route":"/messages/direct?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-direct-ko-v1'),
    ('12000000-0000-0000-0000-000000000008', NULL,
     '11000000-0000-0000-0000-000000000008', 'IN_APP', 'ko-KR', 1,
     '{{conversationName}}에서 회원님을 언급했습니다', '{{senderName}}: {{messagePreview}}',
     '{{senderName}}님이 {{conversationName}} 대화에서 회원님을 언급했습니다.',
     '{"label":"대화 열기","route":"/messages/inbox?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-mention-ko-v1'),
    ('12000000-0000-0000-0000-000000000009', NULL,
     '11000000-0000-0000-0000-000000000009', 'IN_APP', 'ko-KR', 1,
     '{{senderName}}님이 스레드에 답글을 남겼습니다', '{{messagePreview}}',
     '{{conversationName}} 대화의 스레드에 새 답글이 도착했습니다.',
     '{"label":"답글 열기","route":"/messages/inbox?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-thread-ko-v1'),
    ('12000000-0000-0000-0000-000000000010', NULL,
     '11000000-0000-0000-0000-000000000007', 'IN_APP', 'en-US', 1,
     'New message from {{senderName}}', '{{messagePreview}}',
     '{{senderName}} sent a new message in {{conversationName}}.',
     '{"label":"Open conversation","route":"/messages/direct?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-direct-en-v1'),
    ('12000000-0000-0000-0000-000000000011', NULL,
     '11000000-0000-0000-0000-000000000008', 'IN_APP', 'en-US', 1,
     'You were mentioned in {{conversationName}}', '{{senderName}}: {{messagePreview}}',
     '{{senderName}} mentioned you in {{conversationName}}.',
     '{"label":"Open conversation","route":"/messages/inbox?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-mention-en-v1'),
    ('12000000-0000-0000-0000-000000000012', NULL,
     '11000000-0000-0000-0000-000000000009', 'IN_APP', 'en-US', 1,
     '{{senderName}} replied in a thread', '{{messagePreview}}',
     'A new reply arrived in a {{conversationName}} thread.',
     '{"label":"Open reply","route":"/messages/inbox?conversation={{conversationId}}&message={{messageId}}"}'::jsonb,
     'PUBLISHED', 'messaging-thread-en-v1')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_routing_policies (
    policy_id, tenant_id, scope_type, scope_key, version, state,
    mandatory, quiet_hours_bypass, digest_mode)
VALUES (
    '13000000-0000-0000-0000-000000000001', NULL, 'APP', 'messaging', 1,
    'PUBLISHED', FALSE, FALSE, 'IMMEDIATE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_policy_channel_rules (
    policy_channel_rule_id, tenant_id, policy_id, channel, enabled,
    default_mode, user_overridable, max_per_window, provider_route_key)
VALUES (
    '14000000-0000-0000-0000-000000000001', NULL,
    '13000000-0000-0000-0000-000000000001', 'IN_APP', TRUE,
    'IMMEDIATE', TRUE, 60, 'in-app-interactive')
ON CONFLICT DO NOTHING;
