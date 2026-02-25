-- V71: 케이스 상태 확장 — ANALYZING, PENDING_EXPLANATION, CLOSED 및 NEW/IGNORED
-- Lifecycle: ANALYZING → NEW → IN_PROGRESS → PENDING_EXPLANATION / RESOLVED / IGNORED → CLOSED
-- Aura: 케이스 생성 시 ANALYZING, 분석 완료 후 status 콜백으로 NEW 설정

SET search_path TO dwp_aura, public;

-- agent_case_status enum에 신규 값 추가 (이미 있으면 무시)
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'NEW'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'ANALYZING'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'PENDING_EXPLANATION'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'IGNORED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ADD VALUE IF NOT EXISTS is from PG12+; for older PG use only ADD VALUE and EXCEPTION
-- NEW may not exist in enum (V3/V12 had OPEN only), so we add NEW
-- CLOSED already in V3; ANALYZING, PENDING_EXPLANATION, IGNORED added above

-- app_codes CASE_STATUS: 기존 4개(NEW, IN_PROGRESS, RESOLVED, IGNORED) + ANALYZING, PENDING_EXPLANATION, CLOSED = 7개
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('CASE_STATUS', 'ANALYZING', 'Analyzing', '분석 중', 3, true, now(), now()),
  ('CASE_STATUS', 'PENDING_EXPLANATION', 'Pending Explanation', '소명 요청', 35, true, now(), now()),
  ('CASE_STATUS', 'CLOSED', 'Closed', '종결', 80, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, sort_order = EXCLUDED.sort_order, updated_at = now();
