-- Product-to-entitlement ownership is explicit so tenant capability screens never infer
-- commercial eligibility from an application name or from a successful Auth decision.
CREATE TABLE prv_product_entitlement_bindings (
    product_key VARCHAR(80) PRIMARY KEY,
    app_resource_key VARCHAR(255) NOT NULL UNIQUE,
    entitlement_id BIGINT NOT NULL UNIQUE REFERENCES prv_entitlement_catalog(entitlement_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_product_entitlement_binding_product CHECK (
        product_key = LOWER(BTRIM(product_key))
        AND product_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_prv_product_entitlement_binding_resource CHECK (
        app_resource_key = UPPER(BTRIM(app_resource_key))
        AND app_resource_key ~ '^APP\.[A-Z][A-Z0-9_.-]{1,250}$'),
    CONSTRAINT ck_prv_product_entitlement_binding_state CHECK (
        lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

-- These entries preserve the current product availability by placing every currently shipped
-- product in every active plan. Future plan versions may narrow the set without changing the
-- tenant-safe read contract introduced here.
INSERT INTO prv_entitlement_catalog (
    entitlement_key, name, entitlement_type, description)
VALUES
    ('core.calendar', 'Calendar', 'APP', 'Calendar work and management surfaces'),
    ('core.communications', 'Communications', 'APP', 'Tenant communications surfaces'),
    ('core.hcm', 'Human resources', 'APP', 'Human resources work and management surfaces'),
    ('core.mail', 'Mail', 'APP', 'Mail work and management surfaces'),
    ('core.meetings', 'Meetings', 'APP', 'Meeting work and management surfaces'),
    ('core.messaging', 'Messaging', 'APP', 'Messaging work and management surfaces'),
    ('core.notifications', 'Notifications', 'APP', 'Notification work and management surfaces'),
    ('core.services', 'Service requests', 'APP', 'Service request work and management surfaces'),
    ('core.workplace', 'Workplace', 'APP', 'Workplace work and management surfaces')
ON CONFLICT (entitlement_key) DO NOTHING;

INSERT INTO prv_product_entitlement_bindings (
    product_key, app_resource_key, entitlement_id)
SELECT binding.product_key, binding.app_resource_key, entitlement.entitlement_id
  FROM (VALUES
        ('approvals', 'APP.APPROVALS', 'core.approvals'),
        ('calendar', 'APP.CALENDAR', 'core.calendar'),
        ('communications', 'APP.COMMUNICATIONS', 'core.communications'),
        ('dwaion', 'APP.ASK', 'ai.agent-runtime'),
        ('hcm', 'APP.HCM', 'core.hcm'),
        ('mail', 'APP.MAIL', 'core.mail'),
        ('meetings', 'APP.MEETINGS', 'core.meetings'),
        ('messaging', 'APP.MESSAGING', 'core.messaging'),
        ('notifications', 'APP.NOTIFICATIONS', 'core.notifications'),
        ('services', 'APP.EMPLOYEE_SERVICES', 'core.services'),
        ('spaces', 'APP.SPACES', 'core.spaces'),
        ('workplace', 'APP.WORKPLACE', 'core.workplace')
  ) AS binding(product_key, app_resource_key, entitlement_key)
  JOIN prv_entitlement_catalog entitlement
    ON entitlement.entitlement_key = binding.entitlement_key
ON CONFLICT (product_key) DO NOTHING;

INSERT INTO prv_service_plan_entitlements (service_plan_id, entitlement_id)
SELECT plan.service_plan_id, binding.entitlement_id
  FROM prv_service_plans plan
 CROSS JOIN prv_product_entitlement_bindings binding
 WHERE plan.lifecycle_state = 'ACTIVE'
ON CONFLICT (service_plan_id, entitlement_id) DO NOTHING;

INSERT INTO prv_tenant_entitlements (
    provider_tenant_id, entitlement_id, lifecycle_state, created_by, updated_by)
SELECT tenant.provider_tenant_id, binding.entitlement_id, 'ACTIVE', 1, 1
  FROM prv_tenants tenant
 CROSS JOIN prv_product_entitlement_bindings binding
 WHERE tenant.lifecycle_state IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED')
ON CONFLICT (provider_tenant_id, entitlement_id) DO NOTHING;

COMMENT ON TABLE prv_product_entitlement_bindings IS
    'Provider-owned product-to-entitlement contract used by tenant-safe plan eligibility reads.';
