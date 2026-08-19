INSERT INTO spc_tenants (
    tenant_id, creation_policy, external_sharing_enabled,
    default_retention_days, lifecycle_state)
VALUES (1, 'POLICY_DRIVEN', FALSE, 365, 'ACTIVE')
ON CONFLICT (tenant_id) DO UPDATE SET
    creation_policy = EXCLUDED.creation_policy,
    external_sharing_enabled = EXCLUDED.external_sharing_enabled,
    default_retention_days = EXCLUDED.default_retention_days,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO spc_templates (
    template_id, tenant_id, template_key, name_ko, name_en,
    description_ko, description_en, purpose_type, creation_mode,
    default_visibility, default_data_classification, default_member_role,
    allowed_content_types, default_apps, icon_key, accent_token,
    lifecycle_state, current_version, created_by, updated_by)
VALUES
    ('10000000-0000-4000-8000-000000000001', 1, 'PROJECT_DELIVERY',
     '프로젝트 딜리버리', 'Project delivery',
     '목표, 의사결정, 산출물과 실행 현황을 한 흐름으로 운영합니다.',
     'Run goals, decisions, deliverables, and execution in one governed flow.',
     'PROJECT', 'POLICY', 'REQUEST', 'CONFIDENTIAL', 'CONTRIBUTOR',
     '["PAGE","FILE","DECISION","CANVAS","LINK"]',
     '["approvals","calendar","knowledge"]', 'briefcase-business', 'indigo',
     'PUBLISHED', 1, 1, 1),
    ('10000000-0000-4000-8000-000000000002', 1, 'COMMUNITY_PRACTICE',
     '전문가 커뮤니티', 'Community of practice',
     '전문 지식과 질문, 사례를 개방형 커뮤니티에서 축적합니다.',
     'Build reusable expertise, questions, and field practices in an open community.',
     'COMMUNITY', 'AUTO', 'OPEN', 'INTERNAL', 'CONTRIBUTOR',
     '["POST","PAGE","LINK","FILE"]',
     '["communications","knowledge"]', 'messages-square', 'teal',
     'PUBLISHED', 1, 1, 1),
    ('10000000-0000-4000-8000-000000000003', 1, 'OPERATIONS_ROOM',
     '운영 컨트롤룸', 'Operations room',
     '서비스 신호, 대응 작업과 회고 근거를 제한된 운영 그룹에서 관리합니다.',
     'Coordinate service signals, response actions, and review evidence in a restricted room.',
     'OPERATIONS', 'APPROVAL', 'PRIVATE', 'RESTRICTED', 'CONTRIBUTOR',
     '["POST","DECISION","FILE","APP_EMBED"]',
     '["activity","approvals","service-center"]', 'radio-tower', 'amber',
     'PUBLISHED', 1, 1, 1),
    ('10000000-0000-4000-8000-000000000004', 1, 'LEADERSHIP_CHANNEL',
     '리더십 채널', 'Leadership channel',
     '리더십 메시지, 경영 의사결정과 제한 배포 자료를 보호합니다.',
     'Protect leadership communication, executive decisions, and restricted material.',
     'LEADERSHIP', 'APPROVAL', 'HIDDEN', 'RESTRICTED', 'VIEWER',
     '["PAGE","DECISION","FILE"]',
     '["approvals","calendar"]', 'landmark', 'crimson',
     'PUBLISHED', 1, 1, 1)
ON CONFLICT (tenant_id, template_key) DO UPDATE SET
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    purpose_type = EXCLUDED.purpose_type,
    creation_mode = EXCLUDED.creation_mode,
    default_visibility = EXCLUDED.default_visibility,
    default_data_classification = EXCLUDED.default_data_classification,
    default_member_role = EXCLUDED.default_member_role,
    allowed_content_types = EXCLUDED.allowed_content_types,
    default_apps = EXCLUDED.default_apps,
    icon_key = EXCLUDED.icon_key,
    accent_token = EXCLUDED.accent_token,
    lifecycle_state = 'PUBLISHED',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO spc_spaces (
    space_id, tenant_id, template_id, space_key, name_ko, name_en,
    summary_ko, summary_en, purpose_type, visibility, data_classification,
    content_policy, app_policy, ai_policy, icon_key, accent_token,
    lifecycle_state, activated_at, last_activity_at, created_by, updated_by)
