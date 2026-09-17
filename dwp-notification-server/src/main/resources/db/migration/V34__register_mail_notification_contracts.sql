SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES
    ('10000000-0000-0000-0000-000000000300', NULL, 'PROVIDER', 'dwp',
     'MAIL.NEW_MESSAGE', 'mail', 'Mail Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000301', NULL, 'PROVIDER', 'dwp',
     'MAIL.SHARED_ASSIGNMENT', 'mail', 'Mail Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000302', NULL, 'PROVIDER', 'dwp',
     'MAIL.FOLLOW_UP_DUE', 'mail', 'Mail Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES
    ('11000000-0000-0000-0000-000000000300', NULL,
     '10000000-0000-0000-0000-000000000300', 1,
     'mail.message.received.v1', 1, 1, 'NORMAL', 'INFORMATIONAL', 'INTERNAL',
     '{
        "audienceMode":"DIRECT",
        "interruptionLevel":"PASSIVE",
        "userConfigurable":true,
        "previewPolicy":"TITLE_ONLY",
        "requiredVariables":["threadId","messageId"],
        "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
        "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
        "runbookUrl":"/notifications/admin/operations?typeKey=MAIL.NEW_MESSAGE"
      }'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000301', NULL,
     '10000000-0000-0000-0000-000000000301', 1,
     'mail.shared-inbox.assigned.v1', 1, 1, 'HIGH', 'ACTIONABLE', 'INTERNAL',
     '{
        "audienceMode":"DIRECT",
        "interruptionLevel":"ACTIVE",
        "userConfigurable":true,
        "previewPolicy":"TITLE_ONLY",
        "requiredVariables":["threadId","sharedInboxId"],
        "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
        "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
        "runbookUrl":"/notifications/admin/operations?typeKey=MAIL.SHARED_ASSIGNMENT"
      }'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000302', NULL,
     '10000000-0000-0000-0000-000000000302', 1,
     'mail.follow-up.due.v1', 1, 1, 'HIGH', 'ACTIONABLE', 'INTERNAL',
     '{
        "audienceMode":"DIRECT",
        "interruptionLevel":"ACTIVE",
        "userConfigurable":true,
        "previewPolicy":"TITLE_ONLY",
        "requiredVariables":["threadId","followUpId","dueAt"],
        "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
        "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
        "runbookUrl":"/notifications/admin/operations?typeKey=MAIL.FOLLOW_UP_DUE"
      }'::jsonb, 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000300', NULL,
     '11000000-0000-0000-0000-000000000300', 'IN_APP', 'ko-KR', 1,
     '새 메일이 도착했습니다', '받은메일에서 새 메시지를 확인해 주세요.',
     '새 메시지의 내용은 Mail에서 확인할 수 있습니다.',
     '{"label":"메일 확인","route":"/mail/inbox?thread={{threadId}}"}'::jsonb,
     'PUBLISHED', 'mail-new-message-ko-v1'),
    ('12000000-0000-0000-0000-000000000301', NULL,
     '11000000-0000-0000-0000-000000000300', 'IN_APP', 'en-US', 1,
     'New mail received', 'Open Mail to review the new message.',
     'The message content is available in Mail.',
     '{"label":"Open mail","route":"/mail/inbox?thread={{threadId}}"}'::jsonb,
     'PUBLISHED', 'mail-new-message-en-v1'),
    ('12000000-0000-0000-0000-000000000302', NULL,
     '11000000-0000-0000-0000-000000000301', 'IN_APP', 'ko-KR', 1,
     '공유함 메일이 배정되었습니다', '배정된 메일을 확인해 주세요.',
     '공유함에서 담당 업무와 다음 조치를 확인할 수 있습니다.',
     '{"label":"배정 메일 확인","route":"/mail/shared?threadId={{threadId}}"}'::jsonb,
     'PUBLISHED', 'mail-shared-assignment-ko-v1'),
    ('12000000-0000-0000-0000-000000000303', NULL,
     '11000000-0000-0000-0000-000000000301', 'IN_APP', 'en-US', 1,
     'Shared mail assigned', 'Review the mail assigned to you.',
     'Open the shared inbox to review the assignment and next action.',
     '{"label":"Review assignment","route":"/mail/shared?threadId={{threadId}}"}'::jsonb,
     'PUBLISHED', 'mail-shared-assignment-en-v1'),
    ('12000000-0000-0000-0000-000000000304', NULL,
     '11000000-0000-0000-0000-000000000302', 'IN_APP', 'ko-KR', 1,
     '후속 확인 기한이 되었습니다', '답변 대기 중인 메일을 확인해 주세요.',
     'Mail의 후속 확인 목록에서 상태와 다음 조치를 확인할 수 있습니다.',
     '{"label":"후속 확인","route":"/mail/follow-up?threadId={{threadId}}"}'::jsonb,
     'PUBLISHED', 'mail-follow-up-due-ko-v1'),
    ('12000000-0000-0000-0000-000000000305', NULL,
     '11000000-0000-0000-0000-000000000302', 'IN_APP', 'en-US', 1,
     'Mail follow-up is due', 'Review the message awaiting a response.',
     'Open the follow-up list in Mail to review its status and next action.',
     '{"label":"Review follow-up","route":"/mail/follow-up?threadId={{threadId}}"}'::jsonb,
     'PUBLISHED', 'mail-follow-up-due-en-v1')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_routing_policies (
    policy_id, tenant_id, scope_type, scope_key, version, state,
    mandatory, quiet_hours_bypass, digest_mode)
