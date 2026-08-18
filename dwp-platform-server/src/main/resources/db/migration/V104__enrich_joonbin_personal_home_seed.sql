-- Enrich the local integrated verification account with a realistic personal-home dataset.
-- This remains user-scoped: tenant communications are shared, while work and activity
-- projections are owned by joonbin@sk.com (IAM user 900018).

WITH target_user AS (
    SELECT tenant.tenant_id, identity_link.user_id
      FROM sys_service_tenants tenant
      JOIN cal_identity_links identity_link
        ON identity_link.tenant_id = tenant.tenant_id
       AND identity_link.user_id = 900018
     WHERE tenant.tenant_key = 'default'
), seed (
    work_key, title_ko, title_en, summary_ko, summary_en,
    work_type, priority, lifecycle_state, owner_name, due_in,
    source_system, source_reference, source_route,
    reason_ko, reason_en, recommended_next_ko, recommended_next_en,
    latest_activity_ko, latest_activity_en
) AS (
    VALUES
        (
            'JOONBIN-HOME-01',
            '변경 배포 계획 최종 승인',
            'Final approval for the change deployment plan',
            '오늘 배포 예정인 워크스페이스 변경의 영향 범위와 복구 계획을 확인합니다.',
            'Review the impact scope and rollback plan for today''s workspace deployment.',
            'APPROVAL', 'HIGH', 'DUE_SOON', 'Platform Operations', INTERVAL '45 minutes',
            'Approval Service', 'CHG-2026-0818-014', '/approvals/inbox',
            '배포 창이 가까워 최종 승인 대기 시간이 짧습니다.',
            'The deployment window is approaching and final approval is pending.',
            '영향 서비스와 복구 담당자를 확인한 뒤 승인 여부를 결정하세요.',
            'Confirm the impacted services and rollback owner, then decide on approval.',
            '안전 점검 6개 중 5개가 완료되었습니다.',
            'Five of six safety checks are complete.'
        ),
        (
            'JOONBIN-HOME-02',
            '주간 운영 리스크 브리핑 검토',
            'Review the weekly operational risk briefing',
            '고객 영향 가능성이 높은 세 가지 운영 신호와 권장 조치를 검토합니다.',
            'Review three operational signals with potential customer impact and their recommended actions.',
            'TASK', 'HIGH', 'DUE_SOON', 'DWP Operations', INTERVAL '2 hours',
            'DWP Operations', 'OPS-RISK-WEEKLY-0818', '/work',
            '이번 주 서비스 품질 회의 전에 우선순위 합의가 필요합니다.',
            'Priorities need alignment before this week''s service quality review.',
            '상위 위험 두 건의 담당자와 완료 목표를 지정하세요.',
            'Assign owners and completion targets to the top two risks.',
            '운영 데이터와 고객 문의 28건의 상관 분석이 완료되었습니다.',
            'Correlation analysis of operational data and 28 customer inquiries is complete.'
        ),
        (
            'JOONBIN-HOME-03',
            '생성형 AI 보안 원칙 확인',
            'Acknowledge the generative AI security principles',
            '업무 데이터 입력 범위와 외부 AI 도구 사용 기준의 개정 내용을 확인합니다.',
            'Review the updated rules for business data input and external AI tools.',
            'REQUIRED', 'HIGH', 'DUE_SOON', 'Information Security', INTERVAL '5 hours',
            'Security Center', 'POLICY-GENAI-2026-H2', '/communications/required',
            '정책 개정 확인 기한이 오늘 종료됩니다.',
            'The acknowledgement window closes today.',
            '변경 요약을 읽고 필수 확인을 완료하세요.',
            'Read the change summary and complete the required acknowledgement.',
            '개정된 세 가지 데이터 분류 기준이 적용되었습니다.',
            'Three revised data-classification rules are now in effect.'
        ),
        (
            'JOONBIN-HOME-04',
            '고객 워크숍 후속 조치 정리',
            'Organize customer workshop follow-ups',
            '워크숍에서 합의한 실행 항목을 담당자와 목표 일정에 연결합니다.',
            'Connect agreed workshop actions to owners and target dates.',
            'TASK', 'MEDIUM', 'IN_PROGRESS', 'Customer Success', INTERVAL '1 day',
            'Work Management', 'CUSTOMER-WORKSHOP-42', '/work',
            '다음 고객 체크인 전에 합의 사항을 실행 가능한 작업으로 전환해야 합니다.',
            'Agreements should become actionable work before the next customer check-in.',
            '미지정 항목 두 건의 담당자를 선택하세요.',
            'Choose owners for the two unassigned actions.',
            '회의 메모에서 실행 항목 7개를 추출했습니다.',
            'Seven action items were extracted from the meeting notes.'
        ),
        (
            'JOONBIN-HOME-05',
            '홈 경험 개선안 피드백',
            'Provide feedback on the home experience proposal',
            '개인 홈의 정보 우선순위와 위젯 상호작용 개선안을 검토합니다.',
            'Review proposed improvements to information priority and widget interactions on personal home.',
            'TASK', 'MEDIUM', 'IN_PROGRESS', 'Experience Design', INTERVAL '2 days',
            'DWP Experience', 'HOME-EXPERIENCE-REVIEW-08', '/work',
            '다음 디자인 검토에서 의사결정할 사용자 피드백이 준비되었습니다.',
            'User feedback is ready for the next design review decision.',
            '핵심 사용자 여정 세 가지에 의견을 남기세요.',
            'Leave feedback on the three primary user journeys.',
            '사용성 인터뷰 12건의 공통 패턴이 정리되었습니다.',
            'Common patterns from 12 usability interviews have been summarized.'
        ),
        (
            'JOONBIN-HOME-06',
            '신규 구성원 앱 접근 검토',
            'Review application access for new joiners',
            '이번 주 입사자의 역할 기반 앱 권한과 예외 요청을 검토합니다.',
            'Review role-based application access and exceptions for this week''s new joiners.',
            'APPROVAL', 'MEDIUM', 'WAITING', 'Identity Operations', INTERVAL '3 days',
            'Access Control', 'ACCESS-REVIEW-NEW-HIRES-34', '/admin/access',
            '관리자 확인이 필요한 예외 요청 한 건이 포함되어 있습니다.',
            'One exception request requires administrator confirmation.',
            '직무 템플릿과 예외 사유를 비교한 뒤 검토를 완료하세요.',
            'Compare the job template with the exception rationale and complete the review.',
            '표준 권한 18개가 자동 검증되었습니다.',
            'Eighteen standard entitlements were validated automatically.'
        ),
        (
            'JOONBIN-HOME-07',
            'AI 업무 활용 학습 경로 이어보기',
            'Continue the AI productivity learning path',
            '실무 프롬프트 설계와 데이터 보호 모듈을 이어서 학습합니다.',
            'Continue the practical prompt design and data protection modules.',
            'SERVICE', 'LOW', 'IN_PROGRESS', 'Talent Growth', INTERVAL '5 days',
            'HCM Learning', 'LEARNING-AI-PRODUCTIVITY-02', '/hr/home',
            '현재 진도는 68%이며 두 개 모듈이 남아 있습니다.',
            'Progress is at 68%, with two modules remaining.',
            '15분 분량의 다음 모듈을 시작하세요.',
            'Start the next 15-minute module.',
            '실무 프롬프트 설계 모듈을 완료했습니다.',
            'The practical prompt design module is complete.'
        )
)
INSERT INTO wrk_items (
    work_item_id, tenant_id, work_key, title_ko, title_en, summary_ko, summary_en,
    work_type, priority, lifecycle_state, owner_name, assignee_user_id, due_at,
    source_system, source_reference, source_route,
    reason_ko, reason_en, recommended_next_ko, recommended_next_en,
    latest_activity_ko, latest_activity_en, created_by, updated_by)
