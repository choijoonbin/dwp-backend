-- V37: Audit 화면 필터용 코드 그룹 시드 (Phase A)
-- 목적: FE 하드코딩 제거. GET /api/admin/codes?groupKey=AUDIT_CATEGORY 등으로 옵션 제공
-- 참고: docs/job/PROMPT_BE_PHASEA_AUDIT_CODES_AND_POLICIES.txt

-- 1. sys_code_groups: AUDIT_* 그룹
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('AUDIT_CATEGORY', '감사 카테고리', 'audit_event_log event_category', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', '감사 이벤트 유형', 'audit_event_log event_type', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_OUTCOME', '감사 결과', 'audit_event_log outcome', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_ACTOR_TYPE', '행위자 유형', 'audit_event_log actor_type', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_SEVERITY', '감사 심각도', 'audit_event_log severity', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_RESOURCE_TYPE', '감사 리소스 유형', 'audit_event_log resource_type', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- 2. AUDIT_CATEGORY (실제 적재되는 카테고리 중심)
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('AUDIT_CATEGORY', 'CASE', '케이스', '케이스', 'Case', '케이스 관련 이벤트', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'ACTION', '조치', '조치', 'Action', '조치 관련 이벤트', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'ADMIN', '관리', '관리', 'Admin', '관리/정책 변경', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'AUDIT', '감사', '감사', 'Audit', '감사 화면 조회', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'RUN', '실행', '실행', 'Run', '배치 실행', 50, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'UI', 'UI', 'UI', 'UI', 'UI 이벤트', 60, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'DASHBOARD', '대시보드', '대시보드', 'Dashboard', '대시보드 조회', 70, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'POLICY', '정책', '정책', 'Policy', '정책 관련', 80, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'FEEDBACK', '피드백', '피드백', 'Feedback', '피드백 이벤트', 90, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_CATEGORY', 'INTEGRATION', '통합', '통합', 'Integration', '통합 이벤트', 100, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 3. AUDIT_EVENT_TYPE (실제 사용 중인 event_type)
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('AUDIT_EVENT_TYPE', 'STATUS_CHANGE', '상태 변경', '상태 변경', 'Status Change', '케이스 상태 변경', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'CASE_VIEW_LIST', '케이스 목록 조회', '케이스 목록 조회', 'Case List View', '케이스 목록 조회', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'CASE_VIEW_DETAIL', '케이스 상세 조회', '케이스 상세 조회', 'Case Detail View', '케이스 상세 조회', 25, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'CASE_ASSIGN', '케이스 할당', '케이스 할당', 'Case Assign', '케이스 할당', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'CASE_COMMENT_CREATE', '코멘트 생성', '코멘트 생성', 'Comment Create', '코멘트 생성', 35, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'DOCUMENT_VIEW_LIST', '전표 목록 조회', '전표 목록 조회', 'Document List View', '전표 목록 조회', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'DOCUMENT_VIEW_DETAIL', '전표 상세 조회', '전표 상세 조회', 'Document Detail View', '전표 상세 조회', 45, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'OPENITEM_VIEW_LIST', '미결제 목록 조회', '미결제 목록 조회', 'Open Item List View', '미결제 목록 조회', 50, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'OPENITEM_VIEW_DETAIL', '미결제 상세 조회', '미결제 상세 조회', 'Open Item Detail View', '미결제 상세 조회', 55, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'ACTION_VIEW_LIST', '조치 목록 조회', '조치 목록 조회', 'Action List View', '조치 목록 조회', 60, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'ACTION_VIEW_DETAIL', '조치 상세 조회', '조치 상세 조회', 'Action Detail View', '조치 상세 조회', 65, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'AUDIT_VIEW_LIST', '감사 목록 조회', '감사 목록 조회', 'Audit List View', '감사 화면 목록 조회', 70, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'AUDIT_VIEW_DETAIL', '감사 상세 조회', '감사 상세 조회', 'Audit Detail View', '감사 화면 상세 조회', 75, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'RUN_DETECT_STARTED', '배치 시작', '배치 시작', 'Run Started', '탐지 배치 시작', 80, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'RUN_DETECT_COMPLETED', '배치 완료', '배치 완료', 'Run Completed', '탐지 배치 완료', 85, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'RUN_DETECT_FAILED', '배치 실패', '배치 실패', 'Run Failed', '탐지 배치 실패', 90, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'RUN_DETECT_MANUAL_TRIGGERED', '수동 트리거', '수동 트리거', 'Manual Trigger', '수동 배치 트리거', 95, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'CASE_CREATED', '케이스 생성', '케이스 생성', 'Case Created', '배치 케이스 생성', 100, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'CASE_UPDATED', '케이스 갱신', '케이스 갱신', 'Case Updated', '배치 케이스 갱신', 105, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'FILTER_APPLY', '필터 적용', '필터 적용', 'Filter Apply', 'UI 필터 적용', 110, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'DASHBOARD_VIEWED', '대시보드 조회', '대시보드 조회', 'Dashboard Viewed', '대시보드 조회', 115, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'UPDATE', '수정', '수정', 'Update', '일반 수정', 120, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'BULK_UPDATE', '일괄 수정', '일괄 수정', 'Bulk Update', '일괄 수정', 125, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'PROPOSE', '제안', '제안', 'Propose', '조치 제안', 130, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'APPROVE', '승인', '승인', 'Approve', '조치 승인', 135, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'REJECT', '거절', '거절', 'Reject', '조치 거절', 140, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'EXECUTE', '실행', '실행', 'Execute', '조치 실행', 145, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_EVENT_TYPE', 'FAILED', '실패', '실패', 'Failed', '조치 실패', 150, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 4. AUDIT_OUTCOME
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('AUDIT_OUTCOME', 'SUCCESS', '성공', '성공', 'Success', '성공', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_OUTCOME', 'FAILED', '실패', '실패', 'Failed', '실패', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_OUTCOME', 'DENIED', '거부', '거부', 'Denied', '접근 거부', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_OUTCOME', 'NOOP', '무작위', '무작위', 'No-op', '변경 없음', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 5. AUDIT_ACTOR_TYPE
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('AUDIT_ACTOR_TYPE', 'HUMAN', '사용자', '사용자', 'Human', '사용자', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_ACTOR_TYPE', 'AGENT', '에이전트', '에이전트', 'Agent', 'AI 에이전트', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_ACTOR_TYPE', 'SYSTEM', '시스템', '시스템', 'System', '시스템', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 6. AUDIT_SEVERITY
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('AUDIT_SEVERITY', 'INFO', '정보', '정보', 'Info', '정보', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_SEVERITY', 'WARN', '경고', '경고', 'Warn', '경고', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_SEVERITY', 'HIGH', '높음', '높음', 'High', '높음', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_SEVERITY', 'CRITICAL', '치명', '치명', 'Critical', '치명적', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 7. AUDIT_RESOURCE_TYPE
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('AUDIT_RESOURCE_TYPE', 'AGENT_CASE', '케이스', '케이스', 'Case', 'AGENT_CASE', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_RESOURCE_TYPE', 'AGENT_ACTION', '조치', '조치', 'Action', 'AGENT_ACTION', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_RESOURCE_TYPE', 'DETECT_RUN', '탐지 실행', '탐지 실행', 'Detect Run', 'DETECT_RUN', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_RESOURCE_TYPE', 'AUDIT_EVENT', '감사 이벤트', '감사 이벤트', 'Audit Event', 'AUDIT_EVENT', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_RESOURCE_TYPE', 'ROUTE', '라우트', '라우트', 'Route', 'ROUTE', 50, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('AUDIT_RESOURCE_TYPE', 'DASHBOARD', '대시보드', '대시보드', 'Dashboard', 'DASHBOARD', 60, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- 8. sys_code_usages: 감사 화면 메뉴 매핑 (FE codes/usage 호출 시)
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.audit', 'AUDIT_CATEGORY', 'MENU', true, 10, '감사 화면 카테고리 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.audit', 'AUDIT_EVENT_TYPE', 'MENU', true, 20, '감사 화면 이벤트 유형 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.audit', 'AUDIT_OUTCOME', 'MENU', true, 30, '감사 화면 결과 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.audit', 'AUDIT_ACTOR_TYPE', 'MENU', true, 40, '감사 화면 행위자 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.audit', 'AUDIT_SEVERITY', 'MENU', true, 50, '감사 화면 심각도 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.admin.audit', 'AUDIT_RESOURCE_TYPE', 'MENU', true, 60, '감사 화면 리소스 유형 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = true,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;
