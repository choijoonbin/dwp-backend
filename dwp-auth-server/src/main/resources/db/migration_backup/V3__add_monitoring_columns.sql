-- ========================================
-- DWP Monitoring Schema 확장 V3
-- 생성일: 2026-01-19
-- 목적: PV/UV 이외의 이벤트(클릭 등) 수집을 위한 컬럼 확장
-- ========================================

ALTER TABLE sys_page_view_events ADD COLUMN event_type VARCHAR(50);
ALTER TABLE sys_page_view_events ADD COLUMN event_name VARCHAR(100);
ALTER TABLE sys_page_view_events ADD COLUMN target_key VARCHAR(255);
ALTER TABLE sys_page_view_events ADD COLUMN metadata_json TEXT;

COMMENT ON COLUMN sys_page_view_events.event_type IS '이벤트 타입 (PAGE_VIEW, BUTTON_CLICK 등)';
COMMENT ON COLUMN sys_page_view_events.event_name IS '이벤트명 (화면명 또는 기능명)';
COMMENT ON COLUMN sys_page_view_events.target_key IS '대상 식별자 (버튼 ID 등)';
COMMENT ON COLUMN sys_page_view_events.metadata_json IS '추가 메타데이터 (JSON)';
