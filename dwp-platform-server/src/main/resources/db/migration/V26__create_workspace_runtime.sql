CREATE TABLE wrk_items (
    work_item_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    work_key VARCHAR(40) NOT NULL,
    title_ko VARCHAR(240) NOT NULL,
    title_en VARCHAR(240) NOT NULL,
    summary_ko VARCHAR(1000),
    summary_en VARCHAR(1000),
    work_type VARCHAR(24) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    owner_name VARCHAR(160) NOT NULL,
    assignee_user_id BIGINT,
    due_at TIMESTAMPTZ,
    source_system VARCHAR(120) NOT NULL,
    source_reference VARCHAR(240),
    source_route VARCHAR(500),
    reason_ko VARCHAR(1000),
    reason_en VARCHAR(1000),
    recommended_next_ko VARCHAR(1000),
    recommended_next_en VARCHAR(1000),
    latest_activity_ko VARCHAR(1000),
    latest_activity_en VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wrk_items_tenant_key UNIQUE (tenant_id, work_key),
    CONSTRAINT ck_wrk_items_type
        CHECK (work_type IN ('APPROVAL', 'TASK', 'SERVICE', 'REQUIRED')),
    CONSTRAINT ck_wrk_items_priority
        CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_wrk_items_state
        CHECK (lifecycle_state IN ('DUE_SOON', 'IN_PROGRESS', 'WAITING', 'COMPLETED'))
);

CREATE TABLE wrk_activity_events (
    activity_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    visible_to_user_id BIGINT,
    actor_kind VARCHAR(16) NOT NULL,
    actor_name VARCHAR(160) NOT NULL,
    event_state VARCHAR(24) NOT NULL,
    title_ko VARCHAR(240) NOT NULL,
    title_en VARCHAR(240) NOT NULL,
    summary_ko VARCHAR(1000),
    summary_en VARCHAR(1000),
    object_type VARCHAR(80) NOT NULL,
    object_label_ko VARCHAR(240) NOT NULL,
    object_label_en VARCHAR(240) NOT NULL,
    source_system VARCHAR(120) NOT NULL,
    tool_name VARCHAR(120),
    audit_reference VARCHAR(160) NOT NULL,
    progress INTEGER,
    source_route VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_wrk_activity_actor
        CHECK (actor_kind IN ('AGENT', 'PERSON', 'SYSTEM')),
    CONSTRAINT ck_wrk_activity_state
        CHECK (event_state IN ('RUNNING', 'NEEDS_INPUT', 'COMPLETED', 'POLICY_BLOCKED')),
    CONSTRAINT ck_wrk_activity_progress
        CHECK (progress IS NULL OR progress BETWEEN 0 AND 100)
);

CREATE TABLE adm_workspace_apps (
    workspace_app_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    app_key VARCHAR(80) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    description_ko VARCHAR(1000) NOT NULL,
    description_en VARCHAR(1000) NOT NULL,
    owner_name VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL,
    launch_mode VARCHAR(24) NOT NULL,
    launch_target VARCHAR(500),
    icon_key VARCHAR(80) NOT NULL,
    resource_key VARCHAR(120) NOT NULL,
    health_state VARCHAR(32) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_workspace_apps_tenant_key UNIQUE (tenant_id, app_key),
    CONSTRAINT ck_adm_workspace_apps_category
        CHECK (category IN ('PRODUCTIVITY', 'SERVICE', 'PEOPLE', 'KNOWLEDGE', 'BUSINESS', 'LEGACY')),
    CONSTRAINT ck_adm_workspace_apps_launch_mode
        CHECK (launch_mode IN ('NATIVE', 'SSO', 'DEEP_LINK')),
    CONSTRAINT ck_adm_workspace_apps_health
        CHECK (health_state IN ('HEALTHY', 'MANAGED', 'ATTENTION', 'CONFIGURATION_REQUIRED')),
    CONSTRAINT ck_adm_workspace_apps_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_adm_workspace_apps_target
        CHECK (health_state = 'CONFIGURATION_REQUIRED' OR launch_target IS NOT NULL)
);

CREATE TABLE usr_workspace_app_preferences (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    app_key VARCHAR(80) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_at TIMESTAMPTZ,
    launch_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, app_key),
    CONSTRAINT fk_usr_workspace_app_preference_app
        FOREIGN KEY (tenant_id, app_key)
        REFERENCES adm_workspace_apps (tenant_id, app_key)
        ON DELETE CASCADE,
    CONSTRAINT ck_usr_workspace_app_launch_count CHECK (launch_count >= 0)
);

