-- NULL tenant IDs are distinct under the original V1 UNIQUE constraint. Normalize the
-- policy tenant scope so a pre-provisioned global policy cannot create an ambiguous runtime tie.
CREATE UNIQUE INDEX uq_ntf_policy_normalized_scope_version
    ON ntf_routing_policies (scope_tenant_id, scope_type, scope_key, version);

SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES
    ('10000000-0000-0000-0000-000000000011', NULL, 'PROVIDER', 'dwp',
     'MEETINGS.INVITATION_CREATED', 'meetings', 'Collaboration Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000012', NULL, 'PROVIDER', 'dwp',
     'MEETINGS.INVITATION_RESCHEDULED', 'meetings', 'Collaboration Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000013', NULL, 'PROVIDER', 'dwp',
     'MEETINGS.INVITATION_CANCELLED', 'meetings', 'Collaboration Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000014', NULL, 'PROVIDER', 'dwp',
     'MEETINGS.PREPARATION_MATERIAL_ADDED', 'meetings', 'Collaboration Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000015', NULL, 'PROVIDER', 'dwp',
     'MEETINGS.PREPARATION_MATERIAL_REMOVED', 'meetings', 'Collaboration Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES
    ('11000000-0000-0000-0000-000000000011', NULL,
     '10000000-0000-0000-0000-000000000011', 1, 'meetings.meeting.scheduled.v1',
     1, 1, 'HIGH', 'ACTIONABLE', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","userConfigurable":true,"previewPolicy":"TITLE_ONLY"}'::jsonb,
     'ACTIVE'),
    ('11000000-0000-0000-0000-000000000012', NULL,
     '10000000-0000-0000-0000-000000000012', 1, 'meetings.meeting.rescheduled.v1',
     1, 1, 'HIGH', 'ACTIONABLE', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","userConfigurable":true,"previewPolicy":"TITLE_ONLY"}'::jsonb,
     'ACTIVE'),
    ('11000000-0000-0000-0000-000000000013', NULL,
     '10000000-0000-0000-0000-000000000013', 1, 'meetings.meeting.cancelled.v1',
     1, 1, 'HIGH', 'INFORMATIONAL', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"ACTIVE","userConfigurable":true,"previewPolicy":"TITLE_ONLY"}'::jsonb,
     'ACTIVE'),
    ('11000000-0000-0000-0000-000000000014', NULL,
     '10000000-0000-0000-0000-000000000014', 1,
     'meetings.meeting.preparation-material-added.v1',
     1, 1, 'NORMAL', 'INFORMATIONAL', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"PASSIVE","userConfigurable":true,"previewPolicy":"TITLE_ONLY"}'::jsonb,
     'ACTIVE'),
    ('11000000-0000-0000-0000-000000000015', NULL,
     '10000000-0000-0000-0000-000000000015', 1,
     'meetings.meeting.preparation-material-removed.v1',
     1, 1, 'NORMAL', 'INFORMATIONAL', 'INTERNAL',
     '{"audienceMode":"DIRECT","interruptionLevel":"PASSIVE","userConfigurable":true,"previewPolicy":"TITLE_ONLY"}'::jsonb,
     'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000015', NULL,
     '11000000-0000-0000-0000-000000000011', 'IN_APP', 'ko-KR', 1,
     '회의 초대가 도착했습니다', '새 회의에 초대되었습니다.',
     '회의 준비 화면에서 일정과 참여 정보를 확인하세요.',
     '{"label":"회의 준비 열기","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-invitation-created-ko-v1'),
    ('12000000-0000-0000-0000-000000000016', NULL,
     '11000000-0000-0000-0000-000000000011', 'IN_APP', 'en-US', 1,
     'Meeting invitation received', 'You have been invited to a meeting.',
     'Open meeting preparation to review the schedule and participation details.',
     '{"label":"Open meeting preparation","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-invitation-created-en-v1'),
    ('12000000-0000-0000-0000-000000000017', NULL,
     '11000000-0000-0000-0000-000000000012', 'IN_APP', 'ko-KR', 1,
     '회의 일정이 변경되었습니다', '초대된 회의의 일정이 변경되었습니다.',
     '회의 준비 화면에서 현재 일정과 참여 정보를 다시 확인하세요.',
     '{"label":"변경된 회의 확인","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-invitation-rescheduled-ko-v1'),
    ('12000000-0000-0000-0000-000000000018', NULL,
     '11000000-0000-0000-0000-000000000012', 'IN_APP', 'en-US', 1,
     'Meeting schedule changed', 'The schedule for an invited meeting has changed.',
     'Open meeting preparation to review the current schedule and participation details.',
     '{"label":"Review changed meeting","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-invitation-rescheduled-en-v1'),
    ('12000000-0000-0000-0000-000000000019', NULL,
     '11000000-0000-0000-0000-000000000013', 'IN_APP', 'ko-KR', 1,
     '회의가 취소되었습니다', '초대된 회의가 취소되었습니다.',
     '회의 준비 화면에서 취소된 일정 정보를 확인하세요.',
     '{"label":"취소된 회의 확인","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-invitation-cancelled-ko-v1'),
    ('12000000-0000-0000-0000-000000000020', NULL,
     '11000000-0000-0000-0000-000000000013', 'IN_APP', 'en-US', 1,
     'Meeting cancelled', 'An invited meeting has been cancelled.',
     'Open meeting preparation to review the cancelled schedule details.',
     '{"label":"Review cancelled meeting","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-invitation-cancelled-en-v1'),
    ('12000000-0000-0000-0000-000000000021', NULL,
     '11000000-0000-0000-0000-000000000014', 'IN_APP', 'ko-KR', 1,
     '회의 자료가 추가되었습니다', '초대된 회의에 준비 자료가 추가되었습니다.',
     '회의 준비 화면에서 현재 공유 자료를 확인하세요.',
     '{"label":"회의 자료 확인","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-material-added-ko-v1'),
    ('12000000-0000-0000-0000-000000000022', NULL,
     '11000000-0000-0000-0000-000000000014', 'IN_APP', 'en-US', 1,
     'Meeting material added', 'Preparation material was added to an invited meeting.',
     'Open meeting preparation to review the currently shared material.',
     '{"label":"Review meeting material","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-material-added-en-v1'),
    ('12000000-0000-0000-0000-000000000023', NULL,
     '11000000-0000-0000-0000-000000000015', 'IN_APP', 'ko-KR', 1,
     '회의 자료가 변경되었습니다', '초대된 회의의 준비 자료가 제거되었습니다.',
     '회의 준비 화면에서 현재 공유 자료를 다시 확인하세요.',
     '{"label":"변경된 자료 확인","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-material-removed-ko-v1'),
    ('12000000-0000-0000-0000-000000000024', NULL,
     '11000000-0000-0000-0000-000000000015', 'IN_APP', 'en-US', 1,
     'Meeting material changed', 'Preparation material was removed from an invited meeting.',
     'Open meeting preparation to review the currently shared material.',
     '{"label":"Review changed material","route":"/meetings/mine?view=preparation&meetingId={{meetingId}}"}'::jsonb,
     'PUBLISHED', 'meetings-material-removed-en-v1')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_routing_policies (
    policy_id, tenant_id, scope_type, scope_key, version, state,
    mandatory, quiet_hours_bypass, digest_mode)
