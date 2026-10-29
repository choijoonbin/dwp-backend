SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

-- Calendar emits one exact source event and selects a delivery contract by type key.
-- External email adapters remain a separate runtime capability; the EMAIL route is
-- nevertheless explicit so admission cannot silently fall back to another channel.
INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES
    ('10000000-0000-0000-0000-000000000500', NULL, 'PROVIDER', 'dwp',
     'CALENDAR.EVENT_REMINDER.IN_APP', 'calendar', 'Calendar Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000501', NULL, 'PROVIDER', 'dwp',
     'CALENDAR.EVENT_REMINDER.EMAIL', 'calendar', 'Calendar Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES
    ('11000000-0000-0000-0000-000000000500', NULL,
     '10000000-0000-0000-0000-000000000500', 1,
     'calendar.event-reminder.due.v1', 1, 1,
     'HIGH', 'ACTIONABLE', 'INTERNAL',
     '{
        "audienceMode":"DIRECT",
        "interruptionLevel":"ACTIVE",
        "userConfigurable":true,
        "previewPolicy":"TITLE_ONLY",
        "deliveryChannel":"IN_APP",
        "requiredVariables":["eventId","eventTitle","occurrenceStartsAt","dueAt","offsetMinutes","channel","timeZone"],
        "variableAllowlist":["eventId","eventTitle","occurrenceStartsAt","dueAt","offsetMinutes","channel","timeZone"],
        "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
        "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
        "runbookUrl":"/notifications/admin/operations?typeKey=CALENDAR.EVENT_REMINDER.IN_APP"
      }'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000501', NULL,
     '10000000-0000-0000-0000-000000000501', 1,
     'calendar.event-reminder.due.v1', 1, 1,
     'HIGH', 'ACTIONABLE', 'INTERNAL',
     '{
        "audienceMode":"DIRECT",
        "interruptionLevel":"ACTIVE",
        "userConfigurable":true,
        "previewPolicy":"TITLE_ONLY",
        "deliveryChannel":"EMAIL",
        "externalAdapterRequired":true,
        "requiredVariables":["eventId","eventTitle","occurrenceStartsAt","dueAt","offsetMinutes","channel","timeZone"],
        "variableAllowlist":["eventId","eventTitle","occurrenceStartsAt","dueAt","offsetMinutes","channel","timeZone"],
        "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
        "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
        "runbookUrl":"/notifications/admin/operations?typeKey=CALENDAR.EVENT_REMINDER.EMAIL"
      }'::jsonb, 'ACTIVE')
ON CONFLICT DO NOTHING;

-- EMAIL has no IN_APP fallback: until an external adapter is attested, its disabled
-- channel rule and the Calendar producer activation gate keep it fail closed.
INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000500', NULL,
     '11000000-0000-0000-0000-000000000500', 'IN_APP', 'ko-KR', 1,
     '일정 알림', '{{eventTitle}} 일정이 곧 시작됩니다.',
     '{{occurrenceStartsAt}}에 시작하는 일정을 확인해 주세요.',
     '{"label":"일정 확인","route":"/calendar/schedule?eventId={{eventId}}"}'::jsonb,
     'PUBLISHED', 'calendar-event-reminder-in-app-ko-v1'),
    ('12000000-0000-0000-0000-000000000501', NULL,
     '11000000-0000-0000-0000-000000000500', 'IN_APP', 'en-US', 1,
     'Calendar reminder', '{{eventTitle}} starts soon.',
     'Review the event starting at {{occurrenceStartsAt}}.',
     '{"label":"View event","route":"/calendar/schedule?eventId={{eventId}}"}'::jsonb,
     'PUBLISHED', 'calendar-event-reminder-in-app-en-v1'),
    ('12000000-0000-0000-0000-000000000504', NULL,
     '11000000-0000-0000-0000-000000000501', 'EMAIL', 'ko-KR', 1,
     '일정 알림: {{eventTitle}}', '{{eventTitle}} 일정이 곧 시작됩니다.',
     '{{occurrenceStartsAt}}에 시작하는 일정을 Calendar에서 확인해 주세요.',
     '{"label":"일정 확인","route":"/calendar/schedule?eventId={{eventId}}"}'::jsonb,
     'PUBLISHED', 'calendar-event-reminder-email-ko-v1'),
    ('12000000-0000-0000-0000-000000000505', NULL,
     '11000000-0000-0000-0000-000000000501', 'EMAIL', 'en-US', 1,
     'Calendar reminder: {{eventTitle}}', '{{eventTitle}} starts soon.',
     'Open Calendar to review the event starting at {{occurrenceStartsAt}}.',
     '{"label":"View event","route":"/calendar/schedule?eventId={{eventId}}"}'::jsonb,
     'PUBLISHED', 'calendar-event-reminder-email-en-v1')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_routing_policies (
    policy_id, tenant_id, scope_type, scope_key, version, state,
    mandatory, quiet_hours_bypass, digest_mode)