VALUES (
    '13000000-0000-0000-0000-000000000300', NULL, 'APP', 'mail', 1,
    'PUBLISHED', FALSE, FALSE, 'IMMEDIATE')
ON CONFLICT (scope_tenant_id, scope_type, scope_key, version) DO NOTHING;

INSERT INTO ntf_policy_channel_rules (
    policy_channel_rule_id, tenant_id, policy_id, channel, enabled,
    default_mode, user_overridable, max_per_window, provider_route_key)
SELECT
    '14000000-0000-0000-0000-000000000300', NULL, policy.policy_id,
    'IN_APP', TRUE, 'IMMEDIATE', TRUE, 120, 'in-app-interactive'
  FROM ntf_routing_policies policy
 WHERE policy.scope_tenant_id = 0
   AND policy.scope_type = 'APP'
   AND policy.scope_key = 'mail'
   AND policy.version = 1
ON CONFLICT (policy_id, channel) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_notification_types type
          JOIN ntf_notification_type_versions version ON version.type_id = type.type_id
         WHERE type.owner_app_key = 'mail'
           AND type.type_key IN (
               'MAIL.NEW_MESSAGE', 'MAIL.SHARED_ASSIGNMENT', 'MAIL.FOLLOW_UP_DUE')
           AND type.lifecycle_state = 'ACTIVE'
           AND version.lifecycle_state = 'ACTIVE'
           AND version.min_schema_version = 1
           AND version.max_schema_version = 1
           AND version.contract_payload ->> 'previewPolicy' = 'TITLE_ONLY'
           AND version.contract_payload ->> 'dedupeStrategy' = 'SOURCE_EVENT_RECIPIENT'
         GROUP BY type.owner_app_key
        HAVING COUNT(DISTINCT type.type_key) = 3) THEN
        RAISE EXCEPTION 'Mail notification type contracts are incomplete';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM ntf_notification_types type
          JOIN ntf_notification_type_versions version ON version.type_id = type.type_id
          LEFT JOIN ntf_template_versions template
            ON template.type_version_id = version.type_version_id
           AND template.channel = 'IN_APP'
           AND template.locale IN ('ko-KR', 'en-US')
           AND template.state = 'PUBLISHED'
         WHERE type.owner_app_key = 'mail'
           AND type.type_key IN (
               'MAIL.NEW_MESSAGE', 'MAIL.SHARED_ASSIGNMENT', 'MAIL.FOLLOW_UP_DUE')
         GROUP BY type.type_id
        HAVING COUNT(DISTINCT template.locale) <> 2) THEN
        RAISE EXCEPTION 'Mail notification templates are incomplete';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_policy_channel_rules channel
          JOIN ntf_routing_policies policy ON policy.policy_id = channel.policy_id
         WHERE policy.scope_tenant_id = 0
           AND policy.scope_type = 'APP'
           AND policy.scope_key = 'mail'
           AND policy.version = 1
           AND policy.state = 'PUBLISHED'
           AND policy.mandatory = FALSE
           AND policy.quiet_hours_bypass = FALSE
           AND channel.scope_tenant_id = 0
           AND channel.channel = 'IN_APP'
           AND channel.enabled = TRUE
           AND channel.default_mode = 'IMMEDIATE'
           AND channel.user_overridable = TRUE
           AND channel.provider_route_key = 'in-app-interactive') THEN
        RAISE EXCEPTION 'Mail in-app notification routing policy is incomplete';
    END IF;
END
$$;
