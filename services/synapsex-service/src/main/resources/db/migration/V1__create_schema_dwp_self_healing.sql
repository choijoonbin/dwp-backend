-- ========================================
-- DWP Self-Healing Service: 스키마 생성
-- 생성일: 2026-01-29
-- 목적: 동일 PostgreSQL DB 내 dwp_self_healing 스키마 추가
-- ========================================

CREATE SCHEMA IF NOT EXISTS dwp_self_healing;

COMMENT ON SCHEMA dwp_self_healing IS 'DWP Self-Healing Service 전용 스키마.';

-- ========================================
-- 이후 마이그레이션에서 테이블/인덱스 추가
-- ========================================