CREATE INDEX idx_wrk_items_actor_queue
    ON wrk_items (tenant_id, assignee_user_id, lifecycle_state, priority, due_at);
CREATE INDEX idx_wrk_activity_actor_time
    ON wrk_activity_events (tenant_id, visible_to_user_id, occurred_at DESC);
CREATE INDEX idx_adm_workspace_apps_runtime
    ON adm_workspace_apps (tenant_id, lifecycle_state, sort_order, app_key);

INSERT INTO wrk_items (
    work_item_id, tenant_id, work_key, title_ko, title_en, summary_ko, summary_en,
    work_type, priority, lifecycle_state, owner_name, assignee_user_id, due_at,
    source_system, source_reference, source_route,
    reason_ko, reason_en, recommended_next_ko, recommended_next_en,
    latest_activity_ko, latest_activity_en, created_by, updated_by)
VALUES
    ('10420000-0000-0000-0000-000000000001', 1, 'WK-1042',
     '소프트웨어 접근 요청 승인', 'Approve software access request',
     '신규 구성원의 프로젝트 워크스페이스 접근 요청입니다.',
     'A new team member requested access to the project workspace.',
     'APPROVAL', 'HIGH', 'DUE_SOON', 'SELF', NULL, CURRENT_TIMESTAMP + INTERVAL '45 minutes',
     'IT Service', 'REQ-8812', '/admin/access',
     '승인 전까지 신규 구성원이 프로젝트 업무를 시작할 수 없습니다.',
     'A new team member cannot start project work until this request is approved.',
     '역할과 라이선스 범위를 검토한 후 승인하거나 반려하세요.',
     'Review the role and license scope, then approve or return the request.',
     '정책 엔진이 역할 적합성을 확인했습니다.',
     'The policy engine verified role eligibility.', 1, 1),
    ('10450000-0000-0000-0000-000000000002', 1, 'WK-1045',
     '고객 브리핑 노트 검토', 'Review customer briefing notes',
     '고객 미팅 전에 미해결 질문과 담당자를 확인합니다.',
     'Review unresolved questions and owners before the customer meeting.',
     'TASK', 'HIGH', 'IN_PROGRESS', 'SELF', NULL, CURRENT_TIMESTAMP + INTERVAL '70 minutes',
     'Microsoft 365', 'DOC-2048', NULL,
     '고객 미팅 전에 세 가지 질문의 담당자가 필요합니다.',
     'Three discovery questions still need owners before the customer meeting.',
     '질문을 검토하고 미팅 20분 전까지 담당자를 지정하세요.',
     'Review the questions and assign owners twenty minutes before the meeting.',
     '김민아님이 고객 질문 세 건을 추가했습니다.',
     'Mina Kim added three customer questions.', 1, 1),
    ('10430000-0000-0000-0000-000000000003', 1, 'WK-1043',
     '복리후생 선택 확인', 'Confirm benefits enrollment',
     '오늘 종료되는 복리후생 신청을 확인합니다.',
     'Confirm the benefits enrollment that closes today.',
     'SERVICE', 'MEDIUM', 'WAITING', 'SELF', NULL, CURRENT_TIMESTAMP + INTERVAL '8 hours',
     'People Service', 'BEN-2026', '/people',
     '신청 가능 기간이 오늘 종료됩니다.', 'The enrollment window closes today.',
     '선택한 제도를 확인하고 임직원 동의를 제출하세요.',
     'Confirm the selected plan and submit the employee acknowledgement.',
     '인사 시스템이 신청 마감 시간을 확인했습니다.',
     'The people connector confirmed the enrollment deadline.', 1, 1),
    ('10460000-0000-0000-0000-000000000004', 1, 'WK-1046',
     '보안 인식 교육 이수', 'Complete security awareness module',
     '필수 보안 교육과 정책 확인을 완료합니다.',
     'Complete the required security training and policy acknowledgement.',
     'REQUIRED', 'LOW', 'DUE_SOON', 'SELF', NULL, CURRENT_TIMESTAMP + INTERVAL '1 day',
     'Learning', 'LRN-771', NULL,
     '내일까지 정책 확인이 필요합니다.', 'Policy acknowledgement is required by tomorrow.',
     '15분 교육을 마치고 확인을 기록하세요.',
     'Complete the 15-minute module and record acknowledgement.',
     '학습 시스템이 오늘 아침 알림을 발송했습니다.',
     'The learning system issued a reminder this morning.', 1, 1),
    ('10380000-0000-0000-0000-000000000005', 1, 'WK-1038',
     '분기 목표 검토', 'Review quarterly objectives',
     '관리자 검토를 위해 분기 목표 진행 상황을 확인합니다.',
     'Review quarterly objective progress for the manager review.',
     'TASK', 'MEDIUM', 'IN_PROGRESS', 'SELF', NULL, CURRENT_TIMESTAMP + INTERVAL '2 days',
     'People Service', 'OBJ-Q3', '/people',
     '분기 목표가 관리자 검토 단계에 도달했습니다.',
     'Quarterly objectives are ready for manager review.',
     '진행 메모를 확인하고 검토 초안을 제출하세요.',
     'Confirm progress notes and submit the review draft.',
     '어제 목표 지표 두 개가 업데이트되었습니다.',
     'Two objective metrics were updated yesterday.', 1, 1),
    ('10270000-0000-0000-0000-000000000006', 1, 'WK-1027',
     '출장 경비 후속 처리', 'Travel expense follow-up',
     '공유 서비스에서 경비 문의 처리를 완료했습니다.',
     'Shared Services completed the expense follow-up.',
     'SERVICE', 'LOW', 'COMPLETED', 'Shared Services', NULL, CURRENT_TIMESTAMP - INTERVAL '4 days',
     'Finance', 'EXP-602', NULL,
     '처리가 완료되어 참고용으로 유지됩니다.', 'The request is complete and retained for reference.',
     '추가 조치가 필요하지 않습니다.', 'No further action is required.',
     '재무 서비스가 요청을 완료했습니다.', 'The finance service completed the request.', 1, 1)
