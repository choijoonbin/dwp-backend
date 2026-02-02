-- ======================================================================
-- Tenant Scope tables (Company Codes, Currencies, SoD Rules)
-- dwp_aura schema. Dedicated tables (NOT config_kv).
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) tenant_company_code_scope
CREATE TABLE IF NOT EXISTS dwp_aura.tenant_company_code_scope (
  tenant_id    BIGINT NOT NULL,
  bukrs        VARCHAR(4) NOT NULL,
  is_enabled   BOOLEAN NOT NULL DEFAULT true,
  source       VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, bukrs)
);

CREATE INDEX IF NOT EXISTS ix_tenant_company_code_scope_tenant_enabled
ON dwp_aura.tenant_company_code_scope(tenant_id, is_enabled);

COMMENT ON TABLE dwp_aura.tenant_company_code_scope IS 'Tenant별 회사코드(BUKRS) 스코프. on/off 토글.';
COMMENT ON COLUMN dwp_aura.tenant_company_code_scope.source IS 'MANUAL | SAP | SEED';

-- 2) tenant_currency_scope
CREATE TABLE IF NOT EXISTS dwp_aura.tenant_currency_scope (
  tenant_id        BIGINT NOT NULL,
  waers            VARCHAR(5) NOT NULL,
  is_enabled       BOOLEAN NOT NULL DEFAULT true,
  fx_control_mode  VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, waers)
);

CREATE INDEX IF NOT EXISTS ix_tenant_currency_scope_tenant_enabled
ON dwp_aura.tenant_currency_scope(tenant_id, is_enabled);

COMMENT ON TABLE dwp_aura.tenant_currency_scope IS 'Tenant별 통화(WAERS) 스코프. on/off + FX 제어.';
COMMENT ON COLUMN dwp_aura.tenant_currency_scope.fx_control_mode IS 'ALLOW | FX_REQUIRED | FX_LOCKED';

-- 3) tenant_sod_rule
CREATE TABLE IF NOT EXISTS dwp_aura.tenant_sod_rule (
  rule_id      BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  rule_key     VARCHAR(64) NOT NULL,
  title        VARCHAR(120) NOT NULL,
  description  TEXT,
  is_enabled   BOOLEAN NOT NULL DEFAULT true,
  severity     VARCHAR(16) NOT NULL DEFAULT 'WARN',
  applies_to   JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, rule_key)
);

CREATE INDEX IF NOT EXISTS ix_tenant_sod_rule_tenant_enabled
ON dwp_aura.tenant_sod_rule(tenant_id, is_enabled);

COMMENT ON TABLE dwp_aura.tenant_sod_rule IS 'Tenant별 SoD(Segregation of Duties) 규칙.';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.severity IS 'INFO | WARN | BLOCK';
COMMENT ON COLUMN dwp_aura.tenant_sod_rule.applies_to IS '적용 대상 액션 목록 JSON array.';

-- 4) tenant_scope_seed_state (idempotent seeding)
CREATE TABLE IF NOT EXISTS dwp_aura.tenant_scope_seed_state (
  tenant_id    BIGINT PRIMARY KEY,
  seeded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  seed_version VARCHAR(16) NOT NULL DEFAULT 'v1'
);

COMMENT ON TABLE dwp_aura.tenant_scope_seed_state IS 'Tenant Scope 시드 완료 상태. 첫 GET 시 idempotent 시드용.';