VALUES
    ('20000000-0000-4000-8000-000000000001', 1,
     '10000000-0000-4000-8000-000000000002', 'company-square',
     'SKAX 타운스퀘어', 'SKAX Town Square',
     '전사 소식, 질문과 구성원 참여가 모이는 열린 공간입니다.',
     'An open company space for news, questions, and participation.',
     'COMMUNITY', 'OPEN', 'INTERNAL', 'OWNER_REVIEW', 'OWNER_REVIEW',
     'MEMBER_SCOPED', 'building-2', 'cobalt', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '180 days', CURRENT_TIMESTAMP - INTERVAL '12 minutes', 1, 1),
    ('20000000-0000-4000-8000-000000000002', 1,
     '10000000-0000-4000-8000-000000000001', 'ai-transformation',
     'AI 전환 프로그램', 'AI Transformation Program',
     'AI 업무 전환 과제와 의사결정, 검증 근거를 연결합니다.',
     'Connect AI transformation initiatives, decisions, and validation evidence.',
     'PROJECT', 'REQUEST', 'CONFIDENTIAL', 'OWNER_REVIEW', 'ADMIN_REVIEW',
     'RESTRICTED_SCOPED', 'sparkles', 'violet', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '74 days', CURRENT_TIMESTAMP - INTERVAL '28 minutes', 1, 1),
    ('20000000-0000-4000-8000-000000000003', 1,
     '10000000-0000-4000-8000-000000000003', 'customer-zero-operations',
     'Customer Zero 운영실', 'Customer Zero Operations',
     '내부 도입 신호, 서비스 대응과 재발 방지 조치를 운영합니다.',
     'Operate internal adoption signals, service response, and prevention actions.',
     'OPERATIONS', 'PRIVATE', 'RESTRICTED', 'COMPLIANCE_REVIEW', 'ADMIN_REVIEW',
     'RESTRICTED_SCOPED', 'radio-tower', 'amber', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '42 days', CURRENT_TIMESTAMP - INTERVAL '1 hour', 1, 1),
    ('20000000-0000-4000-8000-000000000004', 1,
     '10000000-0000-4000-8000-000000000002', 'engineering-guild',
     '엔지니어링 길드', 'Engineering Guild',
     '플랫폼 엔지니어링 사례와 기술 표준을 공동으로 발전시킵니다.',
     'Evolve platform engineering practices and technical standards together.',
     'COMMUNITY', 'OPEN', 'INTERNAL', 'OPEN_PUBLISH', 'OWNER_MANAGED',
     'MEMBER_SCOPED', 'blocks', 'teal', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '96 days', CURRENT_TIMESTAMP - INTERVAL '3 hours', 1, 1),
    ('20000000-0000-4000-8000-000000000005', 1,
     '10000000-0000-4000-8000-000000000004', 'executive-briefing',
     '경영 브리핑', 'Executive Briefing',
     '경영진 의사결정과 대외비 브리핑을 제한 배포합니다.',
     'Distribute executive decisions and restricted briefings to named audiences.',
     'LEADERSHIP', 'HIDDEN', 'RESTRICTED', 'COMPLIANCE_REVIEW', 'ADMIN_REVIEW',
     'DISABLED', 'landmark', 'crimson', 'ACTIVE',
     CURRENT_TIMESTAMP - INTERVAL '28 days', CURRENT_TIMESTAMP - INTERVAL '1 day', 1, 1)
ON CONFLICT (tenant_id, space_key) DO UPDATE SET
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    summary_ko = EXCLUDED.summary_ko,
    summary_en = EXCLUDED.summary_en,
    purpose_type = EXCLUDED.purpose_type,
    visibility = EXCLUDED.visibility,
    data_classification = EXCLUDED.data_classification,
    content_policy = EXCLUDED.content_policy,
    app_policy = EXCLUDED.app_policy,
    ai_policy = EXCLUDED.ai_policy,
    icon_key = EXCLUDED.icon_key,
    accent_token = EXCLUDED.accent_token,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO spc_memberships (
    membership_id, tenant_id, space_id, principal_type, principal_ref,
    member_role, membership_source, lifecycle_state, approved_by)
