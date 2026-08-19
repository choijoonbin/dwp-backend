INSERT INTO prv_entitlement_catalog (
    entitlement_key, name, entitlement_type, description)
VALUES
    ('core.spaces', 'Enterprise Spaces', 'APP',
     'Governed collaboration spaces, templates, content review, membership, and lifecycle controls')
ON CONFLICT (entitlement_key) DO UPDATE SET
    name = EXCLUDED.name,
    entitlement_type = EXCLUDED.entitlement_type,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_service_plan_entitlements (service_plan_id, entitlement_id)
SELECT plan.service_plan_id, entitlement.entitlement_id
  FROM prv_service_plans plan
 CROSS JOIN prv_entitlement_catalog entitlement
 WHERE entitlement.entitlement_key = 'core.spaces'
   AND plan.lifecycle_state = 'ACTIVE'
ON CONFLICT (service_plan_id, entitlement_id) DO NOTHING;

INSERT INTO prv_tenant_entitlements (
    provider_tenant_id, entitlement_id, lifecycle_state, created_by, updated_by)
SELECT tenant.provider_tenant_id, entitlement.entitlement_id, 'ACTIVE', 1, 1
  FROM prv_tenants tenant
 CROSS JOIN prv_entitlement_catalog entitlement
 WHERE entitlement.entitlement_key = 'core.spaces'
   AND tenant.lifecycle_state = 'ACTIVE'
ON CONFLICT (provider_tenant_id, entitlement_id) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    version = prv_tenant_entitlements.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;