SELECT md5('workspace:joonbin:' || target_user.tenant_id || ':' || seed.work_key)::uuid,
       target_user.tenant_id, seed.work_key, seed.title_ko, seed.title_en,
       seed.summary_ko, seed.summary_en, seed.work_type, seed.priority,
       seed.lifecycle_state, seed.owner_name, target_user.user_id,
       CURRENT_TIMESTAMP + seed.due_in, seed.source_system,
       seed.source_reference, seed.source_route, seed.reason_ko, seed.reason_en,
       seed.recommended_next_ko, seed.recommended_next_en,
       seed.latest_activity_ko, seed.latest_activity_en, 1, 1
  FROM target_user
 CROSS JOIN seed
ON CONFLICT (tenant_id, work_key) DO NOTHING;

WITH target_user AS (
    SELECT tenant.tenant_id, identity_link.user_id
      FROM sys_service_tenants tenant
      JOIN cal_identity_links identity_link
        ON identity_link.tenant_id = tenant.tenant_id
       AND identity_link.user_id = 900018
     WHERE tenant.tenant_key = 'default'
), seed (
    seed_key, actor_kind, actor_name, event_state, title_ko, title_en,
    summary_ko, summary_en, object_type, object_label_ko, object_label_en,
    source_system, tool_name, progress, source_route, occurred_ago
) AS (
    VALUES
        (
            'weekly-risk-digest', 'AGENT', 'DWP Copilot', 'RUNNING',
            '주간 운영 리스크 브리핑을 준비하고 있습니다',
            'Preparing the weekly operational risk briefing',
            '서비스 신호와 고객 문의를 연결해 우선 검토할 위험을 정리합니다.',
            'Correlating service signals with customer inquiries to prioritize risks.',
            'RISK_BRIEFING', '주간 운영 리스크', 'Weekly operational risks',
            'DWP Agent', 'Risk briefing agent', 72, '/work', INTERVAL '2 minutes'
        ),
        (
            'deployment-approval', 'SYSTEM', 'Approval workflow', 'NEEDS_INPUT',
            '변경 배포 계획의 최종 검토가 필요합니다',
            'The change deployment plan needs final review',
            '자동 안전 점검은 완료되었으며 승인자의 판단을 기다리고 있습니다.',
            'Automated safety checks are complete and the workflow awaits an approver decision.',
            'APPROVAL', '변경 배포 계획', 'Change deployment plan',
            'Approval Service', 'Change workflow', NULL::integer, '/approvals/inbox', INTERVAL '7 minutes'
        ),
        (
            'workforce-sync', 'SYSTEM', 'People sync', 'COMPLETED',
            '구성원과 조직 정보 동기화를 완료했습니다',
            'Workforce and organization synchronization completed',
            '변경된 소속과 직책을 검증하고 최신 조직 정보에 반영했습니다.',
            'Validated changed assignments and titles and projected the latest organization data.',
            'WORKFORCE_SYNC', 'SKAX 조직 정보', 'SKAX organization data',
            'People Service', 'HRIS projection', 100, '/hr/home', INTERVAL '13 minutes'
        ),
        (
            'external-share-policy', 'SYSTEM', 'Policy engine', 'POLICY_BLOCKED',
            '정책에 따라 외부 공유를 안전하게 차단했습니다',
            'External sharing was safely blocked by policy',
            '기밀 데이터가 포함된 문서의 외부 링크 생성을 차단하고 검토 경로를 안내했습니다.',
            'Blocked an external link for a document containing confidential data and provided a review path.',
            'POLICY_DECISION', '고객 분석 문서', 'Customer analysis document',
            'Security Center', 'Data loss prevention', NULL::integer, '/communications/required', INTERVAL '24 minutes'
        ),
        (
            'customer-actions', 'PERSON', '최준빈', 'COMPLETED',
            '고객 워크숍의 실행 항목을 업데이트했습니다',
            'Updated action items from the customer workshop',
            '회의 메모를 정리하고 우선순위와 목표 일정을 추가했습니다.',
            'Organized the meeting notes and added priorities and target dates.',
            'WORK_ITEM', '고객 워크숍 후속 조치', 'Customer workshop follow-ups',
            'Work Management', NULL::varchar, 100, '/work', INTERVAL '38 minutes'
        ),
        (
            'home-insights', 'AGENT', 'DWP Copilot', 'COMPLETED',
            '오늘의 홈 인사이트 구성을 완료했습니다',
            'Completed today''s home insight composition',
            '마감 업무, 일정 충돌, 필수 소식을 분석해 홈의 우선순위를 조정했습니다.',
            'Analyzed due work, calendar conflicts, and required news to prioritize the home experience.',
            'HOME_INSIGHT', '개인 홈 인사이트', 'Personal home insights',
            'DWP Agent', 'Home orchestration', 100, '/', INTERVAL '52 minutes'
        )
)
INSERT INTO wrk_activity_events (
    activity_event_id, tenant_id, visible_to_user_id, actor_kind, actor_name,
    event_state, title_ko, title_en, summary_ko, summary_en,
    object_type, object_label_ko, object_label_en, source_system, tool_name,
    audit_reference, progress, source_route, occurred_at)
