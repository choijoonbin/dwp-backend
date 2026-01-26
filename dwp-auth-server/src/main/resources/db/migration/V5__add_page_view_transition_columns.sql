-- ========================================
-- V5: 페이지 이동 전/후 정보 저장을 위한 컬럼 추가
-- 생성일: 2026-01-23
-- 대상: sys_page_view_events
-- ========================================

ALTER TABLE sys_page_view_events
    ADD COLUMN path VARCHAR(500),
    ADD COLUMN from_page_key VARCHAR(255),
    ADD COLUMN from_path VARCHAR(500);

COMMENT ON COLUMN sys_page_view_events.path IS '현재 페이지 경로 (to_path)';
COMMENT ON COLUMN sys_page_view_events.from_page_key IS '이전 페이지 키 (from_page_key)';
COMMENT ON COLUMN sys_page_view_events.from_path IS '이전 페이지 경로 (from_path)';
