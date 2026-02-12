-- Agent Studio: agent_key 추가 (Snake Case, Aura 호출 시 Key로 사용)
-- 규칙: 'finance_aura', 'hr_aura' 등 tenant 내 unique

SET search_path TO dwp_aura, public;

ALTER TABLE dwp_aura.agent_master
  ADD COLUMN IF NOT EXISTS agent_key VARCHAR(100);

-- 기존 행이 있으면 임시 값 부여 후 NOT NULL 적용
UPDATE dwp_aura.agent_master SET agent_key = 'agent_' || agent_id::text WHERE agent_key IS NULL;
ALTER TABLE dwp_aura.agent_master ALTER COLUMN agent_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_master_tenant_agent_key
  ON dwp_aura.agent_master(tenant_id, agent_key);

COMMENT ON COLUMN dwp_aura.agent_master.agent_key IS '에이전트 키 (Snake Case, Aura 호출 시 사용). 예: finance_aura, hr_aura. tenant 내 unique.';
