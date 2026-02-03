-- ======================================================================
-- Agent Tool API: Simulation 결과 저장 + Integration Outbox
-- 스키마: dwp_aura
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ======================================================================
-- 1) agent_action_simulation — 시뮬레이션 전용 (propose 전 단독 호출)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.agent_action_simulation (
    simulation_id    BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT NOT NULL,
    case_id          BIGINT NOT NULL,
    action_type      VARCHAR(50) NOT NULL,
    payload_json     JSONB,
    before_json      JSONB,
    after_json       JSONB,
    validation_json  JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_actor VARCHAR(20),
    created_by_id    BIGINT
);

CREATE INDEX IF NOT EXISTS ix_agent_action_simulation_tenant_created
ON dwp_aura.agent_action_simulation(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_agent_action_simulation_case
ON dwp_aura.agent_action_simulation(tenant_id, case_id, created_at DESC);

COMMENT ON TABLE dwp_aura.agent_action_simulation IS 'Agent Tool simulate API 전용. propose/execute 전 단독 시뮬레이션 결과 저장.';

-- ======================================================================
-- 2) integration_outbox — SAP 등 외부 연동 이벤트 큐 (플러그 가능)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.integration_outbox (
    outbox_id       BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    payload_json    JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     INTEGER NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_integration_outbox_tenant_status
ON dwp_aura.integration_outbox(tenant_id, status, next_retry_at);

CREATE INDEX IF NOT EXISTS ix_integration_outbox_created
ON dwp_aura.integration_outbox(tenant_id, created_at DESC);

COMMENT ON TABLE dwp_aura.integration_outbox IS 'Integration Outbox. SAP 반영 등 외부 연동 이벤트 큐. 플러그 가능.';

-- ======================================================================
-- 3) config_kv 시드: AUTONOMY_LEVEL (Agent Tool Policy Engine)
-- ======================================================================
-- 기본값: APPROVAL_REQUIRED (HITL 필요)
INSERT INTO dwp_aura.config_kv (tenant_id, profile_id, config_key, config_value, created_at, updated_at)
SELECT tenant_id, profile_id, 'AUTONOMY_LEVEL', '"APPROVAL_REQUIRED"'::jsonb, now(), now()
FROM dwp_aura.config_profile
WHERE is_default = true
ON CONFLICT (tenant_id, profile_id, config_key) DO NOTHING;
