-- ========================================
-- DWP Menu Table Column Type Fix V8
-- 생성일: 2026-01-19
-- 목적: sys_menus 테이블의 is_visible, is_enabled 컬럼 타입 수정 (CHAR(1) → VARCHAR(1))
-- ========================================

-- ========================================
-- 컬럼 타입 변경
-- ========================================
ALTER TABLE sys_menus
ALTER COLUMN is_visible TYPE VARCHAR(1) USING is_visible::VARCHAR(1);

ALTER TABLE sys_menus
ALTER COLUMN is_enabled TYPE VARCHAR(1) USING is_enabled::VARCHAR(1);

-- ========================================
-- 주석 업데이트
-- ========================================
COMMENT ON COLUMN sys_menus.is_visible IS '노출 여부 (Y/N, 권한과 별개로 시스템에서 숨김 가능)';
COMMENT ON COLUMN sys_menus.is_enabled IS '활성화 여부 (Y/N)';
