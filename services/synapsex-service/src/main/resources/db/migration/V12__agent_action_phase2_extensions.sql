-- Phase 2 agent_action 확장
-- 기존: action_id, tenant_id, case_id, action_type, action_payload, planned_at, executed_at, status, executed_by, error_message
-- 추가: requested_by_user_id, requested_by_actor_type, payload_json, simulation_before/after/diff_json, failure_reason, created_at, updated_at
-- status 확장: PROPOSED, PENDING_APPROVAL, APPROVED, EXECUTING, EXECUTED, FAILED, CANCELED (기존 PLANNED/SENT/SUCCESS 유지)

SET search_path TO dwp_aura, public;

-- agent_action_status enum에 Phase2 값 추가 (duplicate_object 무시)
DO $$ BEGIN ALTER TYPE dwp_aura.agent_action_status ADD VALUE 'PROPOSED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_action_status ADD VALUE 'PENDING_APPROVAL'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_action_status ADD VALUE 'APPROVED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_action_status ADD VALUE 'EXECUTING'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_action_status ADD VALUE 'EXECUTED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_action_status ADD VALUE 'CANCELED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- agent_case_status enum에 Phase2 값 추가
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'TRIAGED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'IN_PROGRESS'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'RESOLVED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN ALTER TYPE dwp_aura.agent_case_status ADD VALUE 'DISMISSED'; EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- agent_action 새 컬럼 추가
ALTER TABLE dwp_aura.agent_action
  ADD COLUMN IF NOT EXISTS requested_by_user_id BIGINT,
  ADD COLUMN IF NOT EXISTS requested_by_actor_type VARCHAR(20) DEFAULT 'USER',
  ADD COLUMN IF NOT EXISTS payload_json JSONB,
  ADD COLUMN IF NOT EXISTS simulation_before JSONB,
  ADD COLUMN IF NOT EXISTS simulation_after JSONB,
  ADD COLUMN IF NOT EXISTS diff_json JSONB,
  ADD COLUMN IF NOT EXISTS failure_reason TEXT,
  ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT now(),
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

-- executed_by NOT NULL 완화 (Phase2: PROPOSED 시점에는 미정)
ALTER TABLE dwp_aura.agent_action ALTER COLUMN executed_by DROP NOT NULL;

-- 기존 행에 created_at/updated_at 채우기
UPDATE dwp_aura.agent_action SET created_at = planned_at WHERE created_at IS NULL;
UPDATE dwp_aura.agent_action SET updated_at = COALESCE(executed_at, planned_at) WHERE updated_at IS NULL;

-- 인덱스
CREATE INDEX IF NOT EXISTS ix_agent_action_status_created
ON dwp_aura.agent_action(tenant_id, status, created_at DESC);
