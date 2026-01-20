-- ========================================
-- DWP 리소스 추적 코드 Seed V18
-- 생성일: 2026-01-20
-- 목적: RESOURCE_CATEGORY, RESOURCE_KIND, UI_ACTION 코드 그룹 추가
-- ========================================

-- ========================================
-- 1. 코드 그룹 추가
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('RESOURCE_CATEGORY', '리소스 카테고리', '리소스 대분류 (MENU/UI_COMPONENT)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', '리소스 종류', '리소스 세부 분류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'UI 액션', 'UI 이벤트 액션 타입 (VIEW/CLICK/SUBMIT/DOWNLOAD/SEARCH/FILTER/OPEN/CLOSE/EXECUTE/SCROLL)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. RESOURCE_CATEGORY 코드 삽입
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('RESOURCE_CATEGORY', 'MENU', '메뉴', '메뉴 리소스 카테고리', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_CATEGORY', 'UI_COMPONENT', 'UI 컴포넌트', 'UI 컴포넌트 리소스 카테고리', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. RESOURCE_KIND 코드 삽입
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('RESOURCE_KIND', 'MENU_GROUP', '메뉴 그룹', '상위 메뉴 그룹 (하위 메뉴 포함)', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'PAGE', '페이지', '라우팅되는 화면/페이지', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'BUTTON', '버튼', '버튼 컴포넌트', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'TAB', '탭', '탭 컴포넌트', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'SELECT', '콤보박스', '드롭다운/셀렉트 박스', 50, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'FILTER', '필터', '필터 토글/칩', 60, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'SEARCH', '검색', '검색 입력 필드', 70, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'TABLE_ACTION', '테이블 액션', '테이블 행 액션 (수정/삭제 등)', 80, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'DOWNLOAD', '다운로드', '파일 다운로드', 90, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'UPLOAD', '업로드', '파일 업로드', 100, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'MODAL', '모달', '팝업/모달', 110, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('RESOURCE_KIND', 'API_ACTION', 'API 액션', '화면에서 특정 API 실행 트리거', 120, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 4. UI_ACTION 코드 삽입
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, tenant_id, created_at, updated_at)
VALUES
    ('UI_ACTION', 'VIEW', '조회', '화면/페이지 조회', 10, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'CLICK', '클릭', '버튼/링크 클릭', 20, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'EXECUTE', '실행', '액션 실행', 30, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'SCROLL', '스크롤', '페이지 스크롤', 40, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'SEARCH', '검색', '검색 실행', 50, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'FILTER', '필터', '필터 적용', 60, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'DOWNLOAD', '다운로드', '파일 다운로드', 70, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'OPEN', '열기', '모달/팝업 열기', 80, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'CLOSE', '닫기', '모달/팝업 닫기', 90, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('UI_ACTION', 'SUBMIT', '제출', '폼 제출', 100, true, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 5. sys_code_usages에 UI_ACTION 매핑 추가 (menu.admin.monitoring)
-- ========================================
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.admin.monitoring', 'UI_ACTION', 'MENU', true, 10, 'Events 탭 필터용 UI 액션 코드', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    scope = EXCLUDED.scope,
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 완료
-- ========================================
-- 코드 그룹 추가:
-- - RESOURCE_CATEGORY (2개 코드)
-- - RESOURCE_KIND (12개 코드)
-- - UI_ACTION (10개 코드)
-- sys_code_usages에 menu.admin.monitoring → UI_ACTION 매핑 추가
-- ========================================
