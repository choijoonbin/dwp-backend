-- PLAT-004 / HRM-002 catalog-only registration. Domain-event consumption,
-- People experience, and People relay activation remain outside this migration.
SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES (
    '10000000-0000-0000-0000-000000000400', NULL, 'PROVIDER', 'dwp',
    'HCM.EMPLOYEE_CHANGE_APPLIED', 'hcm', 'People Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES (
    '11000000-0000-0000-0000-000000000400', NULL,
    '10000000-0000-0000-0000-000000000400', 1,
    'EmployeeChangeAppliedNotificationRequested.v1', 1, 1,
    'NORMAL', 'INFORMATIONAL', 'INTERNAL',
    '{
       "audienceMode":"DIRECT",
       "interruptionLevel":"PASSIVE",
       "userConfigurable":true,
       "previewPolicy":"TITLE_ONLY",
       "requiredVariables":[],
       "variableAllowlist":["effectiveDate","requestReference","targetType"],
       "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
       "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
       "templateKey":"hcm.employee-change.applied",
       "templateVersion":1,
       "templateModelDigest":"24b7eff4c70309f32c86911c7e52acf05ee0b38d368630272422e963109dd784",
       "purposeCode":"EMPLOYEE_SELF_SERVICE",
       "reasonCode":"EMPLOYEE_CHANGE_APPLIED",
       "requiredEntitlement":"APP.HCM:VIEW",
       "runbookUrl":"/notifications/admin/operations?typeKey=HCM.EMPLOYEE_CHANGE_APPLIED"
     }'::jsonb,
    'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000400', NULL,
     '11000000-0000-0000-0000-000000000400', 'IN_APP', 'ko-KR', 1,
     '인사정보 변경이 반영되었습니다', '요청한 인사정보 변경이 반영되었습니다.',
     '직원변경 요청 화면에서 반영 결과를 확인하세요.',
     '{"label":"변경 결과 확인","route":"/hr/operations/people/employee-change-requests"}'::jsonb,
     'PUBLISHED', '08454d6cb4a2f8806fef3af419b0889f011ed47f74c62f6af34e1908f1be0d71'),
    ('12000000-0000-0000-0000-000000000401', NULL,
     '11000000-0000-0000-0000-000000000400', 'IN_APP', 'en-US', 1,
     'Employee information updated',
     'The requested employee information change has been applied.',
     'Open employee change requests to review the result.',
     '{"label":"Review change result","route":"/hr/operations/people/employee-change-requests"}'::jsonb,
     'PUBLISHED', '1aae47c07c46152908708c68cd8c9f3557465a7d0807919411bcb6a56a67ce88')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_routing_policies (
    policy_id, tenant_id, scope_type, scope_key, version, state,
    mandatory, quiet_hours_bypass, digest_mode)
VALUES (
    '13000000-0000-0000-0000-000000000400', NULL, 'APP', 'hcm', 1,
    'PUBLISHED', FALSE, FALSE, 'IMMEDIATE')
ON CONFLICT (scope_tenant_id, scope_type, scope_key, version) DO NOTHING;

INSERT INTO ntf_policy_channel_rules (
    policy_channel_rule_id, tenant_id, policy_id, channel, enabled,
    default_mode, user_overridable, max_per_window, provider_route_key)
SELECT
    '14000000-0000-0000-0000-000000000400', NULL, policy.policy_id,
    'IN_APP', TRUE, 'IMMEDIATE', TRUE, 30, 'in-app-interactive'
  FROM ntf_routing_policies policy
 WHERE policy.scope_tenant_id = 0
   AND policy.scope_type = 'APP'
   AND policy.scope_key = 'hcm'
   AND policy.version = 1