VALUES
    ('30000000-0000-4000-8000-000000000001', 1,
     '20000000-0000-4000-8000-000000000001', 'GROUP', 'SKAX_ALL_EMPLOYEES',
     'VIEWER', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000002', 1,
     '20000000-0000-4000-8000-000000000001', 'GROUP', 'SKAX_COMMUNICATIONS_EDITORS',
     'MODERATOR', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000003', 1,
     '20000000-0000-4000-8000-000000000002', 'GROUP', 'SKAX_ALL_EMPLOYEES',
     'VIEWER', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000004', 1,
     '20000000-0000-4000-8000-000000000002', 'GROUP', 'SKAX_APP_OWNERS',
     'OWNER', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000005', 1,
     '20000000-0000-4000-8000-000000000002', 'GROUP', 'SKAX_ERP_USERS',
     'CONTRIBUTOR', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000006', 1,
     '20000000-0000-4000-8000-000000000003', 'GROUP', 'SKAX_APP_OWNERS',
     'OWNER', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000007', 1,
     '20000000-0000-4000-8000-000000000003', 'GROUP', 'SKAX_LEGACY_OPERATIONS_USERS',
     'CONTRIBUTOR', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000008', 1,
     '20000000-0000-4000-8000-000000000004', 'GROUP', 'SKAX_ALL_EMPLOYEES',
     'CONTRIBUTOR', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000009', 1,
     '20000000-0000-4000-8000-000000000004', 'GROUP', 'SKAX_APP_OWNERS',
     'OWNER', 'GROUP', 'ACTIVE', 1),
    ('30000000-0000-4000-8000-000000000010', 1,
     '20000000-0000-4000-8000-000000000005', 'GROUP', 'SKAX_APP_OWNERS',
     'OWNER', 'GROUP', 'ACTIVE', 1)
ON CONFLICT (tenant_id, space_id, principal_type, principal_ref) DO UPDATE SET
    member_role = EXCLUDED.member_role,
    membership_source = EXCLUDED.membership_source,
    lifecycle_state = 'ACTIVE',
    approved_by = EXCLUDED.approved_by,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO spc_access_requests (
    access_request_id, tenant_id, space_id, requester_user_id,
    requester_name, requested_role, justification, decision_mode, status,
    created_at, updated_at)
VALUES
    ('39000000-0000-4000-8000-000000000001', 1,
     '20000000-0000-4000-8000-000000000003', 7, '김서연', 'VIEWER',
     '고객 장애 대응 업무의 공통 의사결정과 운영 지침을 확인하기 위해 참여가 필요합니다.',
     'OWNER_REVIEW', 'PENDING', CURRENT_TIMESTAMP - INTERVAL '3 hours', CURRENT_TIMESTAMP),
    ('39000000-0000-4000-8000-000000000002', 1,
     '20000000-0000-4000-8000-000000000004', 9, '이도윤', 'CONTRIBUTOR',
     '사내 AI 엔지니어링 가이드와 재사용 가능한 구현 사례를 공유하고 검토하기 위해 요청합니다.',
     'OWNER_REVIEW', 'PENDING', CURRENT_TIMESTAMP - INTERVAL '75 minutes', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, access_request_id) DO UPDATE SET
    requester_name = EXCLUDED.requester_name,
    requested_role = EXCLUDED.requested_role,
    justification = EXCLUDED.justification,
    decision_mode = EXCLUDED.decision_mode,
    status = CASE
        WHEN spc_access_requests.status = 'PENDING' THEN EXCLUDED.status
        ELSE spc_access_requests.status
    END,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO spc_principal_cardinalities (
    tenant_id, principal_type, principal_ref, active_principal_count, source_system)
VALUES
    (1, 'GROUP', 'SKAX_ALL_EMPLOYEES', 100, 'HRIS_PROJECTION'),
    (1, 'GROUP', 'SKAX_COMMUNICATIONS_EDITORS', 8, 'IAM_PROJECTION'),
    (1, 'GROUP', 'SKAX_APP_OWNERS', 6, 'IAM_PROJECTION'),
    (1, 'GROUP', 'SKAX_ERP_USERS', 18, 'IAM_PROJECTION'),
    (1, 'GROUP', 'SKAX_LEGACY_OPERATIONS_USERS', 12, 'IAM_PROJECTION')
