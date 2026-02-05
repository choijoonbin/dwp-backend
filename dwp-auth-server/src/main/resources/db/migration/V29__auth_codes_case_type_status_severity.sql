-- V29: A안(Auth 중앙 코드) — CASE_TYPE, CASE_STATUS, SEVERITY를 auth sys_codes로 통일
-- 목적: SynapseX 케이스 라벨이 Auth 코드 시스템 1곳에서 다국어 제공
-- 참고: docs/job/PROMPT_BE_I18N_CHOOSE_PLAN_A_AUTH_CODES_SOURCE_OF_TRUTH.txt

-- ========================================
-- 1. sys_code_groups 추가
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('CASE_TYPE', '케이스 유형', '케이스 분류 유형 (DUPLICATE_INVOICE, ANOMALY_AMOUNT 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', '케이스 상태', '케이스 진행 상태 (OPEN, IN_PROGRESS, RESOLVED 등)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SEVERITY', '심각도', '심각도 수준 (CRITICAL, HIGH, MEDIUM, LOW, INFO)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. sys_codes: CASE_TYPE (name_ko, name_en)
-- ========================================
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('CASE_TYPE', 'DUPLICATE_INVOICE', '중복 전표', '중복 전표', 'Duplicate invoice', '중복 송장/전표', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'ANOMALY_AMOUNT', '금액 이상', '금액 이상', 'Amount anomaly', '금액 이상 징후', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'MISSING_EVIDENCE', '근거 누락', '근거 누락', 'Missing evidence', '근거/증빙 누락', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'BANK_CHANGE', '은행 변경', '은행 변경', 'Bank change', '은행 정보 변경', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'BANK_CHANGE_RISK', '은행 변경 위험', '은행 변경 위험', 'Bank change risk', '은행 변경 위험', 45, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'POLICY_VIOLATION', '정책 위반', '정책 위반', 'Policy violation', '정책 위반', 50, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'DATA_INTEGRITY', '데이터 무결성', '데이터 무결성', 'Data integrity', '데이터 무결성 이슈', 60, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'THRESHOLD_BREACH', '임계값 초과', '임계값 초과', 'Threshold breach', '임계값 초과', 70, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'ANOMALY', '이상', '이상', 'Anomaly', '이상 징후', 80, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_TYPE', 'DEFAULT', '기타', '기타', 'Other', '기타 유형', 100, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. sys_codes: CASE_STATUS (name_ko, name_en)
-- ========================================
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('CASE_STATUS', 'OPEN', '미해결', '미해결', 'Open', '미해결', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'TRIAGED', '분류됨', '분류됨', 'Triaged', '분류 완료', 15, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'IN_REVIEW', '검토중', '검토중', 'In Review', '검토 중', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'IN_PROGRESS', '진행중', '진행중', 'In Progress', '진행 중', 25, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'PENDING_APPROVAL', '승인대기', '승인대기', 'Pending Approval', '승인 대기', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'APPROVED', '승인됨', '승인됨', 'Approved', '승인됨', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'REJECTED', '거절됨', '거절됨', 'Rejected', '거절됨', 50, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'RESOLVED', '해결됨', '해결됨', 'Resolved', '해결됨', 60, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'CLOSED', '종료', '종료', 'Closed', '종료', 70, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'ARCHIVED', '보관', '보관', 'Archived', '보관', 80, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CASE_STATUS', 'DISMISSED', '무시됨', '무시됨', 'Dismissed', '무시됨', 75, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 4. sys_codes: SEVERITY (name_ko, name_en)
-- ========================================
INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('SEVERITY', 'CRITICAL', '치명', '치명', 'Critical', '치명적', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SEVERITY', 'HIGH', '높음', '높음', 'High', '높음', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SEVERITY', 'MEDIUM', '중간', '중간', 'Medium', '중간', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SEVERITY', 'LOW', '낮음', '낮음', 'Low', '낮음', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('SEVERITY', 'INFO', '정보', '정보', 'Info', '정보', 50, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 5. sys_code_usages: menu.autonomous-operations.cases 매핑
-- ========================================
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.autonomous-operations.cases', 'CASE_TYPE', 'MENU', true, 10, '케이스 유형 필터/배지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations.cases', 'CASE_STATUS', 'MENU', true, 20, '케이스 상태 필터/배지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.autonomous-operations.cases', 'SEVERITY', 'MENU', true, 30, '심각도 필터/배지', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = true,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;