ON CONFLICT (tenant_id, work_key) DO NOTHING;

INSERT INTO wrk_activity_events (
    activity_event_id, tenant_id, visible_to_user_id, actor_kind, actor_name,
    event_state, title_ko, title_en, summary_ko, summary_en,
    object_type, object_label_ko, object_label_en, source_system, tool_name,
    audit_reference, progress, source_route, occurred_at)
VALUES
    ('a1000000-0000-0000-0000-000000000001', 1, NULL, 'AGENT', 'DWP Agent', 'RUNNING',
     '고객 브리핑 준비 중', 'Preparing customer briefing',
     '승인된 문서와 회의 메모를 검토하고 있습니다.',
     'Reviewing approved documents and meeting notes.',
     'WORK_ITEM', '고객 브리핑', 'Customer briefing', 'Ask DWP', 'Briefing planner',
     'AUD-WRK-901', 64, '/ask', CURRENT_TIMESTAMP - INTERVAL '4 minutes'),
    ('a1000000-0000-0000-0000-000000000002', 1, NULL, 'PERSON', '김민아', 'NEEDS_INPUT',
     '접근 요청 검토 필요', 'Access request needs review',
     '신규 구성원의 프로젝트 역할 확인이 필요합니다.',
     'The new team member project role needs confirmation.',
     'WORK_ITEM', 'WK-1042 접근 요청', 'WK-1042 access request', 'IT Service', NULL,
     'AUD-WRK-902', NULL, '/work?item=WK-1042', CURRENT_TIMESTAMP - INTERVAL '18 minutes'),
    ('a1000000-0000-0000-0000-000000000003', 1, NULL, 'SYSTEM', 'Policy Engine', 'POLICY_BLOCKED',
     '외부 공유 차단', 'External sharing blocked',
     '민감 정보 정책에 따라 외부 공유가 차단되었습니다.',
     'External sharing was blocked by the sensitive information policy.',
     'POLICY_DECISION', '외부 공유 정책', 'External sharing policy', 'Policy Service', 'DLP policy',
     'AUD-WRK-903', NULL, '/activity', CURRENT_TIMESTAMP - INTERVAL '37 minutes'),
    ('a1000000-0000-0000-0000-000000000004', 1, NULL, 'SYSTEM', 'People Connector', 'COMPLETED',
     '조직 정보 동기화 완료', 'Organization sync completed',
     'SKAX 조직 및 구성원 변경 사항이 반영되었습니다.',
     'SKAX organization and workforce changes were applied.',
     'INTEGRATION_RUN', 'SKAX 인사정보 동기화', 'SKAX workforce synchronization',
     'People Service', 'HRIS connector', 'AUD-WRK-904', 100, '/people',
     CURRENT_TIMESTAMP - INTERVAL '1 hour')
