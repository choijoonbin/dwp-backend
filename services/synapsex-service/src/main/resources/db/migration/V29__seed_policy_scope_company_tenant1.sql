-- ======================================================================
-- Seed policy_scope_company for tenant_id=1 (Scope 적용 시 cases 목록 노출용)
-- ======================================================================

SET search_path TO dwp_aura, public;

INSERT INTO dwp_aura.policy_scope_company (tenant_id, profile_id, bukrs, included, created_at, created_by, updated_at, updated_by)
SELECT 1, p.profile_id, '1000', true, now(), null, now(), null
FROM dwp_aura.config_profile p
WHERE p.tenant_id = 1 AND p.is_default = true
LIMIT 1
ON CONFLICT (tenant_id, profile_id, bukrs) DO NOTHING;
