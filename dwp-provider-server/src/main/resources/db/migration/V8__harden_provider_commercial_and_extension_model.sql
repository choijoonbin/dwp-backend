CREATE TABLE prv_service_plans (
    service_plan_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_key VARCHAR(80) NOT NULL,
    plan_version INTEGER NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    service_tier VARCHAR(30) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    effective_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to TIMESTAMPTZ,
    commercial_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_service_plans_key_version UNIQUE (plan_key, plan_version),
    CONSTRAINT ck_prv_service_plans_version CHECK (plan_version > 0),
    CONSTRAINT ck_prv_service_plans_tier
        CHECK (service_tier IN ('STANDARD', 'ENTERPRISE', 'REGULATED')),
    CONSTRAINT ck_prv_service_plans_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_service_plans_window
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_prv_service_plans_metadata
        CHECK (jsonb_typeof(commercial_metadata) = 'object')
);

CREATE UNIQUE INDEX uk_prv_service_plans_active_key
    ON prv_service_plans(plan_key)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE prv_service_plan_entitlements (
    service_plan_id UUID NOT NULL REFERENCES prv_service_plans(service_plan_id),
    entitlement_id BIGINT NOT NULL REFERENCES prv_entitlement_catalog(entitlement_id),
    default_configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (service_plan_id, entitlement_id),
    CONSTRAINT ck_prv_service_plan_entitlements_configuration
        CHECK (jsonb_typeof(default_configuration) = 'object')
);

INSERT INTO prv_service_plans (
    plan_key, plan_version, display_name, service_tier, commercial_metadata)
VALUES
    ('DWP_STANDARD', 1, 'DWP Standard', 'STANDARD', '{"supportClass":"business-hours"}'::jsonb),
    ('DWP_ENTERPRISE', 1, 'DWP Enterprise', 'ENTERPRISE', '{"supportClass":"enterprise"}'::jsonb),
    ('DWP_REGULATED', 1, 'DWP Regulated', 'REGULATED', '{"supportClass":"regulated"}'::jsonb);

INSERT INTO prv_service_plan_entitlements (service_plan_id, entitlement_id)
SELECT plan.service_plan_id, entitlement.entitlement_id
  FROM prv_service_plans plan
  JOIN prv_entitlement_catalog entitlement
    ON entitlement.entitlement_key IN ('core.workspace', 'core.people', 'core.control-center')
 WHERE plan.plan_key = 'DWP_STANDARD';

INSERT INTO prv_service_plan_entitlements (service_plan_id, entitlement_id)
SELECT plan.service_plan_id, entitlement.entitlement_id
  FROM prv_service_plans plan
 CROSS JOIN prv_entitlement_catalog entitlement
 WHERE plan.plan_key IN ('DWP_ENTERPRISE', 'DWP_REGULATED');