ON CONFLICT (activity_event_id) DO NOTHING;

INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, created_by, updated_by)
VALUES
    (1, 'dwp-work', '업무', 'Work', '우선순위, 승인 및 할 일을 한곳에서 처리합니다.',
     'Manage priorities, approvals, and tasks in one place.', 'DWP Platform',
     'PRODUCTIVITY', 'NATIVE', '/work', 'work', 'APP.WORK', 'HEALTHY', 10, 1, 1),
    (1, 'dwp-ask', 'Ask DWP', 'Ask DWP', '근거가 있는 답변과 통제된 실행을 제공합니다.',
     'Grounded answers and governed actions.', 'DWP AI',
     'KNOWLEDGE', 'NATIVE', '/ask', 'ask', 'APP.ASK', 'MANAGED', 20, 1, 1),
    (1, 'dwp-activity', '활동', 'Activity', '사용자, 시스템 및 에이전트 활동을 추적합니다.',
     'Track human, system, and agent activity.', 'DWP Platform',
     'PRODUCTIVITY', 'NATIVE', '/activity', 'activity', 'APP.ACTIVITY', 'HEALTHY', 30, 1, 1),
    (1, 'ref-app-mail', '메일 및 일정', 'Mail & calendar', '메시지, 일정 및 회의 후속 조치를 연결합니다.',
     'Connect messages, calendars, and meeting follow-ups.', 'Workplace Platform',
     'PRODUCTIVITY', 'SSO', NULL, 'mail', 'APP.MAIL_CALENDAR', 'CONFIGURATION_REQUIRED', 40, 1, 1),
    (1, 'ref-app-collaboration', '협업', 'Collaboration', '채팅, 채널 및 회의를 연결합니다.',
     'Connect chat, channels, and meetings.', 'Workplace Platform',
     'PRODUCTIVITY', 'SSO', NULL, 'collaboration', 'APP.COLLABORATION', 'CONFIGURATION_REQUIRED', 50, 1, 1),
    (1, 'ref-app-service', '임직원 서비스', 'Employee services', '인사, IT 및 업무환경 요청을 처리합니다.',
     'Handle HR, IT, and workplace requests.', 'Shared Services',
     'SERVICE', 'DEEP_LINK', NULL, 'services', 'APP.EMPLOYEE_SERVICES', 'CONFIGURATION_REQUIRED', 60, 1, 1),
    (1, 'ref-app-people', '구성원', 'People directory', '동료와 조직의 보고 관계를 탐색합니다.',
     'Find colleagues and explore reporting relationships.', 'People Platform',
     'PEOPLE', 'NATIVE', '/people', 'people', 'APP.PEOPLE_DIRECTORY', 'HEALTHY', 70, 1, 1),
    (1, 'ref-app-knowledge', '지식', 'Knowledge', '정책, 가이드 및 검증된 답변을 찾습니다.',
     'Find policies, guides, and verified answers.', 'Knowledge Office',
     'KNOWLEDGE', 'SSO', NULL, 'knowledge', 'APP.KNOWLEDGE', 'CONFIGURATION_REQUIRED', 80, 1, 1),
    (1, 'ref-app-erp', '비즈니스 ERP', 'Business ERP', '재무 및 구매 업무를 연결합니다.',
     'Connect finance and purchasing work.', 'Finance Platform',
     'BUSINESS', 'SSO', NULL, 'erp', 'APP.BUSINESS_ERP', 'CONFIGURATION_REQUIRED', 90, 1, 1),
    (1, 'ref-app-legacy', '레거시 운영', 'Legacy operations', '기존 운영 시스템으로 안전하게 연결합니다.',
     'Provide governed access to existing operational systems.', 'Enterprise Systems',
     'LEGACY', 'DEEP_LINK', NULL, 'legacy', 'APP.LEGACY_OPERATIONS', 'CONFIGURATION_REQUIRED', 100, 1, 1)
ON CONFLICT (tenant_id, app_key) DO NOTHING;
