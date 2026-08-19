SET LOCAL ROLE dwp_notification_worker;
SELECT set_config('dwp.tenant_id', '1', TRUE);
SELECT set_config('dwp.user_id', '0', TRUE);
SELECT set_config('dwp.notification_scope', 'WORKER', TRUE);

INSERT INTO ntf_notification_types (
    type_id, tenant_id, scope_type, scope_id, type_key,
    owner_app_key, owner_team, lifecycle_state)
VALUES
    ('10000000-0000-0000-0000-000000000001', NULL, 'PROVIDER', 'dwp',
     'APPROVAL.ACTION_REQUIRED', 'approvals', 'Workflow Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000002', NULL, 'PROVIDER', 'dwp',
     'HCM.LEAVE_APPROVED', 'hcm', 'People Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000003', NULL, 'PROVIDER', 'dwp',
     'SPACE.MENTION', 'space', 'Collaboration Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000004', NULL, 'PROVIDER', 'dwp',
     'APPROVAL.REQUEST_SUBMITTED', 'approvals', 'Workflow Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000005', NULL, 'PROVIDER', 'dwp',
     'APPROVAL.REQUEST_APPROVED', 'approvals', 'Workflow Platform', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000006', NULL, 'PROVIDER', 'dwp',
     'APPROVAL.REQUEST_REJECTED', 'approvals', 'Workflow Platform', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notification_type_versions (
    type_version_id, tenant_id, type_id, version, source_event_type,
    min_schema_version, max_schema_version, priority, urgency,
    contract_payload, lifecycle_state)
VALUES
    ('11000000-0000-0000-0000-000000000001', NULL,
     '10000000-0000-0000-0000-000000000001', 1, 'dwp.approval.task.assigned',
     1, 1, 'HIGH', 'ACTIONABLE', '{"audienceMode":"DIRECT"}'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000002', NULL,
     '10000000-0000-0000-0000-000000000002', 1, 'dwp.hcm.leave.approved',
     1, 1, 'NORMAL', 'INFORMATIONAL', '{"audienceMode":"DIRECT"}'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000003', NULL,
     '10000000-0000-0000-0000-000000000003', 1, 'dwp.space.mentioned',
     1, 1, 'NORMAL', 'ACTIONABLE', '{"audienceMode":"DIRECT"}'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000004', NULL,
     '10000000-0000-0000-0000-000000000004', 1, 'approval.request.submitted',
     1, 1, 'NORMAL', 'INFORMATIONAL', '{"audienceMode":"DIRECT"}'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000005', NULL,
     '10000000-0000-0000-0000-000000000005', 1, 'approval.request.approved',
     1, 1, 'NORMAL', 'INFORMATIONAL', '{"audienceMode":"DIRECT"}'::jsonb, 'ACTIVE'),
    ('11000000-0000-0000-0000-000000000006', NULL,
     '10000000-0000-0000-0000-000000000006', 1, 'approval.request.rejected',
     1, 1, 'HIGH', 'INFORMATIONAL', '{"audienceMode":"DIRECT"}'::jsonb, 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_template_versions (
    template_version_id, tenant_id, type_version_id, channel, locale, version,
    title_template, preview_template, body_template, action_payload, state, checksum)
VALUES
    ('12000000-0000-0000-0000-000000000001', NULL,
     '11000000-0000-0000-0000-000000000001', 'IN_APP', 'ko-KR', 1,
     '{{requestTitle}} 승인이 필요합니다', '{{requesterName}}님이 검토를 요청했습니다.',
     '{{requestTitle}} 요청의 승인 단계가 도착했습니다.',
     '{"label":"검토하기","route":"/approvals/tasks/{{taskId}}"}'::jsonb,
     'PUBLISHED', 'seed-approval-v1'),
    ('12000000-0000-0000-0000-000000000002', NULL,
     '11000000-0000-0000-0000-000000000002', 'IN_APP', 'ko-KR', 1,
     '휴가 신청이 승인되었습니다', '{{leavePeriod}} 휴가 일정이 확정되었습니다.',
     '승인된 휴가 일정과 잔여 휴가를 확인해 주세요.',
     '{"label":"휴가 보기","route":"/hr/time-off"}'::jsonb,
     'PUBLISHED', 'seed-hcm-v1'),
    ('12000000-0000-0000-0000-000000000003', NULL,
     '11000000-0000-0000-0000-000000000003', 'IN_APP', 'ko-KR', 1,
     '{{spaceName}}에서 회원님을 언급했습니다', '{{senderName}}: {{messagePreview}}',
     '{{senderName}}님이 {{spaceName}} 대화에서 회원님을 언급했습니다.',
     '{"label":"대화 열기","route":"/space/{{conversationId}}"}'::jsonb,
     'PUBLISHED', 'seed-space-v1'),
    ('12000000-0000-0000-0000-000000000004', NULL,
     '11000000-0000-0000-0000-000000000004', 'IN_APP', 'ko-KR', 1,
     '{{requestTitle}} 요청이 제출되었습니다', '결재 상태: {{decision}}',
     '{{requestTitle}} 요청이 제출되었습니다. 요청 상세에서 진행 상태를 확인하세요.',
     '{"label":"요청 보기","route":"/approvals/requests/{{requestId}}"}'::jsonb,
     'PUBLISHED', 'seed-approval-request-submitted-v1'),
    ('12000000-0000-0000-0000-000000000005', NULL,
     '11000000-0000-0000-0000-000000000005', 'IN_APP', 'ko-KR', 1,
     '{{requestTitle}} 요청이 승인되었습니다', '최종 결정: {{decision}}',
     '{{requestTitle}} 요청이 최종 승인되었습니다. 요청 상세에서 결과를 확인하세요.',
     '{"label":"요청 보기","route":"/approvals/requests/{{requestId}}"}'::jsonb,
     'PUBLISHED', 'seed-approval-request-approved-v1'),
    ('12000000-0000-0000-0000-000000000006', NULL,
     '11000000-0000-0000-0000-000000000006', 'IN_APP', 'ko-KR', 1,
     '{{requestTitle}} 요청이 반려되었습니다', '최종 결정: {{decision}}',
     '{{requestTitle}} 요청이 반려되었습니다. 요청 상세에서 결정 내용을 확인하세요.',
     '{"label":"요청 보기","route":"/approvals/requests/{{requestId}}"}'::jsonb,
     'PUBLISHED', 'seed-approval-request-rejected-v1')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_user_delivery_profiles (
    tenant_id, user_id, timezone, quiet_schedule, default_channels,
    digest_frequency, digest_local_time)
VALUES (
    1, 900018, 'Asia/Seoul',
    '{"enabled":false,"start":"22:00","end":"07:00"}'::jsonb,
    '["IN_APP"]'::jsonb, 'IMMEDIATE', TIME '09:00')
ON CONFLICT DO NOTHING;

INSERT INTO ntf_user_counters (tenant_id, user_id)
VALUES (1, 900018)
ON CONFLICT DO NOTHING;

INSERT INTO ntf_notifications (
    notification_id, tenant_id, type_version_id, type_scope_tenant_id, thread_key,
    actor_ref, subject_ref, target_ref, safe_body, action_payload,
    sanitized_template_variables, first_activity_at, last_activity_at,
    occurrence_count, version)
VALUES
    ('20000000-0000-0000-0000-000000000001', 1,
     '11000000-0000-0000-0000-000000000001', 0, 'local-approval-task-001',
     '김민서', 'approval-request:LOCAL-APR-001', '/approvals/requests/30000000-0000-0000-0000-000000000001',
     '클라우드 운영 예산 요청의 검토 차례가 도착했습니다.',
     '{"label":"검토하기","route":"/approvals/requests/30000000-0000-0000-0000-000000000001"}'::jsonb,
     '{"requestTitle":"클라우드 운영 예산","decision":"ASSIGNED"}'::jsonb,
     CURRENT_TIMESTAMP - INTERVAL '18 minutes', CURRENT_TIMESTAMP - INTERVAL '18 minutes', 1, 1),
    ('20000000-0000-0000-0000-000000000002', 1,
     '11000000-0000-0000-0000-000000000004', 0, 'local-approval-request-002',
     '본인', 'approval-request:LOCAL-APR-002', '/approvals/requests/30000000-0000-0000-0000-000000000002',
     '신규 프로젝트 환경 신청이 정상적으로 제출되었습니다.',
     '{"label":"요청 보기","route":"/approvals/requests/30000000-0000-0000-0000-000000000002"}'::jsonb,
     '{"requestTitle":"신규 프로젝트 환경 신청","decision":"SUBMITTED"}'::jsonb,
     CURRENT_TIMESTAMP - INTERVAL '3 hours', CURRENT_TIMESTAMP - INTERVAL '3 hours', 1, 1),
    ('20000000-0000-0000-0000-000000000003', 1,
     '11000000-0000-0000-0000-000000000005', 0, 'local-approval-request-003',
     '워크플로우 시스템', 'approval-request:LOCAL-APR-003', '/approvals/requests/30000000-0000-0000-0000-000000000003',
     '재택근무 장비 구매 요청이 최종 승인되었습니다.',
     '{"label":"요청 보기","route":"/approvals/requests/30000000-0000-0000-0000-000000000003"}'::jsonb,
     '{"requestTitle":"재택근무 장비 구매","decision":"APPROVED"}'::jsonb,
     CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '1 day', 1, 1),
    ('20000000-0000-0000-0000-000000000004', 1,
     '11000000-0000-0000-0000-000000000006', 0, 'local-approval-request-004',
     '워크플로우 시스템', 'approval-request:LOCAL-APR-004', '/approvals/requests/30000000-0000-0000-0000-000000000004',
     '외부 교육 참가 요청이 반려되었습니다. 결정 내용을 확인하세요.',
     '{"label":"요청 보기","route":"/approvals/requests/30000000-0000-0000-0000-000000000004"}'::jsonb,
     '{"requestTitle":"외부 교육 참가","decision":"REJECTED"}'::jsonb,
     CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '2 hours', 1, 1),
    ('20000000-0000-0000-0000-000000000005', 1,
     '11000000-0000-0000-0000-000000000002', 0, 'local-hcm-leave-001',
     'People Operations', 'leave-request:LOCAL-HCM-001', '/hr/time-off',
     '8월 24일 연차 일정이 승인되었습니다.',
     '{"label":"휴가 보기","route":"/hr/time-off"}'::jsonb,
     '{"leavePeriod":"8월 24일"}'::jsonb,
     CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days', 1, 1),
    ('20000000-0000-0000-0000-000000000006', 1,
     '11000000-0000-0000-0000-000000000003', 0, 'local-space-mention-001',
     '박현우', 'space-conversation:LOCAL-SPACE-001', '/space/LOCAL-SPACE-001',
     '프로젝트 채널에서 다음 주 배포 계획에 대한 의견을 요청했습니다.',
     '{"label":"대화 열기","route":"/space/LOCAL-SPACE-001"}'::jsonb,
     '{"spaceName":"AX 프로젝트","senderName":"박현우"}'::jsonb,
     CURRENT_TIMESTAMP - INTERVAL '45 minutes', CURRENT_TIMESTAMP - INTERVAL '45 minutes', 1, 1),
    ('20000000-0000-0000-0000-000000000007', 1,
     '11000000-0000-0000-0000-000000000003', 0, 'local-space-mention-002',
     '이서연', 'space-conversation:LOCAL-SPACE-002', '/space/LOCAL-SPACE-002',
     '완료된 운영 회고 대화에서 회원님을 언급했습니다.',
     '{"label":"대화 열기","route":"/space/LOCAL-SPACE-002"}'::jsonb,
     '{"spaceName":"운영 회고","senderName":"이서연"}'::jsonb,
     CURRENT_TIMESTAMP - INTERVAL '4 days', CURRENT_TIMESTAMP - INTERVAL '4 days', 1, 1)
ON CONFLICT (notification_id) DO NOTHING;

INSERT INTO ntf_user_notifications (
    tenant_id, user_id, notification_id, reason_code, effective_priority,
    action_required, due_at, locale, in_app_template_version_id,
    template_scope_tenant_id, safe_title, safe_preview, search_text, inbox_state,
    read_at, saved_at, completed_at, snoozed_until,
    last_activity_at, change_version, version)
VALUES
    (1, 900018, '20000000-0000-0000-0000-000000000001', 'DIRECT', 'URGENT',
     TRUE, CURRENT_TIMESTAMP + INTERVAL '4 hours', 'ko-KR',
     '12000000-0000-0000-0000-000000000001', 0,
     '클라우드 운영 예산 승인이 필요합니다', '김민서님이 오늘 안으로 검토를 요청했습니다.',
     '클라우드 운영 예산 승인 김민서 긴급', 'ACTIVE',
     NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '18 minutes', 1, 1),
    (1, 900018, '20000000-0000-0000-0000-000000000002', 'DIRECT', 'NORMAL',
     FALSE, NULL, 'ko-KR', '12000000-0000-0000-0000-000000000004', 0,
     '신규 프로젝트 환경 신청이 제출되었습니다', '결재 상태: SUBMITTED',
     '신규 프로젝트 환경 신청 제출', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '2 hours', NULL, NULL, NULL,
     CURRENT_TIMESTAMP - INTERVAL '3 hours', 2, 1),
    (1, 900018, '20000000-0000-0000-0000-000000000003', 'DIRECT', 'NORMAL',
     FALSE, NULL, 'ko-KR', '12000000-0000-0000-0000-000000000005', 0,
     '재택근무 장비 구매 요청이 승인되었습니다', '최종 결정: APPROVED',
     '재택근무 장비 구매 승인', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '20 hours', CURRENT_TIMESTAMP - INTERVAL '20 hours',
     NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '1 day', 3, 1),
    (1, 900018, '20000000-0000-0000-0000-000000000004', 'DIRECT', 'HIGH',
     FALSE, NULL, 'ko-KR', '12000000-0000-0000-0000-000000000006', 0,
     '외부 교육 참가 요청이 반려되었습니다', '최종 결정: REJECTED',
     '외부 교육 참가 반려 결정', 'ACTIVE',
     NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '2 hours', 4, 1),
    (1, 900018, '20000000-0000-0000-0000-000000000005', 'DIRECT', 'LOW',
     FALSE, NULL, 'ko-KR', '12000000-0000-0000-0000-000000000002', 0,
     '8월 24일 연차가 승인되었습니다', '휴가 일정과 잔여 연차를 확인해 보세요.',
     '연차 휴가 승인 8월 24일', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '1 day', NULL, NULL, NULL,
     CURRENT_TIMESTAMP - INTERVAL '2 days', 5, 1),
    (1, 900018, '20000000-0000-0000-0000-000000000006', 'MENTION', 'NORMAL',
     TRUE, NULL, 'ko-KR', '12000000-0000-0000-0000-000000000003', 0,
     'AX 프로젝트에서 회원님을 언급했습니다', '박현우: 다음 주 배포 계획을 함께 확인해 주세요.',
     'AX 프로젝트 박현우 배포 계획 언급', 'ACTIVE',
     NULL, NULL, NULL, CURRENT_TIMESTAMP + INTERVAL '2 days',
     CURRENT_TIMESTAMP - INTERVAL '45 minutes', 6, 1),
    (1, 900018, '20000000-0000-0000-0000-000000000007', 'MENTION', 'NORMAL',
     FALSE, NULL, 'ko-KR', '12000000-0000-0000-0000-000000000003', 0,
     '운영 회고에서 회원님을 언급했습니다', '이서연: 회고 정리 내용을 확인했습니다.',
     '운영 회고 이서연 완료 언급', 'DONE',
     CURRENT_TIMESTAMP - INTERVAL '3 days', NULL, CURRENT_TIMESTAMP - INTERVAL '3 days', NULL,
     CURRENT_TIMESTAMP - INTERVAL '4 days', 7, 1)
ON CONFLICT (tenant_id, user_id, notification_id) DO NOTHING;

UPDATE ntf_user_counters counter
   SET unread_count = projection.unread_count,
       actionable_unread_count = projection.actionable_unread_count,
       urgent_count = projection.urgent_count,
       counter_version = projection.counter_version,
       min_available_change_version = LEAST(
           counter.min_available_change_version, projection.counter_version),
       updated_at = CURRENT_TIMESTAMP
  FROM (
      SELECT tenant_id, user_id,
             COUNT(*) FILTER (
                 WHERE inbox_state = 'ACTIVE'
                   AND read_at IS NULL
                   AND (snoozed_until IS NULL OR snoozed_until <= CURRENT_TIMESTAMP)) AS unread_count,
             COUNT(*) FILTER (
                 WHERE inbox_state = 'ACTIVE'
                   AND read_at IS NULL
                   AND action_required
                   AND (snoozed_until IS NULL OR snoozed_until <= CURRENT_TIMESTAMP)) AS actionable_unread_count,
             COUNT(*) FILTER (
                 WHERE inbox_state = 'ACTIVE'
                   AND read_at IS NULL
                   AND effective_priority = 'URGENT'
                   AND (snoozed_until IS NULL OR snoozed_until <= CURRENT_TIMESTAMP)) AS urgent_count,
             COALESCE(MAX(change_version), 0) AS counter_version
        FROM ntf_user_notifications
       WHERE tenant_id = 1 AND user_id = 900018
       GROUP BY tenant_id, user_id
  ) projection
 WHERE counter.tenant_id = projection.tenant_id
   AND counter.user_id = projection.user_id;