SELECT md5('activity:joonbin:' || target_user.tenant_id || ':' || seed.seed_key)::uuid,
       target_user.tenant_id, target_user.user_id, seed.actor_kind, seed.actor_name,
       seed.event_state, seed.title_ko, seed.title_en, seed.summary_ko, seed.summary_en,
       seed.object_type, seed.object_label_ko, seed.object_label_en,
       seed.source_system, seed.tool_name,
       'HOME-SEED-' || UPPER(REPLACE(seed.seed_key, '-', '_')),
       seed.progress, seed.source_route, CURRENT_TIMESTAMP - seed.occurred_ago
  FROM target_user
 CROSS JOIN seed
ON CONFLICT (activity_event_id) DO NOTHING;

WITH target_user AS (
    SELECT tenant.tenant_id, identity_link.user_id,
           identity_link.person_public_id, calendar.calendar_id
      FROM sys_service_tenants tenant
      JOIN cal_identity_links identity_link
        ON identity_link.tenant_id = tenant.tenant_id
       AND identity_link.user_id = 900018
      JOIN cal_calendars calendar
        ON calendar.tenant_id = tenant.tenant_id
       AND calendar.calendar_key = 'personal-' || identity_link.user_id
     WHERE tenant.tenant_key = 'default'
), seed (
    seed_key, day_offset, start_offset, duration_minutes, title, description,
    event_type, visibility, response_required, location, conference_url
) AS (
    VALUES
        (
            'daily-priorities', 0, INTERVAL '9 hours 10 minutes', 25,
            '오늘의 우선순위 정리',
            '마감 업무와 협업 일정을 확인하고 오늘의 실행 순서를 정리합니다.',
            'TASK', 'PRIVATE', FALSE, NULL::varchar, NULL::varchar
        ),
        (
            'operations-sync', 0, INTERVAL '10 hours 30 minutes', 50,
            '운영 지휘 주간 싱크',
            '서비스 품질 신호와 고객 영향 가능성을 함께 검토합니다.',
            'MEETING', 'DEFAULT', TRUE, '온라인 협업 공간',
            'https://meet.dwp.local/skax/900018/operations-sync'
        ),
        (
            'customer-follow-up', 0, INTERVAL '13 hours 30 minutes', 45,
            '고객 워크숍 후속 조치',
            '합의한 실행 항목의 담당자와 완료 목표를 확정합니다.',
            'MEETING', 'DEFAULT', TRUE, '프로젝트룸 A',
            'https://meet.dwp.local/skax/900018/customer-follow-up'
        ),
        (
            'experience-focus', 0, INTERVAL '15 hours', 90,
            '집중 업무 · 홈 경험 개선안',
            '알림을 최소화하고 홈 경험 개선안의 핵심 흐름을 설계합니다.',
            'FOCUS', 'PRIVATE', FALSE, NULL::varchar, NULL::varchar
        ),
        (
            'daily-review', 0, INTERVAL '17 hours 20 minutes', 20,
            '업무 회고와 내일 준비',
            '완료한 일과 남은 의사결정을 정리하고 내일의 첫 행동을 선택합니다.',
            'REMINDER', 'PRIVATE', FALSE, NULL::varchar, NULL::varchar
        ),
        (
            'service-quality-review', 1, INTERVAL '9 hours 30 minutes', 60,
            '서비스 품질 리뷰',
            '주요 서비스 지표와 반복 이슈의 개선 계획을 검토합니다.',
            'MEETING', 'DEFAULT', TRUE, '온라인 협업 공간',
            'https://meet.dwp.local/skax/900018/service-quality-review'
        ),
        (
            'hcm-check-in', 1, INTERVAL '14 hours', 45,
            'HCM 경험 개선 체크인',
            '구성원 여정의 개선 지표와 다음 실험 범위를 합의합니다.',
            'MEETING', 'DEFAULT', TRUE, '디지털 캠퍼스 8F',
            'https://meet.dwp.local/skax/900018/hcm-check-in'
        ),
        (
            'ai-working-session', 2, INTERVAL '10 hours', 75,
            'AI 업무 활용 워킹 세션',
            '실제 업무 시나리오를 바탕으로 안전한 AI 활용 패턴을 정리합니다.',
            'MEETING', 'DEFAULT', TRUE, 'AX Lab',
            'https://meet.dwp.local/skax/900018/ai-working-session'
        )
), resolved AS (
    SELECT target_user.*,
           seed.*,
           date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + make_interval(days => seed.day_offset)
               + seed.start_offset AS starts_at_local
      FROM target_user
     CROSS JOIN seed
)
INSERT INTO cal_events (
    event_id, tenant_id, calendar_id, organizer_user_id,
    organizer_person_public_id, organizer_name, organizer_email,
    title, description, event_type, starts_at, ends_at, time_zone,
    all_day, location, conference_url, status, visibility,
    recurrence_pattern, recurrence_interval, recurrence_until,
    response_required, source_type, source_ref, idempotency_key,
    created_by, updated_by)
