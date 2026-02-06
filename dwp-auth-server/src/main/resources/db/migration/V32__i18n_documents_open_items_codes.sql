-- V32: 전표 조회(Documents), 미결제 항목(Open Items) 화면용 sys_codes 추가
-- 목적: FE useCodes로 테이블·필터 라벨 다국어 지원 (Documents, Open Items, Entities 모두 포함)
-- 참고: docs/job/PROMPT_BE_I18N_SYNAPSE_LABELS_AND_CODES.md §2.4

-- ========================================
-- 1. 전표 조회 (Documents) — INTEGRITY_STATUS
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('INTEGRITY_STATUS', '무결성 상태', '전표 무결성 상태 (PASS, WARN, FAIL)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('INTEGRITY_STATUS', 'PASS', '정상', '정상', 'Pass', '무결성 검증 통과', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('INTEGRITY_STATUS', 'WARN', '경고', '경고', 'Warning', '무결성 검증 경고', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('INTEGRITY_STATUS', 'FAIL', '실패', '실패', 'Fail', '무결성 검증 실패', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. 미결제 항목 (Open Items) — OPEN_ITEM_TYPE, OPEN_ITEM_STATUS
-- ========================================
INSERT INTO sys_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
    ('OPEN_ITEM_TYPE', '미결제 유형', '미결제 항목 유형 (AR, AP)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('OPEN_ITEM_STATUS', '미결제 상태', '미결제 항목 상태 (OPEN, PARTIALLY_CLEARED, CLEARED)', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key) DO UPDATE SET
    group_name = EXCLUDED.group_name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_codes (group_key, code, name, name_ko, name_en, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('OPEN_ITEM_TYPE', 'AR', '매출채권', '매출채권', 'Accounts Receivable', '매출채권', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('OPEN_ITEM_TYPE', 'AP', '매입채무', '매입채무', 'Accounts Payable', '매입채무', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('OPEN_ITEM_STATUS', 'OPEN', '미결제', '미결제', 'Open', '미결제', 10, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('OPEN_ITEM_STATUS', 'PARTIALLY_CLEARED', '부분결제', '부분결제', 'Partially Cleared', '부분결제', 20, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('OPEN_ITEM_STATUS', 'CLEARED', '결제완료', '결제완료', 'Cleared', '결제완료', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. sys_code_usages: Documents, Open Items 메뉴 매핑
-- ========================================
INSERT INTO sys_code_usages (tenant_id, resource_key, code_group_key, scope, enabled, sort_order, remark, created_at, updated_at)
VALUES
    (1, 'menu.master-data-history.documents', 'INTEGRITY_STATUS', 'MENU', true, 10, '전표 무결성 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.master-data-history.open-items', 'OPEN_ITEM_TYPE', 'MENU', true, 10, '미결제 유형 필터 (AR/AP)', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'menu.master-data-history.open-items', 'OPEN_ITEM_STATUS', 'MENU', true, 20, '미결제 상태 필터', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, resource_key, code_group_key) DO UPDATE SET
    enabled = true,
    sort_order = EXCLUDED.sort_order,
    remark = EXCLUDED.remark,
    updated_at = CURRENT_TIMESTAMP;