ON CONFLICT (tenant_id, principal_type, principal_ref) DO UPDATE SET
    active_principal_count = EXCLUDED.active_principal_count,
    source_system = EXCLUDED.source_system,
    observed_at = CURRENT_TIMESTAMP;

INSERT INTO spc_content_items (
    content_id, tenant_id, space_id, content_type, title, summary, route,
    data_classification, lifecycle_state, author_user_id, author_name,
    current_revision, published_at)
VALUES
    ('40000000-0000-4000-8000-000000000001', 1,
     '20000000-0000-4000-8000-000000000001', 'POST',
     '8월 All Hands 핵심 내용', '사업 방향과 구성원 질의응답을 5분 안에 확인하세요.',
     '/spaces/company-square/content/40000000-0000-4000-8000-000000000001',
     'INTERNAL', 'PUBLISHED', 1, '박현우', 1, CURRENT_TIMESTAMP - INTERVAL '2 hours'),
    ('40000000-0000-4000-8000-000000000002', 1,
     '20000000-0000-4000-8000-000000000001', 'PAGE',
     '하이브리드 근무 가이드', '회의 집중 시간과 오피스 협업 규칙을 정리했습니다.',
     '/spaces/company-square/content/40000000-0000-4000-8000-000000000002',
     'INTERNAL', 'PUBLISHED', 1, '김민서', 2, CURRENT_TIMESTAMP - INTERVAL '2 days'),
    ('40000000-0000-4000-8000-000000000003', 1,
     '20000000-0000-4000-8000-000000000002', 'DECISION',
     'Agentic workflow 1차 적용 범위', '고객 탐색과 운영 브리핑을 우선 자동화 대상으로 확정했습니다.',
     '/spaces/ai-transformation/content/40000000-0000-4000-8000-000000000003',
     'CONFIDENTIAL', 'PUBLISHED', 1, '최유진', 3, CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('40000000-0000-4000-8000-000000000004', 1,
     '20000000-0000-4000-8000-000000000002', 'CANVAS',
     'AI 전환 실행 지도', '업무 가치, 데이터 준비도, 위험 통제를 한 화면에 연결합니다.',
     '/spaces/ai-transformation/content/40000000-0000-4000-8000-000000000004',
     'CONFIDENTIAL', 'PUBLISHED', 1, '김태연', 4, CURRENT_TIMESTAMP - INTERVAL '3 days'),
    ('40000000-0000-4000-8000-000000000005', 1,
     '20000000-0000-4000-8000-000000000003', 'POST',
     '검색 응답 지연 대응 현황', '원인 격리와 캐시 정책 보정이 완료되었고 24시간 관찰 중입니다.',
     '/spaces/customer-zero-operations/content/40000000-0000-4000-8000-000000000005',
     'RESTRICTED', 'PUBLISHED', 1, '박지호', 2, CURRENT_TIMESTAMP - INTERVAL '55 minutes'),
    ('40000000-0000-4000-8000-000000000006', 1,
     '20000000-0000-4000-8000-000000000004', 'PAGE',
     'React 제품 셸 성능 기준', '라우트 청크, 상호작용 지연과 접근성 검증 기준을 제안합니다.',
     '/spaces/engineering-guild/content/40000000-0000-4000-8000-000000000006',
     'INTERNAL', 'PUBLISHED', 1, '장민석', 2, CURRENT_TIMESTAMP - INTERVAL '6 hours'),
    ('40000000-0000-4000-8000-000000000007', 1,
     '20000000-0000-4000-8000-000000000004', 'POST',
     '8월 기술 교류회 발표 모집', '플랫폼·AI·데이터 분야의 15분 사례 발표를 모집합니다.',
     '/spaces/engineering-guild/content/40000000-0000-4000-8000-000000000007',
     'INTERNAL', 'PUBLISHED', 1, '최유진', 1, CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('40000000-0000-4000-8000-000000000008', 1,
     '20000000-0000-4000-8000-000000000002', 'PAGE',
     '외부 모델 사용 위험 검토', '데이터 경계와 공급자 통제 항목에 대한 검토를 요청합니다.',
     NULL, 'RESTRICTED', 'IN_REVIEW', 1, '김태연', 1, NULL)
ON CONFLICT (tenant_id, content_id) DO UPDATE SET
    title = EXCLUDED.title,
    summary = EXCLUDED.summary,
    route = EXCLUDED.route,
    data_classification = EXCLUDED.data_classification,
    lifecycle_state = EXCLUDED.lifecycle_state,
    author_name = EXCLUDED.author_name,
    current_revision = EXCLUDED.current_revision,
    published_at = EXCLUDED.published_at,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO spc_publication_reviews (
    review_id, tenant_id, space_id, content_id, requested_by,
    reviewer_strategy, status)
VALUES
    ('50000000-0000-4000-8000-000000000001', 1,
     '20000000-0000-4000-8000-000000000002',
     '40000000-0000-4000-8000-000000000008', 1, 'COMPLIANCE', 'PENDING')
ON CONFLICT (tenant_id, review_id) DO UPDATE SET
    reviewer_strategy = EXCLUDED.reviewer_strategy,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO spc_app_bindings (
    binding_id, tenant_id, space_id, app_key, display_name_ko, display_name_en,
    launch_target, icon_key, data_access_scope, lifecycle_state, created_by)
VALUES
    ('60000000-0000-4000-8000-000000000001', 1,
     '20000000-0000-4000-8000-000000000001', 'communications', 'DWP 소식', 'DWP News',
     '/communications/home', 'newspaper', 'SPACE_ONLY', 'ACTIVE', 1),
    ('60000000-0000-4000-8000-000000000002', 1,
     '20000000-0000-4000-8000-000000000001', 'calendar', '캘린더', 'Calendar',
     '/calendar/home', 'calendar-days', 'SPACE_ONLY', 'ACTIVE', 1),
    ('60000000-0000-4000-8000-000000000003', 1,
     '20000000-0000-4000-8000-000000000002', 'approvals', '전자결재', 'Approvals',
     '/approvals/home', 'file-check-2', 'EXPLICIT_RESOURCE', 'ACTIVE', 1),
    ('60000000-0000-4000-8000-000000000004', 1,
     '20000000-0000-4000-8000-000000000002', 'ask', 'DWAI·ON 워크스페이스', 'DWAI·ON Workspace',
     '/dwaion', 'sparkles', 'SPACE_ONLY', 'ACTIVE', 1),
    ('60000000-0000-4000-8000-000000000005', 1,
     '20000000-0000-4000-8000-000000000003', 'activity', '활동', 'Activity',
     '/activity', 'activity', 'SPACE_ONLY', 'ACTIVE', 1),
    ('60000000-0000-4000-8000-000000000006', 1,
     '20000000-0000-4000-8000-000000000004', 'knowledge', '지식', 'Knowledge',
     '/apps', 'book-open', 'SPACE_ONLY', 'ACTIVE', 1)
ON CONFLICT (tenant_id, space_id, app_key) DO UPDATE SET
    display_name_ko = EXCLUDED.display_name_ko,
    display_name_en = EXCLUDED.display_name_en,
    launch_target = EXCLUDED.launch_target,
    icon_key = EXCLUDED.icon_key,
    data_access_scope = EXCLUDED.data_access_scope,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO spc_activity_events (
    activity_id, tenant_id, space_id, activity_type, actor_type, actor_ref,
    actor_name, object_type, object_ref, title_ko, title_en, route, occurred_at)
VALUES
    ('70000000-0000-4000-8000-000000000001', 1,
     '20000000-0000-4000-8000-000000000001', 'CONTENT_PUBLISHED', 'USER', '1',
     '박현우', 'CONTENT', '40000000-0000-4000-8000-000000000001',
     'All Hands 핵심 내용이 게시되었습니다.', 'The All Hands brief was published.',
     '/spaces/company-square/content/40000000-0000-4000-8000-000000000001',
     CURRENT_TIMESTAMP - INTERVAL '2 hours'),
    ('70000000-0000-4000-8000-000000000002', 1,
     '20000000-0000-4000-8000-000000000002', 'DECISION_RECORDED', 'USER', '1',
     '최유진', 'CONTENT', '40000000-0000-4000-8000-000000000003',
     'Agentic workflow 적용 범위가 확정되었습니다.', 'The Agentic workflow scope was approved.',
     '/spaces/ai-transformation/content/40000000-0000-4000-8000-000000000003',
     CURRENT_TIMESTAMP - INTERVAL '1 day'),
    ('70000000-0000-4000-8000-000000000003', 1,
     '20000000-0000-4000-8000-000000000003', 'OPERATIONS_UPDATE', 'AGENT', 'dwp-agent',
     'DWP Agent', 'CONTENT', '40000000-0000-4000-8000-000000000005',
     '서비스 신호와 대응 근거가 연결되었습니다.', 'Service signals were linked to response evidence.',
     '/spaces/customer-zero-operations/content/40000000-0000-4000-8000-000000000005',
     CURRENT_TIMESTAMP - INTERVAL '55 minutes'),
    ('70000000-0000-4000-8000-000000000004', 1,
     '20000000-0000-4000-8000-000000000004', 'CONTENT_PUBLISHED', 'USER', '1',
     '장민석', 'CONTENT', '40000000-0000-4000-8000-000000000006',
     '제품 셸 성능 기준이 업데이트되었습니다.', 'Product shell performance guidance was updated.',
     '/spaces/engineering-guild/content/40000000-0000-4000-8000-000000000006',
     CURRENT_TIMESTAMP - INTERVAL '6 hours')
ON CONFLICT (activity_id) DO UPDATE SET
    title_ko = EXCLUDED.title_ko,
    title_en = EXCLUDED.title_en,
    route = EXCLUDED.route,
    occurred_at = EXCLUDED.occurred_at;

INSERT INTO spc_space_requests (
    request_id, tenant_id, template_id, requester_user_id, requester_name,
    requested_key, requested_name, requested_summary, requested_visibility,
    justification, decision_mode, risk_level, policy_evidence, status)
VALUES
    ('80000000-0000-4000-8000-000000000001', 1,
     '10000000-0000-4000-8000-000000000001', 1, '김태연',
     'semiconductor-ai-quality', '반도체 AI 품질 협업',
     '품질 탐지 모델 검증과 현장 피드백을 공동 운영합니다.', 'REQUEST',
     '품질·데이터·현장 조직이 동일한 의사결정 근거를 사용해야 합니다.',
     'POLICY', 'MEDIUM', '{"checks":["ownerGroup","classification"],"result":"REVIEW"}',
     'PENDING'),
    ('80000000-0000-4000-8000-000000000002', 1,
     '10000000-0000-4000-8000-000000000003', 1, '박지호',
     'telecom-network-incident-room', '통신망 장애 대응실',
     '중대 장애 대응과 사후 검토 근거를 제한 운영합니다.', 'PRIVATE',
     '네트워크 운영과 보안 담당자의 제한 협업 공간이 필요합니다.',
     'APPROVAL', 'HIGH', '{"checks":["restrictedData","namedOwners"],"result":"REVIEW"}',
     'PENDING')
ON CONFLICT (tenant_id, request_id) DO UPDATE SET
    requested_name = EXCLUDED.requested_name,
    requested_summary = EXCLUDED.requested_summary,
    justification = EXCLUDED.justification,
    risk_level = EXCLUDED.risk_level,
    policy_evidence = EXCLUDED.policy_evidence,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO spc_lifecycle_reviews (
    lifecycle_review_id, tenant_id, space_id, review_type, due_at,
    status, recommendation, evidence)
VALUES
    ('90000000-0000-4000-8000-000000000001', 1,
     '20000000-0000-4000-8000-000000000003', 'ACCESS',
     CURRENT_TIMESTAMP + INTERVAL '14 days', 'OPEN', 'REVIEW_ACCESS',
     '{"activePrincipals":2,"restricted":true,"lastReviewDays":76}'),
    ('90000000-0000-4000-8000-000000000002', 1,
     '20000000-0000-4000-8000-000000000005', 'ACTIVITY',
     CURRENT_TIMESTAMP - INTERVAL '3 days', 'OVERDUE', 'KEEP',
     '{"daysSinceActivity":1,"restricted":true,"ownerCount":1}')
ON CONFLICT (tenant_id, lifecycle_review_id) DO UPDATE SET
    due_at = EXCLUDED.due_at,
    status = EXCLUDED.status,
    recommendation = EXCLUDED.recommendation,
    evidence = EXCLUDED.evidence,
    updated_at = CURRENT_TIMESTAMP;
