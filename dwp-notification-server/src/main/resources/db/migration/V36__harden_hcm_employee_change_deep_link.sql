-- Append-only hardening of the V35 HRIS notification contract. Published V35
-- templates remain immutable; version 2 requires the public request identifier
-- used by the exact employee-change-request deep link.
SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    data_classification, contract_payload, lifecycle_state)
VALUES (
    '11000000-0000-0000-0000-000000000401', NULL,
    '10000000-0000-0000-0000-000000000400', 2,
    'EmployeeChangeAppliedNotificationRequested.v1', 1, 1,
    'NORMAL', 'INFORMATIONAL', 'INTERNAL',
    '{
       "audienceMode":"DIRECT",
       "interruptionLevel":"PASSIVE",
       "userConfigurable":true,
       "previewPolicy":"TITLE_ONLY",
       "requiredVariables":["requestPublicId"],
       "variableAllowlist":["effectiveDate","requestPublicId","requestReference","targetType"],
       "dedupeStrategy":"SOURCE_EVENT_RECIPIENT",
       "retentionPolicy":"TENANT_DEFAULT_LEGAL_HOLD_AWARE",
       "templateKey":"hcm.employee-change.applied",
       "templateVersion":2,
       "templateModelDigest":"736bcd27e82c3629388f4c53b29827c5e3a7a4ac820f3244a0c7ec23f155fcfd",
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
    ('12000000-0000-0000-0000-000000000402', NULL,
     '11000000-0000-0000-0000-000000000401', 'IN_APP', 'ko-KR', 2,
     '인사정보 변경이 반영되었습니다', '요청한 인사정보 변경이 반영되었습니다.',
     '직원변경 요청 화면에서 반영 결과를 확인하세요.',
     '{"label":"변경 결과 확인","route":"/hr/operations/people/employee-change-requests?requestPublicId={{requestPublicId}}"}'::jsonb,
     'PUBLISHED', '332b8b3023c8870f948dffe9229389c31da16553ed5ab42a392308679d575e75'),
    ('12000000-0000-0000-0000-000000000403', NULL,
     '11000000-0000-0000-0000-000000000401', 'IN_APP', 'en-US', 2,
     'Employee information updated',
     'The requested employee information change has been applied.',
     'Open employee change requests to review the result.',
     '{"label":"Review change result","route":"/hr/operations/people/employee-change-requests?requestPublicId={{requestPublicId}}"}'::jsonb,
     'PUBLISHED', 'edbcae85558a64e84ec051a07190bc35fd3725b232923c6542a03d054082188d')
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM ntf_notification_type_versions version
         WHERE version.type_version_id = '11000000-0000-0000-0000-000000000401'
           AND version.type_id = '10000000-0000-0000-0000-000000000400'
           AND version.tenant_id IS NULL
           AND version.version = 2
           AND version.source_event_type =
               'EmployeeChangeAppliedNotificationRequested.v1'
           AND version.min_schema_version = 1
           AND version.max_schema_version = 1
           AND version.contract_payload -> 'requiredVariables' =
               '["requestPublicId"]'::jsonb
           AND version.contract_payload -> 'variableAllowlist' =
               '["effectiveDate","requestPublicId","requestReference","targetType"]'::jsonb
           AND version.contract_payload ->> 'templateVersion' = '2'
           AND version.lifecycle_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'HCM employee change v2 contract is incomplete';
    END IF;

    IF (SELECT count(*)
          FROM ntf_template_versions template
         WHERE template.type_version_id =
               '11000000-0000-0000-0000-000000000401'
           AND template.tenant_id IS NULL
           AND template.channel = 'IN_APP'
           AND template.locale IN ('ko-KR', 'en-US')
           AND template.version = 2
           AND template.state = 'PUBLISHED'
           AND template.action_payload ->> 'route' =
               '/hr/operations/people/employee-change-requests?requestPublicId={{requestPublicId}}') <> 2 THEN
        RAISE EXCEPTION 'HCM employee change v2 localized templates are incomplete';
    END IF;
END
$$;
