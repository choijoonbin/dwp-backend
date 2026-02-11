-- V45 (Phase 6): 조치 이력 테이블 — 승인/거절 시 누가, 왜, 어떻게 기록 (감사 추적)
-- Task: case_action_history 역할을 agent_case_action_history로 구현

SET search_path TO dwp_aura, public;

-- 1. agent_case_action_history
CREATE TABLE IF NOT EXISTS dwp_aura.agent_case_action_history (
  id            BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT NOT NULL,
  case_id       BIGINT NOT NULL REFERENCES dwp_aura.agent_case(case_id) ON DELETE CASCADE,
  action_type   VARCHAR(20) NOT NULL,
  actor_id      VARCHAR(50) NOT NULL,
  comment_text  TEXT,
  action_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  metadata_json JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_agent_case_action_history_tenant_case
  ON dwp_aura.agent_case_action_history(tenant_id, case_id, action_at DESC);
CREATE INDEX IF NOT EXISTS ix_agent_case_action_history_action_at
  ON dwp_aura.agent_case_action_history(tenant_id, action_at DESC);

COMMENT ON TABLE dwp_aura.agent_case_action_history IS 'Phase 6: 조치 이력 (승인/거절/에스컬레이션). 감사 추적용.';
COMMENT ON COLUMN dwp_aura.agent_case_action_history.action_type IS 'APPROVE, REJECT, HOLD, ESCALATE (app_codes CASE_DECISION_ACTION)';
COMMENT ON COLUMN dwp_aura.agent_case_action_history.actor_id IS '조치자 식별자 (USER:userId 또는 AGENT:agentId)';
COMMENT ON COLUMN dwp_aura.agent_case_action_history.comment_text IS '조치 사유/코멘트';
COMMENT ON COLUMN dwp_aura.agent_case_action_history.metadata_json IS '조치 당시 전표 요약(bukrs,belnr,gjahr,status_code 등)';

-- 2. app_codes: CASE_DECISION_ACTION (승인/거절 이력용 — ACTION_TYPE과 구분)
INSERT INTO dwp_aura.app_code_groups (group_key, group_name, description, is_active, created_at, updated_at)
VALUES
  ('CASE_DECISION_ACTION', 'Case Decision Action', '케이스 조치 결정 유형 (승인/거절/보류/에스컬레이션)', true, now(), now())
ON CONFLICT (group_key) DO UPDATE SET group_name = EXCLUDED.group_name, description = EXCLUDED.description, updated_at = now();

INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('CASE_DECISION_ACTION', 'APPROVE', '승인', '승인', 10, true, now(), now()),
  ('CASE_DECISION_ACTION', 'REJECT', '거절', '거절', 20, true, now(), now()),
  ('CASE_DECISION_ACTION', 'HOLD', '보류', '보류', 30, true, now(), now()),
  ('CASE_DECISION_ACTION', 'ESCALATE', '에스컬레이션', '에스컬레이션', 40, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();

-- ACTION_TYPE 그룹에 동일 코드 추가 (요구사항: com_codes ACTION_TYPE에 APPROVE, REJECT, HOLD, ESCALATE)
INSERT INTO dwp_aura.app_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  ('ACTION_TYPE', 'APPROVE', '승인', '승인', 60, true, now(), now()),
  ('ACTION_TYPE', 'REJECT', '거절', '거절', 70, true, now(), now()),
  ('ACTION_TYPE', 'HOLD', '보류', '보류', 80, true, now(), now()),
  ('ACTION_TYPE', 'ESCALATE', '에스컬레이션', '에스컬레이션', 90, true, now(), now())
ON CONFLICT (group_key, code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, updated_at = now();
