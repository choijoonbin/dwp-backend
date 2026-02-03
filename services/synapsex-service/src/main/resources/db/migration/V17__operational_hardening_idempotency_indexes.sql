-- ======================================================================
-- SynapseX 운영형 하드닝: 멱등성 테이블 + 성능 인덱스
-- tenant_id + 주요 검색 컬럼 복합 인덱스
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) 멱등성 키 (simulate/execute 중복 실행 차단)
CREATE TABLE IF NOT EXISTS dwp_aura.idempotency_key (
  idempotency_id   BIGSERIAL PRIMARY KEY,
  tenant_id        BIGINT NOT NULL,
  resource_type    VARCHAR(50) NOT NULL,  -- ACTION_SIMULATE, ACTION_EXECUTE
  resource_id      BIGINT NOT NULL,        -- action_id
  gateway_request_id VARCHAR(100) NOT NULL,
  outcome          VARCHAR(20),           -- SUCCESS, FAILED
  result_snapshot  JSONB,                  -- 캐시된 결과 (선택)
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_idempotency_tenant_resource_request
ON dwp_aura.idempotency_key(tenant_id, resource_type, resource_id, gateway_request_id);

CREATE INDEX IF NOT EXISTS ix_idempotency_tenant_created
ON dwp_aura.idempotency_key(tenant_id, created_at DESC);

COMMENT ON TABLE dwp_aura.idempotency_key IS 'simulate/execute 멱등성. gateway_request_id 기반 중복 실행 차단.';

-- 2) 성능 인덱스 (Worklist/Document/OpenItems/Entity 검색)
CREATE INDEX IF NOT EXISTS ix_fi_doc_header_tenant_bukrs_gjahr_belnr
ON dwp_aura.fi_doc_header(tenant_id, bukrs, gjahr, belnr);

CREATE INDEX IF NOT EXISTS ix_fi_open_item_tenant_type_due
ON dwp_aura.fi_open_item(tenant_id, item_type, due_date) WHERE cleared = false;

CREATE INDEX IF NOT EXISTS ix_bp_party_tenant_type_code
ON dwp_aura.bp_party(tenant_id, party_type, party_code);

CREATE INDEX IF NOT EXISTS ix_agent_case_tenant_status_severity
ON dwp_aura.agent_case(tenant_id, status, severity, detected_at DESC);

CREATE INDEX IF NOT EXISTS ix_agent_action_tenant_status_created
ON dwp_aura.agent_action(tenant_id, status, created_at DESC);
