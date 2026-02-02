-- ======================================================================
-- Profile-scoped Tenant Scope: fx_control_mode, severity 추가
-- FE 요청: TENANT_SCOPE_PROFILE_SCOPED_API_FE_ADDITIONAL_REQUEST.md
-- ======================================================================

SET search_path TO dwp_aura, public;

-- policy_scope_currency: fx_control_mode (ALLOW | FX_REQUIRED | FX_LOCKED)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='dwp_aura' AND table_name='policy_scope_currency' AND column_name='fx_control_mode') THEN
    ALTER TABLE dwp_aura.policy_scope_currency ADD COLUMN fx_control_mode VARCHAR(16) NOT NULL DEFAULT 'ALLOW';
    COMMENT ON COLUMN dwp_aura.policy_scope_currency.fx_control_mode IS 'ALLOW | FX_REQUIRED | FX_LOCKED';
  END IF;
END $$;

-- policy_sod_rule: severity (INFO | WARN | BLOCK)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='dwp_aura' AND table_name='policy_sod_rule' AND column_name='severity') THEN
    ALTER TABLE dwp_aura.policy_sod_rule ADD COLUMN severity VARCHAR(16) NOT NULL DEFAULT 'WARN';
    COMMENT ON COLUMN dwp_aura.policy_sod_rule.severity IS 'INFO | WARN | BLOCK';
  END IF;
END $$;
