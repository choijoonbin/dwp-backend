-- ======================================================================
-- policy_data_protection (Encryption & Retention)
-- policy_pii_field 확장 (mask_rule, hash_rule, encrypt_rule)
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) policy_data_protection
CREATE TABLE IF NOT EXISTS dwp_aura.policy_data_protection (
  protection_id              BIGSERIAL PRIMARY KEY,
  tenant_id                  BIGINT NOT NULL,
  profile_id                 BIGINT NOT NULL REFERENCES dwp_aura.config_profile(profile_id) ON DELETE CASCADE,
  at_rest_encryption_enabled BOOLEAN NOT NULL DEFAULT false,
  key_provider               VARCHAR(20) NOT NULL DEFAULT 'KMS_MOCK',
  audit_retention_years      INT NOT NULL DEFAULT 7,
  export_requires_approval   BOOLEAN NOT NULL DEFAULT true,
  export_mode                VARCHAR(20) NOT NULL DEFAULT 'ZIP',
  updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, profile_id)
);

CREATE INDEX IF NOT EXISTS ix_policy_data_protection_tenant ON dwp_aura.policy_data_protection(tenant_id);

COMMENT ON TABLE dwp_aura.policy_data_protection IS '데이터 보호 정책(암호화, 보존기간, 내보내기 제어).';
COMMENT ON COLUMN dwp_aura.policy_data_protection.key_provider IS 'KMS_MOCK | KMS | HSM';
COMMENT ON COLUMN dwp_aura.policy_data_protection.export_mode IS 'ZIP | CSV';

-- 2) policy_pii_field 확장
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='dwp_aura' AND table_name='policy_pii_field' AND column_name='mask_rule') THEN
    ALTER TABLE dwp_aura.policy_pii_field ADD COLUMN mask_rule TEXT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='dwp_aura' AND table_name='policy_pii_field' AND column_name='hash_rule') THEN
    ALTER TABLE dwp_aura.policy_pii_field ADD COLUMN hash_rule TEXT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='dwp_aura' AND table_name='policy_pii_field' AND column_name='encrypt_rule') THEN
    ALTER TABLE dwp_aura.policy_pii_field ADD COLUMN encrypt_rule TEXT;
  END IF;
END $$;

COMMENT ON COLUMN dwp_aura.policy_pii_field.mask_rule IS '마스킹 규칙(예: PARTIAL_4_4, FULL)';
COMMENT ON COLUMN dwp_aura.policy_pii_field.hash_rule IS '해시 규칙(예: SHA256)';
COMMENT ON COLUMN dwp_aura.policy_pii_field.encrypt_rule IS '암호화 규칙(예: AES256)';
