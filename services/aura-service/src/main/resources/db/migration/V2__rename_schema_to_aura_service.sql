-- ========================================
-- 스키마 명칭 변경: dwp_self_healing → dwp_aura (멱등)
-- 생성일: 2026-02-01
-- 목적: dwp_aura DB 내 스키마 dwp_aura 생성( V1 → RENAME )
-- 참고: dwp_aura가 이미 있으면( Flyway createSchemas 또는 이전 실행 ) RENAME 건너뜀
-- ========================================

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'dwp_self_healing')
     AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'dwp_aura') THEN
    ALTER SCHEMA dwp_self_healing RENAME TO dwp_aura;
  END IF;
END $$;

COMMENT ON SCHEMA dwp_aura IS 'DWP Aura Service 전용 스키마 (DB: dwp_aura).';

-- ========================================
-- 이후 마이그레이션에서 테이블/인덱스는 dwp_aura 스키마 사용
-- ========================================