SELECT md5('calendar:joonbin:' || resolved.tenant_id || ':' || resolved.seed_key)::uuid,
       resolved.tenant_id, resolved.calendar_id, resolved.user_id,
       resolved.person_public_id, '최준빈', 'joonbin@sk.com',
       resolved.title, resolved.description, resolved.event_type,
       resolved.starts_at_local AT TIME ZONE 'Asia/Seoul',
       (resolved.starts_at_local + make_interval(mins => resolved.duration_minutes))
           AT TIME ZONE 'Asia/Seoul',
       'Asia/Seoul', FALSE, resolved.location, resolved.conference_url,
       'CONFIRMED', resolved.visibility, 'NONE', 1, NULL,
       resolved.response_required, 'NATIVE',
       'seed:joonbin:' || resolved.seed_key,
       md5('calendar:idempotency:joonbin:' || resolved.tenant_id || ':' || resolved.seed_key)::uuid,
       resolved.user_id, resolved.user_id
  FROM resolved
ON CONFLICT (event_id) DO NOTHING;

WITH target_tenant AS (
    SELECT tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
), seed (
    title, summary, body, severity, content_type, category_key,
    cover_image_url, publisher_name, action_label, action_url,
    published_ago, ends_in, reading_minutes
) AS (
    VALUES
        (
            '이번 주 DWP 업데이트: 더 빠른 업무 흐름',
            '홈과 승인함, 일정에서 달라진 핵심 경험을 3분 안에 확인해 보세요.',
            E'이번 업데이트는 사용자가 다음 행동을 더 빠르게 결정할 수 있도록 홈의 정보 우선순위와 승인 흐름을 다듬었습니다.\n\n일정 충돌과 마감 업무가 같은 맥락에서 연결되고, 필요한 화면으로 바로 이동할 수 있습니다.',
            'INFO', 'NEWS', 'INNOVATION',
            '/media/communications/innovation-lab.jpg', 'DWP Product',
            '업데이트 살펴보기', '/communications/all',
            INTERVAL '10 minutes', INTERVAL '30 days', 3
        ),
        (
            'AI 업무 활용 클리닉, 실전 질문을 받습니다',
            '현업 시나리오를 가져오면 안전한 활용 패턴과 자동화 아이디어를 함께 설계합니다.',
            E'문서 요약, 회의 준비, 데이터 탐색처럼 반복되는 업무를 대상으로 실전 클리닉을 운영합니다.\n\n참가자는 실제 고민을 제출하고 정보보호와 품질 원칙을 함께 확인할 수 있습니다.',
            'SUCCESS', 'EVENT', 'GROWTH',
            '/media/communications/innovation-lab.jpg', 'AX Enablement',
            '세션 확인', '/communications/all',
            INTERVAL '35 minutes', INTERVAL '21 days', 3
        ),
        (
            '고객 성공 사례: 프로젝트 인사이트를 공유합니다',
            '고객 과제에서 발견한 실행 원칙과 재사용 가능한 업무 패턴을 공개합니다.',
            E'세 개 프로젝트가 고객의 의사결정 시간을 줄인 방법을 정리했습니다. 각 사례에는 문제 정의, 실험 과정, 측정 지표와 다음 개선점이 포함됩니다.',
            'INFO', 'NEWS', 'LEADERSHIP',
            '/media/communications/community-day.jpg', 'Customer Success Office',
            '사례 읽기', '/communications/all',
            INTERVAL '2 hours', INTERVAL '35 days', 4
        ),
        (
            '사내 기술 커뮤니티 오픈 데이',
            '플랫폼, 데이터, 보안 전문가와 현재의 기술 과제를 짧고 깊게 나눕니다.',
            E'오픈 데이는 발표보다 대화에 집중합니다. 관심 주제별 라운드테이블과 데모 부스를 자유롭게 오가며 동료의 경험과 도구를 발견할 수 있습니다.',
            'SUCCESS', 'EVENT', 'CULTURE',
            '/media/communications/community-day.jpg', 'Engineering Community',
            '프로그램 보기', '/communications/all',
            INTERVAL '5 hours', INTERVAL '18 days', 3
        ),
        (
            '8월 웰니스 챌린지 참여 안내',
            '일하는 리듬을 회복하는 2주 프로그램과 팀 참여 방법을 확인하세요.',
            E'집중 시간 보호, 가벼운 움직임, 연결의 세 가지 주제로 매일 작은 실천을 제안합니다. 개인 기록은 본인에게만 보이며 팀에는 참여율만 집계됩니다.',
            'INFO', 'EVENT', 'CULTURE',
            '/media/communications/community-day.jpg', 'People & Culture',
            '참여 방법 보기', '/communications/all',
            INTERVAL '9 hours', INTERVAL '14 days', 2
        ),
        (
            '업무 공간 개선 제안 결과를 공개합니다',
            '구성원 제안 186건 중 이번 분기에 반영할 개선 항목과 진행 일정을 공유합니다.',
            E'집중 공간 예약, 협업 장비, 회의실 사용 경험에 대한 제안을 검토했습니다. 우선 반영 항목과 검토 중인 항목을 구분해 진행 상황을 지속적으로 알리겠습니다.',
            'INFO', 'ANNOUNCEMENT', 'CULTURE',
            '/media/communications/community-day.jpg', 'Workplace Experience',
            '결과 확인', '/communications/all',
            INTERVAL '14 hours', INTERVAL '28 days', 3
        )
)
INSERT INTO adm_announcements (
    tenant_id, title, message, body, severity, lifecycle_state,
    audience_type, starts_at, ends_at, pinned, action_label, action_url,
    published_at, published_by, content_type, category_key, cover_image_url,
    publisher_name, featured, acknowledgement_required,
    acknowledgement_due_at, dismissible, reading_minutes, source_locale,
    created_by, updated_by)
SELECT target_tenant.tenant_id, seed.title, seed.summary, seed.body,
       seed.severity, 'PUBLISHED', 'ALL', CURRENT_TIMESTAMP - INTERVAL '1 day',
       CURRENT_TIMESTAMP + seed.ends_in, FALSE, seed.action_label, seed.action_url,
       CURRENT_TIMESTAMP - seed.published_ago, 1, seed.content_type,
       seed.category_key, seed.cover_image_url, seed.publisher_name,
       FALSE, FALSE, NULL, TRUE, seed.reading_minutes, 'ko', 1, 1
  FROM target_tenant
 CROSS JOIN seed
 WHERE NOT EXISTS (
    SELECT 1
      FROM adm_announcements existing
     WHERE existing.tenant_id = target_tenant.tenant_id
       AND existing.title = seed.title);