CREATE TABLE prv_organization_subscriptions (
    organization_subscription_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES prv_organizations(organization_id),
    service_plan_id UUID NOT NULL REFERENCES prv_service_plans(service_plan_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    starts_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at TIMESTAMPTZ,
    contract_reference VARCHAR(160),
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_prv_organization_subscriptions_state
        CHECK (lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED', 'ENDED')),
    CONSTRAINT ck_prv_organization_subscriptions_window
        CHECK (ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_prv_organization_subscriptions_attributes
        CHECK (jsonb_typeof(attributes) = 'object')
);

CREATE UNIQUE INDEX uk_prv_organization_subscriptions_current
    ON prv_organization_subscriptions(organization_id)
    WHERE lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED');

CREATE UNIQUE INDEX uk_prv_organization_subscriptions_contract
    ON prv_organization_subscriptions(contract_reference)
    WHERE contract_reference IS NOT NULL;

INSERT INTO prv_organization_subscriptions (
    organization_id, service_plan_id, lifecycle_state, contract_reference,
    created_by, updated_by)
SELECT organization.organization_id,
       plan.service_plan_id,
       'ACTIVE',
       organization.customer_reference,
       organization.created_by,
       organization.updated_by
  FROM prv_organizations organization
  JOIN LATERAL (
      SELECT CASE
          WHEN BOOL_OR(tenant.service_tier = 'REGULATED') THEN 'REGULATED'
          WHEN BOOL_OR(tenant.service_tier = 'ENTERPRISE') THEN 'ENTERPRISE'
          ELSE 'STANDARD'
      END AS service_tier
        FROM prv_tenants tenant
       WHERE tenant.organization_id = organization.organization_id
  ) selected ON TRUE
  JOIN prv_service_plans plan
    ON plan.service_tier = selected.service_tier
   AND plan.lifecycle_state = 'ACTIVE';

CREATE TABLE prv_configuration_schemas (
    namespace VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    scope_kind VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    json_schema JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    PRIMARY KEY (namespace, schema_version),
    CONSTRAINT ck_prv_configuration_schemas_version CHECK (schema_version > 0),
    CONSTRAINT ck_prv_configuration_schemas_scope
        CHECK (scope_kind IN ('ORGANIZATION', 'TENANT', 'SERVICE')),
    CONSTRAINT ck_prv_configuration_schemas_state
        CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_configuration_schemas_document
        CHECK (jsonb_typeof(json_schema) = 'object')
);

CREATE UNIQUE INDEX uk_prv_configuration_schemas_active
    ON prv_configuration_schemas(namespace)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE prv_configuration_values (
    configuration_value_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL,
    organization_id UUID REFERENCES prv_organizations(organization_id),
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    tenant_service_instance_id UUID
        REFERENCES prv_tenant_service_instances(tenant_service_instance_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    value JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_prv_configuration_values_schema
        FOREIGN KEY (namespace, schema_version)
        REFERENCES prv_configuration_schemas(namespace, schema_version),
    CONSTRAINT ck_prv_configuration_values_single_scope
        CHECK (num_nonnulls(organization_id, provider_tenant_id, tenant_service_instance_id) = 1),
    CONSTRAINT ck_prv_configuration_values_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_configuration_values_document
        CHECK (jsonb_typeof(value) = 'object')
);

CREATE UNIQUE INDEX uk_prv_configuration_values_organization
    ON prv_configuration_values(namespace, organization_id)
    WHERE organization_id IS NOT NULL AND lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX uk_prv_configuration_values_tenant
    ON prv_configuration_values(namespace, provider_tenant_id)
    WHERE provider_tenant_id IS NOT NULL AND lifecycle_state = 'ACTIVE';
CREATE UNIQUE INDEX uk_prv_configuration_values_service
    ON prv_configuration_values(namespace, tenant_service_instance_id)
    WHERE tenant_service_instance_id IS NOT NULL AND lifecycle_state = 'ACTIVE';

INSERT INTO prv_configuration_schemas (
    namespace, schema_version, scope_kind, lifecycle_state, json_schema)
VALUES (
    'provider.tenant.extensions',
    1,
    'TENANT',
    'ACTIVE',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":true}'::jsonb
);

INSERT INTO prv_configuration_values (
    namespace, schema_version, provider_tenant_id, value, created_by, updated_by)
SELECT 'provider.tenant.extensions', 1, provider_tenant_id, configuration, created_by, updated_by
  FROM prv_tenants;

CREATE TABLE prv_tenant_administrator_roles (
    role_code VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    permission_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_prv_tenant_administrator_roles_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_tenant_administrator_roles_metadata
        CHECK (jsonb_typeof(permission_metadata) = 'object')
);

INSERT INTO prv_tenant_administrator_roles (role_code, display_name, permission_metadata)
VALUES
    ('TENANT_ADMIN', 'Tenant administrator', '{"authority":"full-tenant-administration"}'::jsonb),
    ('SECURITY_ADMIN', 'Security administrator', '{"authority":"identity-and-access"}'::jsonb),
    ('EXPERIENCE_ADMIN', 'Experience administrator', '{"authority":"branding-home-and-content"}'::jsonb);

ALTER TABLE prv_tenant_administrators
    DROP CONSTRAINT ck_prv_tenant_administrators_role,
    ADD CONSTRAINT fk_prv_tenant_administrators_role
        FOREIGN KEY (role_code) REFERENCES prv_tenant_administrator_roles(role_code);

CREATE TABLE prv_support_scope_catalog (
    scope_code VARCHAR(80) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    risk_tier VARCHAR(10) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    requires_customer_approval BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_prv_support_scope_catalog_risk CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_prv_support_scope_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO prv_support_scope_catalog (
    scope_code, display_name, risk_tier, requires_customer_approval)
VALUES
    ('TENANT_CONFIGURATION_READ', 'Read tenant configuration', 'L1', TRUE),
    ('TENANT_CONFIGURATION_WRITE', 'Change tenant configuration', 'L3', TRUE),
    ('WORKFORCE_READ', 'Read workforce data', 'L2', TRUE);

ALTER TABLE prv_support_session_scopes
    DROP CONSTRAINT ck_prv_support_session_scope,
    ADD CONSTRAINT fk_prv_support_session_scopes_catalog
        FOREIGN KEY (scope_code) REFERENCES prv_support_scope_catalog(scope_code);

ALTER TABLE prv_entitlement_catalog
    ADD COLUMN configuration_schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN configuration_schema JSONB NOT NULL DEFAULT '{"type":"object"}'::jsonb,
    ADD COLUMN default_configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_prv_entitlement_catalog_schema_version
        CHECK (configuration_schema_version > 0),
    ADD CONSTRAINT ck_prv_entitlement_catalog_configuration_schema
        CHECK (jsonb_typeof(configuration_schema) = 'object'),
    ADD CONSTRAINT ck_prv_entitlement_catalog_default_configuration
        CHECK (jsonb_typeof(default_configuration) = 'object');

ALTER TABLE prv_tenant_entitlements
    ADD CONSTRAINT ck_prv_tenant_entitlements_configuration
        CHECK (jsonb_typeof(configuration) = 'object') NOT VALID;
ALTER TABLE prv_tenant_entitlements
    VALIDATE CONSTRAINT ck_prv_tenant_entitlements_configuration;

ALTER TABLE prv_tenant_domains
    ADD COLUMN requested_primary BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE prv_tenant_domains
   SET requested_primary = TRUE,
       primary_domain = FALSE
 WHERE primary_domain = TRUE
   AND verification_state <> 'VERIFIED';

INSERT INTO prv_tenant_domains (
    provider_tenant_id, domain_name, domain_type, verification_method,
    verification_state, primary_domain, verified_at, created_by, updated_by)
SELECT tenant.provider_tenant_id,
       tenant.tenant_key || '.local',
       'LOGIN',
       'INTERNAL',
       'VERIFIED',
       FALSE,
       CURRENT_TIMESTAMP,
       tenant.created_by,
       tenant.updated_by
  FROM prv_tenants tenant
 WHERE NOT EXISTS (
       SELECT 1
         FROM prv_tenant_domains domain
        WHERE domain.provider_tenant_id = tenant.provider_tenant_id
          AND domain.verification_state = 'VERIFIED'
   )
ON CONFLICT (domain_name) DO NOTHING;

WITH candidates AS (
    SELECT DISTINCT ON (domain.provider_tenant_id)
           domain.tenant_domain_id
      FROM prv_tenant_domains domain
     WHERE domain.verification_state = 'VERIFIED'
       AND NOT EXISTS (
           SELECT 1
             FROM prv_tenant_domains primary_domain
            WHERE primary_domain.provider_tenant_id = domain.provider_tenant_id
              AND primary_domain.primary_domain = TRUE
              AND primary_domain.verification_state = 'VERIFIED'
       )
     ORDER BY domain.provider_tenant_id,
              (domain.verification_method = 'INTERNAL') DESC,
              domain.created_at
)
UPDATE prv_tenant_domains domain
   SET primary_domain = TRUE
  FROM candidates
 WHERE domain.tenant_domain_id = candidates.tenant_domain_id;

ALTER TABLE prv_tenant_domains
    ADD CONSTRAINT ck_prv_tenant_domains_primary_verified
        CHECK (NOT primary_domain OR verification_state = 'VERIFIED');

CREATE INDEX idx_prv_organization_subscriptions_plan_state
    ON prv_organization_subscriptions(service_plan_id, lifecycle_state, organization_id);
CREATE INDEX idx_prv_configuration_values_tenant_lookup
    ON prv_configuration_values(provider_tenant_id, namespace, lifecycle_state);
CREATE INDEX idx_prv_audit_events_brin_time
    ON prv_audit_events USING BRIN (occurred_at);
