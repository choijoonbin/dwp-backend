-- ======================================================================
-- Profile-scoped Tenant Scope + Master tables (md_company_code, md_currency)
-- Tenant Scope는 profileId 기준 적용. profileId 없으면 default profile 사용.
-- ======================================================================

SET search_path TO dwp_aura, public;

-- ======================================================================
-- 1) Company Code master (SoT for display name etc.)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.md_company_code (
  tenant_id        BIGINT NOT NULL,
  bukrs            VARCHAR(4) NOT NULL,
  bukrs_name       TEXT NOT NULL,
  country          VARCHAR(3) NULL,
  default_currency  VARCHAR(5) NULL,
  is_active        BOOLEAN NOT NULL DEFAULT true,
  source_system    TEXT NOT NULL DEFAULT 'SAP',
  last_sync_ts     TIMESTAMPTZ NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, bukrs)
);

CREATE INDEX IF NOT EXISTS ix_md_company_code_tenant_active
  ON dwp_aura.md_company_code(tenant_id, is_active);
CREATE INDEX IF NOT EXISTS ix_md_company_code_tenant_name
  ON dwp_aura.md_company_code(tenant_id, bukrs_name);

COMMENT ON TABLE dwp_aura.md_company_code IS '회사코드(BUKRS) 마스터. 표시명 등 SoT.';

-- ======================================================================
-- 2) Currency master (global)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.md_currency (
  currency_code    VARCHAR(5) PRIMARY KEY,
  currency_name    TEXT NOT NULL,
  symbol           TEXT NULL,
  minor_unit       INT NULL,
  is_active        BOOLEAN NOT NULL DEFAULT true,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE dwp_aura.md_currency IS '통화 마스터(전역).';

-- ======================================================================
-- 3) Profile-scoped tenant scope (Company Codes)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.policy_scope_company (
  scope_id         BIGSERIAL PRIMARY KEY,
  tenant_id        BIGINT NOT NULL,
  profile_id       BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  bukrs            VARCHAR(4) NOT NULL,
  included         BOOLEAN NOT NULL DEFAULT true,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       BIGINT NULL,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       BIGINT NULL,
  UNIQUE (tenant_id, profile_id, bukrs)
);

CREATE INDEX IF NOT EXISTS ix_policy_scope_company_tenant_profile
  ON dwp_aura.policy_scope_company(tenant_id, profile_id);

COMMENT ON TABLE dwp_aura.policy_scope_company IS 'Profile별 회사코드(BUKRS) 스코프. included=true면 scope 내.';

-- ======================================================================
-- 4) Profile-scoped tenant scope (Currencies)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.policy_scope_currency (
  scope_id         BIGSERIAL PRIMARY KEY,
  tenant_id        BIGINT NOT NULL,
  profile_id       BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  currency_code    VARCHAR(5) NOT NULL REFERENCES dwp_aura.md_currency(currency_code),
  included         BOOLEAN NOT NULL DEFAULT true,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       BIGINT NULL,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       BIGINT NULL,
  UNIQUE (tenant_id, profile_id, currency_code)
);

CREATE INDEX IF NOT EXISTS ix_policy_scope_currency_tenant_profile
  ON dwp_aura.policy_scope_currency(tenant_id, profile_id);

COMMENT ON TABLE dwp_aura.policy_scope_currency IS 'Profile별 통화 스코프. included=true면 scope 내.';

-- ======================================================================
-- 5) SoD rules (profile-scoped)
-- ======================================================================
CREATE TABLE IF NOT EXISTS dwp_aura.policy_sod_rule (
  tenant_id        BIGINT NOT NULL,
  profile_id       BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  rule_key         TEXT NOT NULL,
  title            TEXT NOT NULL,
  description      TEXT NOT NULL DEFAULT '',
  is_enabled       BOOLEAN NOT NULL DEFAULT true,
  config_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by       BIGINT NULL,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by       BIGINT NULL,
  PRIMARY KEY (tenant_id, profile_id, rule_key)
);

CREATE INDEX IF NOT EXISTS ix_policy_sod_rule_tenant_profile
  ON dwp_aura.policy_sod_rule(tenant_id, profile_id);

COMMENT ON TABLE dwp_aura.policy_sod_rule IS 'Profile별 SoD 규칙. NO_SELF_APPROVE, DUAL_CONTROL, FINANCE_VS_SECURITY 등.';

-- ======================================================================
-- 6) policy_data_protection: kms_mode 컬럼 추가 (기존 key_provider 호환)
-- ======================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='dwp_aura' AND table_name='policy_data_protection' AND column_name='kms_mode') THEN
    ALTER TABLE dwp_aura.policy_data_protection ADD COLUMN kms_mode TEXT NOT NULL DEFAULT 'KMS_MANAGED_KEYS';
  END IF;
END $$;

-- ======================================================================
-- 7) Seed md_company_code from FI data (if available)
-- ======================================================================
INSERT INTO dwp_aura.md_company_code(tenant_id, bukrs, bukrs_name, default_currency, is_active, source_system)
SELECT tenant_id, bukrs, 'BUKRS ' || bukrs, MAX(waers), true, 'SAP'
FROM dwp_aura.fi_doc_header
WHERE bukrs IS NOT NULL AND bukrs != ''
GROUP BY tenant_id, bukrs
ON CONFLICT (tenant_id, bukrs) DO NOTHING;

-- ======================================================================
-- 8) Seed md_currency from FI data (if available)
-- ======================================================================
INSERT INTO dwp_aura.md_currency(currency_code, currency_name, is_active)
SELECT c, c, true FROM (
  SELECT DISTINCT waers AS c FROM dwp_aura.fi_doc_header WHERE waers IS NOT NULL AND waers != ''
  UNION
  SELECT DISTINCT currency AS c FROM dwp_aura.fi_open_item WHERE currency IS NOT NULL AND currency != ''
) t
WHERE c IS NOT NULL AND c != ''
ON CONFLICT (currency_code) DO NOTHING;

-- Ensure common currencies exist
INSERT INTO dwp_aura.md_currency(currency_code, currency_name, is_active)
VALUES ('KRW', 'Korean Won', true), ('USD', 'US Dollar', true), ('EUR', 'Euro', true)
ON CONFLICT (currency_code) DO NOTHING;