VALUES (
    '13000000-0000-0000-0000-000000000002', NULL, 'APP', 'meetings', 1,
    'PUBLISHED', FALSE, FALSE, 'IMMEDIATE')
ON CONFLICT (scope_tenant_id, scope_type, scope_key, version) DO NOTHING;

INSERT INTO ntf_policy_channel_rules (
    policy_channel_rule_id, tenant_id, policy_id, channel, enabled,
    default_mode, user_overridable, max_per_window, provider_route_key)
SELECT
    '14000000-0000-0000-0000-000000000002', NULL, policy.policy_id, 'IN_APP', TRUE,
    'IMMEDIATE', TRUE, 60, 'in-app-interactive'
  FROM ntf_routing_policies policy
 WHERE policy.scope_tenant_id = 0
   AND policy.scope_type = 'APP'
   AND policy.scope_key = 'meetings'
   AND policy.version = 1
ON CONFLICT (policy_id, channel) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_routing_policies policy
         WHERE policy.scope_tenant_id = 0
           AND policy.scope_type = 'APP'
           AND policy.scope_key = 'meetings'
           AND policy.version = 1
           AND policy.state = 'PUBLISHED'
           AND policy.mandatory = FALSE
           AND policy.quiet_hours_bypass = FALSE
           AND policy.digest_mode = 'IMMEDIATE'
           AND policy.effective_from IS NULL
           AND policy.effective_to IS NULL) THEN
        RAISE EXCEPTION 'Meetings notification policy v1 conflicts with the canonical contract';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_policy_channel_rules channel
          JOIN ntf_routing_policies policy ON policy.policy_id = channel.policy_id
         WHERE policy.scope_tenant_id = 0
           AND policy.scope_type = 'APP'
           AND policy.scope_key = 'meetings'
           AND policy.version = 1
           AND channel.scope_tenant_id = 0
           AND channel.channel = 'IN_APP'
           AND channel.enabled = TRUE
           AND channel.default_mode = 'IMMEDIATE'
           AND channel.user_overridable = TRUE
           AND channel.max_per_window = 60
           AND channel.provider_route_key = 'in-app-interactive') THEN
        RAISE EXCEPTION 'Meetings in-app notification policy v1 conflicts with the canonical contract';
    END IF;
END
$$;
