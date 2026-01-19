-- ========================================
-- DWP Monitoring Schema 수정 V4
-- 생성일: 2026-01-19
-- 목적: latency_ms 컬럼 타입을 INTEGER에서 BIGINT로 변경
-- ========================================

ALTER TABLE sys_api_call_histories ALTER COLUMN latency_ms TYPE BIGINT;

COMMENT ON COLUMN sys_api_call_histories.latency_ms IS '처리 시간 (ms, BIGINT로 변경)';