VALUES (
    '13000000-0000-0000-0000-000000000500', NULL, 'APP', 'calendar', 1,
    'PUBLISHED', FALSE, FALSE, 'IMMEDIATE')
ON CONFLICT (scope_tenant_id, scope_type, scope_key, version) DO NOTHING;

INSERT INTO ntf_policy_channel_rules (
    policy_channel_rule_id, tenant_id, policy_id, channel, enabled,
    default_mode, user_overridable, max_per_window, provider_route_key)
SELECT
    rule.rule_id, NULL, policy.policy_id, rule.channel, rule.enabled,
    rule.default_mode, rule.user_overridable, rule.max_per_window,
    rule.provider_route_key
  FROM ntf_routing_policies policy
 CROSS JOIN (VALUES
    ('14000000-0000-0000-0000-000000000500'::uuid,
     'IN_APP', TRUE, 'IMMEDIATE', TRUE, 120, 'in-app-interactive'),
    ('14000000-0000-0000-0000-000000000501'::uuid,
     'EMAIL', FALSE, 'MUTED', FALSE, NULL, 'email-calendar-reminder')
 ) rule(rule_id, channel, enabled, default_mode, user_overridable,
        max_per_window, provider_route_key)
 WHERE policy.scope_tenant_id = 0
   AND policy.scope_type = 'APP'
   AND policy.scope_key = 'calendar'
   AND policy.version = 1
ON CONFLICT (policy_id, channel) DO NOTHING;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM (VALUES
               ('CALENDAR.EVENT_REMINDER.IN_APP', 'IN_APP'),
               ('CALENDAR.EVENT_REMINDER.EMAIL', 'EMAIL')
          ) expected(type_key, delivery_channel)
         WHERE NOT EXISTS (
             SELECT 1
               FROM ntf_notification_types type
               JOIN ntf_notification_type_versions version
                 ON version.type_id = type.type_id
              WHERE type.type_key = expected.type_key
                AND type.owner_app_key = 'calendar'
                AND type.lifecycle_state = 'ACTIVE'
                AND version.source_event_type = 'calendar.event-reminder.due.v1'
                AND version.min_schema_version = 1
                AND version.max_schema_version = 1
                AND version.lifecycle_state = 'ACTIVE'
                AND version.contract_payload ->> 'deliveryChannel'
                    = expected.delivery_channel
                AND version.contract_payload -> 'requiredVariables'
                    @> '["eventId","eventTitle","occurrenceStartsAt","dueAt","offsetMinutes","channel","timeZone"]'::jsonb
         )) THEN
        RAISE EXCEPTION 'Calendar reminder notification type contracts are incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM (VALUES
               ('CALENDAR.EVENT_REMINDER.IN_APP', 'IN_APP'),
               ('CALENDAR.EVENT_REMINDER.EMAIL', 'EMAIL')
          ) expected(type_key, channel)
         WHERE NOT EXISTS (
             SELECT 1
               FROM ntf_notification_types type
               JOIN ntf_notification_type_versions version
                 ON version.type_id = type.type_id
               JOIN ntf_template_versions template
                 ON template.type_version_id = version.type_version_id
              WHERE type.type_key = expected.type_key
                AND template.channel = expected.channel
                AND template.locale IN ('ko-KR', 'en-US')
                AND template.state = 'PUBLISHED'
              GROUP BY type.type_key, template.channel
             HAVING COUNT(DISTINCT template.locale) = 2
         )) THEN
        RAISE EXCEPTION 'Calendar reminder localized templates are incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM (VALUES
               ('IN_APP', TRUE, 'IMMEDIATE', TRUE, 'in-app-interactive'),
               ('EMAIL', FALSE, 'MUTED', FALSE, 'email-calendar-reminder')
          ) expected(channel, enabled, default_mode, user_overridable,
                     provider_route_key)
         WHERE NOT EXISTS (
             SELECT 1
               FROM ntf_policy_channel_rules channel
               JOIN ntf_routing_policies policy ON policy.policy_id = channel.policy_id
              WHERE policy.scope_tenant_id = 0
                AND policy.scope_type = 'APP'
                AND policy.scope_key = 'calendar'
                AND policy.version = 1
                AND policy.state = 'PUBLISHED'
                AND policy.mandatory = FALSE
                AND policy.quiet_hours_bypass = FALSE
                AND channel.scope_tenant_id = 0
                AND channel.channel = expected.channel
                AND channel.enabled = expected.enabled
                AND channel.default_mode = expected.default_mode
                AND channel.user_overridable = expected.user_overridable
                AND channel.provider_route_key = expected.provider_route_key
         )) THEN
        RAISE EXCEPTION 'Calendar reminder routing policy is incomplete';
    END IF;
END
$$;
