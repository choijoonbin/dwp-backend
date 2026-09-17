SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES (
    '10000000-0000-0000-0000-000000000200', NULL, 'PROVIDER', 'dwp',
    'WORKPLACE.FACILITY_CLOSURE_IMPACT', 'workplace', 'Workplace Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES (
    '11000000-0000-0000-0000-000000000200', NULL,
    '10000000-0000-0000-0000-000000000200', 1,
    'workplace.facility-closure.executed.v1', 1, 1, 'HIGH', 'ACTIONABLE',
    'INTERNAL',
    '{
       "audienceMode":"DIRECT",
       "interruptionLevel":"ACTIVE",
       "userConfigurable":true,
       "previewPolicy":"TITLE_ONLY",
       "requiredVariables":["resourceName","bookingId","impactAction","startsAt","endsAt","commandId","closureId"],
       "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
       "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
       "runbookUrl":"/notifications/admin/operations?typeKey=WORKPLACE.FACILITY_CLOSURE_IMPACT"
     }'::jsonb,
    'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000200', NULL,
     '11000000-0000-0000-0000-000000000200', 'IN_APP', 'ko-KR', 1,
     '공간 예약 변경 안내', '{{resourceName}} 예약이 {{impactAction}} 처리되었습니다.',
     '{{startsAt}}부터 {{endsAt}}까지의 예약 처리 결과를 확인해 주세요.',
     '{"label":"예약 결과 확인","route":"/workplace/reservations?booking={{bookingId}}"}'::jsonb,
     'PUBLISHED', 'workplace-facility-closure-impact-ko-v1'),
    ('12000000-0000-0000-0000-000000000201', NULL,
     '11000000-0000-0000-0000-000000000200', 'IN_APP', 'en-US', 1,
     'Workspace reservation update', 'Your {{resourceName}} reservation was {{impactAction}}.',
     'Review the reservation result for {{startsAt}} through {{endsAt}}.',
     '{"label":"Review reservation","route":"/workplace/reservations?booking={{bookingId}}"}'::jsonb,
     'PUBLISHED', 'workplace-facility-closure-impact-en-v1')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_routing_policies (
    policy_id, tenant_id, scope_type, scope_key, version, state,
    mandatory, quiet_hours_bypass, digest_mode)
VALUES (
    '13000000-0000-0000-0000-000000000200', NULL, 'APP', 'workplace', 1,
    'PUBLISHED', FALSE, FALSE, 'IMMEDIATE')
ON CONFLICT (scope_tenant_id, scope_type, scope_key, version) DO NOTHING;

INSERT INTO ntf_policy_channel_rules (
    policy_channel_rule_id, tenant_id, policy_id, channel, enabled,
    default_mode, user_overridable, max_per_window, provider_route_key)
SELECT
    '14000000-0000-0000-0000-000000000200', NULL, policy.policy_id,
    'IN_APP', TRUE, 'IMMEDIATE', TRUE, 60, 'in-app-interactive'
  FROM ntf_routing_policies policy
 WHERE policy.scope_tenant_id = 0
   AND policy.scope_type = 'APP'
   AND policy.scope_key = 'workplace'
   AND policy.version = 1
ON CONFLICT (policy_id, channel) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_notification_types type
          JOIN ntf_notification_type_versions version ON version.type_id = type.type_id
          JOIN ntf_template_versions template ON template.type_version_id = version.type_version_id
         WHERE type.type_key = 'WORKPLACE.FACILITY_CLOSURE_IMPACT'
           AND type.owner_app_key = 'workplace'
           AND type.lifecycle_state = 'ACTIVE'
           AND version.source_event_type = 'workplace.facility-closure.executed.v1'
           AND version.min_schema_version = 1
           AND version.max_schema_version = 1
           AND version.lifecycle_state = 'ACTIVE'
           AND version.contract_payload -> 'requiredVariables'
               @> '["resourceName","bookingId","impactAction","startsAt","endsAt","commandId","closureId"]'::jsonb
           AND template.channel = 'IN_APP'
           AND template.locale IN ('ko-KR', 'en-US')
           AND template.state = 'PUBLISHED'
         GROUP BY type.type_id, version.type_version_id
        HAVING COUNT(DISTINCT template.locale) = 2) THEN
        RAISE EXCEPTION 'Workplace facility closure notification contract is incomplete';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_policy_channel_rules channel
          JOIN ntf_routing_policies policy ON policy.policy_id = channel.policy_id
         WHERE policy.scope_tenant_id = 0
           AND policy.scope_type = 'APP'
           AND policy.scope_key = 'workplace'
           AND policy.version = 1
           AND policy.state = 'PUBLISHED'
           AND channel.scope_tenant_id = 0
           AND channel.channel = 'IN_APP'
           AND channel.enabled = TRUE
           AND channel.default_mode = 'IMMEDIATE'
           AND channel.user_overridable = TRUE
           AND channel.provider_route_key = 'in-app-interactive') THEN
        RAISE EXCEPTION 'Workplace in-app notification routing policy is incomplete';
    END IF;
END
$$;
