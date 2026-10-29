SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

-- The Mail proposal owner emits only identifiers and reviewed routing variables.
-- Registering the type here keeps a successfully accepted proposal from becoming
-- an unmaterializable notification event at runtime.
INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES (
    '10000000-0000-0000-0000-000000000303', NULL, 'PROVIDER', 'dwp',
    'MAIL.ACTION_ESCALATED', 'mail', 'Mail Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES (
    '11000000-0000-0000-0000-000000000303', NULL,
    '10000000-0000-0000-0000-000000000303', 1,
    'mail.action.escalated.v1', 1, 1, 'HIGH', 'ACTIONABLE', 'INTERNAL',
    '{
       "audienceMode":"DIRECT",
       "interruptionLevel":"ACTIVE",
       "userConfigurable":true,
       "previewPolicy":"TITLE_ONLY",
       "requiredVariables":["proposalId","threadId","channel","urgency"],
       "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
       "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
       "runbookUrl":"/notifications/admin/operations?typeKey=MAIL.ACTION_ESCALATED"
     }'::jsonb, 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000306', NULL,
     '11000000-0000-0000-0000-000000000303', 'IN_APP', 'ko-KR', 1,
     '메일 후속 조치가 요청되었습니다', '검토한 메일 조치의 상태를 확인해 주세요.',
     '민감한 메일 내용은 알림에 포함되지 않으며 Mail에서 확인할 수 있습니다.',
     '{"label":"조치 확인","route":"/mail/actions?proposalId={{proposalId}}"}'::jsonb,
     'PUBLISHED', 'mail-action-escalated-ko-v1'),
    ('12000000-0000-0000-0000-000000000307', NULL,
     '11000000-0000-0000-0000-000000000303', 'IN_APP', 'en-US', 1,
     'Mail follow-up requested', 'Review the status of the approved mail action.',
     'Sensitive message content is omitted from the notification and remains in Mail.',
     '{"label":"Review action","route":"/mail/actions?proposalId={{proposalId}}"}'::jsonb,
     'PUBLISHED', 'mail-action-escalated-en-v1')
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_notification_types type
          JOIN ntf_notification_type_versions version
            ON version.type_id = type.type_id
         WHERE type.type_key = 'MAIL.ACTION_ESCALATED'
           AND type.owner_app_key = 'mail'
           AND type.lifecycle_state = 'ACTIVE'
           AND version.source_event_type = 'mail.action.escalated.v1'
           AND version.lifecycle_state = 'ACTIVE'
           AND version.min_schema_version = 1
           AND version.max_schema_version = 1
           AND version.contract_payload ->> 'previewPolicy' = 'TITLE_ONLY'
           AND version.contract_payload ->> 'dedupeStrategy' = 'SOURCE_EVENT_RECIPIENT'
           AND version.contract_payload -> 'requiredVariables'
               @> '["proposalId","threadId","channel","urgency"]'::jsonb) THEN
        RAISE EXCEPTION 'Mail action escalation notification contract is incomplete';
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
         WHERE type.type_key = 'MAIL.ACTION_ESCALATED'
         GROUP BY type.type_id
        HAVING COUNT(DISTINCT template.locale) <> 2) THEN
        RAISE EXCEPTION 'Mail action escalation templates are incomplete';
    END IF;
END
$$;
