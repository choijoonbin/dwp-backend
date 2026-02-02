-- ======================================================================
-- SynapseX 감사 이벤트 로그 (Audit Event Log)
-- 스키마: dwp_aura (SynapseX 서비스 전용)
-- 전제: tenant_id는 X-Tenant-ID(BIGINT) 기준, com_tenants는 dwp-auth DB 참조
-- 용도: Synapse 영역 사용자/에이전트 행위 감사 로그 (조회·시뮬레이션·Action 등)
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ======================================================================
-- synapse_audit_event_log: 감사 이벤트 로그
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.synapse_audit_event_log (
  id              BIGSERIAL PRIMARY KEY,
  tenant_id        BIGINT NOT NULL,
  event_type       TEXT NOT NULL,
  actor_type       TEXT,
  actor_id         TEXT,
  resource_type    TEXT,
  resource_id      TEXT,
  action           TEXT,
  payload          JSONB,
  ip_address       TEXT,
  user_agent       TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_synapse_audit_event_log_tenant_created
ON dwp_aura.synapse_audit_event_log(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_synapse_audit_event_log_tenant_event_type
ON dwp_aura.synapse_audit_event_log(tenant_id, event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_synapse_audit_event_log_resource
ON dwp_aura.synapse_audit_event_log(tenant_id, resource_type, resource_id);

COMMENT ON TABLE dwp_aura.synapse_audit_event_log IS 'SynapseX 감사 이벤트 로그. 데이터 조회/시뮬레이션/Action/정책 변경 등 행위 기록.';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.id IS '로그 식별자 (PK)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.tenant_id IS '테넌트 식별자 (논리적 참조: com_tenants.tenant_id)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.event_type IS '이벤트 유형 (예: DATA_VIEW, SIMULATION, ACTION, POLICY_CHANGE)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.actor_type IS '행위자 유형 (USER, AGENT, SYSTEM)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.actor_id IS '행위자 식별자 (user_id, agent_id 등)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.resource_type IS '대상 리소스 유형';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.resource_id IS '대상 리소스 식별자';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.action IS '행위 내용 요약';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.payload IS '추가 상세 (JSONB)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.ip_address IS '요청 IP (선택)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.user_agent IS 'User-Agent (선택)';
COMMENT ON COLUMN dwp_aura.synapse_audit_event_log.created_at IS '발생 일시';