ON CONFLICT (policy_id, channel) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_notification_types type
          JOIN ntf_notification_type_versions version
            ON version.type_id = type.type_id
         WHERE type.type_id = '10000000-0000-0000-0000-000000000400'
           AND type.tenant_id IS NULL
           AND type.scope_type = 'PROVIDER'
           AND type.scope_id = 'dwp'
           AND type.type_key = 'HCM.EMPLOYEE_CHANGE_APPLIED'
           AND type.owner_app_key = 'hcm'
           AND type.owner_team = 'People Platform'
           AND type.lifecycle_state = 'ACTIVE'
           AND version.type_version_id = '11000000-0000-0000-0000-000000000400'
           AND version.tenant_id IS NULL
           AND version.version = 1
           AND version.source_event_type =
               'EmployeeChangeAppliedNotificationRequested.v1'
           AND version.min_schema_version = 1
           AND version.max_schema_version = 1
           AND version.priority = 'NORMAL'
           AND version.urgency = 'INFORMATIONAL'
           AND version.data_classification = 'INTERNAL'
           AND version.lifecycle_state = 'ACTIVE'
           AND version.contract_payload ->> 'audienceMode' = 'DIRECT'
           AND version.contract_payload ->> 'interruptionLevel' = 'PASSIVE'
           AND version.contract_payload -> 'userConfigurable' = 'true'::jsonb
           AND version.contract_payload ->> 'previewPolicy' = 'TITLE_ONLY'
           AND version.contract_payload -> 'requiredVariables' = '[]'::jsonb
           AND version.contract_payload -> 'variableAllowlist' =
               '["effectiveDate","requestReference","targetType"]'::jsonb
           AND version.contract_payload ->> 'dedupeStrategy' =
               'SOURCE_EVENT_RECIPIENT'
           AND version.contract_payload ->> 'retentionPolicy' =
               'TENANT_DEFAULT_LEGAL_HOLD_AWARE'
           AND version.contract_payload ->> 'templateKey' =
               'hcm.employee-change.applied'
           AND version.contract_payload ->> 'templateVersion' = '1'
           AND version.contract_payload ->> 'templateModelDigest' =
               '24b7eff4c70309f32c86911c7e52acf05ee0b38d368630272422e963109dd784'
           AND version.contract_payload ->> 'purposeCode' =
               'EMPLOYEE_SELF_SERVICE'
           AND version.contract_payload ->> 'reasonCode' =
               'EMPLOYEE_CHANGE_APPLIED'
           AND version.contract_payload ->> 'requiredEntitlement' =
               'APP.HCM:VIEW'
           AND version.contract_payload ->> 'runbookUrl' =
               '/notifications/admin/operations?typeKey=HCM.EMPLOYEE_CHANGE_APPLIED') THEN
        RAISE EXCEPTION 'HCM employee change notification type contract is incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM ntf_template_versions template
         WHERE template.template_version_id =
               '12000000-0000-0000-0000-000000000400'
           AND template.tenant_id IS NULL
           AND template.type_version_id =
               '11000000-0000-0000-0000-000000000400'
           AND template.channel = 'IN_APP'
           AND template.locale = 'ko-KR'
           AND template.version = 1
           AND template.title_template = '인사정보 변경이 반영되었습니다'
           AND template.preview_template =
               '요청한 인사정보 변경이 반영되었습니다.'
           AND template.body_template =
               '직원변경 요청 화면에서 반영 결과를 확인하세요.'
           AND template.action_payload =
               '{"label":"변경 결과 확인","route":"/hr/operations/people/employee-change-requests"}'::jsonb
           AND template.state = 'PUBLISHED'
           AND template.checksum =
               '08454d6cb4a2f8806fef3af419b0889f011ed47f74c62f6af34e1908f1be0d71') THEN
        RAISE EXCEPTION 'HCM employee change ko-KR template is incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM ntf_template_versions template
         WHERE template.template_version_id =
               '12000000-0000-0000-0000-000000000401'
           AND template.tenant_id IS NULL
           AND template.type_version_id =
               '11000000-0000-0000-0000-000000000400'
           AND template.channel = 'IN_APP'
           AND template.locale = 'en-US'
           AND template.version = 1
           AND template.title_template = 'Employee information updated'
           AND template.preview_template =
               'The requested employee information change has been applied.'
           AND template.body_template =
               'Open employee change requests to review the result.'
           AND template.action_payload =
               '{"label":"Review change result","route":"/hr/operations/people/employee-change-requests"}'::jsonb
           AND template.state = 'PUBLISHED'
           AND template.checksum =
               '1aae47c07c46152908708c68cd8c9f3557465a7d0807919411bcb6a56a67ce88') THEN
        RAISE EXCEPTION 'HCM employee change en-US template is incomplete';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM ntf_policy_channel_rules channel
          JOIN ntf_routing_policies policy ON policy.policy_id = channel.policy_id
         WHERE policy.policy_id = '13000000-0000-0000-0000-000000000400'
           AND policy.scope_tenant_id = 0
           AND policy.scope_type = 'APP'
           AND policy.scope_key = 'hcm'
           AND policy.version = 1
           AND policy.state = 'PUBLISHED'
           AND policy.mandatory = FALSE
           AND policy.quiet_hours_bypass = FALSE
           AND policy.digest_mode = 'IMMEDIATE'
           AND channel.policy_channel_rule_id =
               '14000000-0000-0000-0000-000000000400'
           AND channel.scope_tenant_id = 0
           AND channel.channel = 'IN_APP'
           AND channel.enabled = TRUE
           AND channel.default_mode = 'IMMEDIATE'
           AND channel.user_overridable = TRUE
           AND channel.max_per_window = 30
           AND channel.provider_route_key = 'in-app-interactive') THEN
        RAISE EXCEPTION 'HCM employee change in-app routing policy is incomplete';
    END IF;
END
$$;
