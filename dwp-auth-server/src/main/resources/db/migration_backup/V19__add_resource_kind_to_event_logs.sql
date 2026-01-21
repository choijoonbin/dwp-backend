-- ========================================
-- DWP sys_event_logs 확장 (resource_kind 추가) V19
-- 생성일: 2026-01-20
-- 목적: 이벤트 로그에 resource_kind 추가하여 추적성 강화
-- ========================================

-- ========================================
-- 1. sys_event_logs에 resource_kind 컬럼 추가
-- ========================================
ALTER TABLE sys_event_logs
    ADD COLUMN IF NOT EXISTS resource_kind VARCHAR(50);

-- ========================================
-- 2. COMMENT 추가
-- ========================================
COMMENT ON COLUMN sys_event_logs.resource_kind IS '리소스 종류 (MENU_GROUP/PAGE/BUTTON/TAB/SELECT/FILTER/SEARCH/TABLE_ACTION/DOWNLOAD/UPLOAD/MODAL/API_ACTION) - com_resources.resource_kind와 동기화';

-- ========================================
-- 3. 인덱스 추가
-- ========================================
CREATE INDEX IF NOT EXISTS idx_sys_event_logs_resource_kind ON sys_event_logs(resource_kind);
CREATE INDEX IF NOT EXISTS idx_sys_event_logs_resource_key_kind ON sys_event_logs(resource_key, resource_kind);

-- ========================================
-- 완료
-- ========================================
-- sys_event_logs 테이블에 resource_kind 컬럼 추가됨
-- 향후 이벤트 수집 시 com_resources에서 resource_kind를 조회하여 저장
-- ========================================
