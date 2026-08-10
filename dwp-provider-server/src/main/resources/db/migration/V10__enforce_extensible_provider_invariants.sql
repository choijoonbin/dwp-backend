CREATE TABLE prv_operation_type_catalog (
    operation_type VARCHAR(40) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    default_risk_tier VARCHAR(20) NOT NULL,
    execution_strategy VARCHAR(30) NOT NULL DEFAULT 'SAGA',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    request_schema_version INTEGER NOT NULL DEFAULT 1,
    request_schema JSONB NOT NULL DEFAULT '{"type":"object"}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_operation_type_catalog_key
        CHECK (operation_type ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT ck_prv_operation_type_catalog_risk
        CHECK (default_risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_prv_operation_type_catalog_strategy
        CHECK (execution_strategy IN ('SAGA', 'SINGLE_STEP', 'EXTERNAL_WORKFLOW')),
    CONSTRAINT ck_prv_operation_type_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_operation_type_catalog_schema_version
        CHECK (request_schema_version > 0),
    CONSTRAINT ck_prv_operation_type_catalog_schema
        CHECK (jsonb_typeof(request_schema) = 'object')
);

INSERT INTO prv_operation_type_catalog (
    operation_type, display_name, default_risk_tier, execution_strategy)
VALUES
    ('TENANT_ONBOARD', 'Tenant onboarding', 'L2', 'SAGA'),
    ('TENANT_SUSPEND', 'Tenant suspension', 'L3', 'SINGLE_STEP'),
    ('TENANT_ACTIVATE', 'Tenant activation', 'L2', 'SINGLE_STEP'),
    ('ENTITLEMENT_CHANGE', 'Tenant entitlement change', 'L2', 'SAGA');

ALTER TABLE prv_operations
    DROP CONSTRAINT ck_prv_operations_type,
    ADD CONSTRAINT fk_prv_operations_type
        FOREIGN KEY (operation_type)
        REFERENCES prv_operation_type_catalog(operation_type);

CREATE TABLE prv_operator_permission_catalog (
    permission_code VARCHAR(80) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    risk_tier VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_operator_permission_catalog_key
        CHECK (permission_code ~ '^[A-Z][A-Z0-9_]{1,79}$'),
    CONSTRAINT ck_prv_operator_permission_catalog_risk
        CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_prv_operator_permission_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('ESTATE_READ', 'Read tenant estate', 'L1', 'View organizations, tenants, and service state'),
    ('TENANT_WRITE', 'Manage tenants', 'L2', 'Create and change tenant configuration'),
    ('ENTITLEMENT_WRITE', 'Manage entitlements', 'L2', 'Change tenant capability assignments'),
    ('OPERATION_EXECUTE', 'Execute operations', 'L3', 'Execute or retry provider workflows'),
    ('SUPPORT_SESSION_WRITE', 'Manage support sessions', 'L3', 'Grant and revoke time-bound support access'),
    ('AUDIT_READ', 'Read provider audit', 'L2', 'View provider control-plane audit events');

ALTER TABLE prv_operator_role_permissions
    ADD CONSTRAINT fk_prv_operator_role_permissions_catalog
        FOREIGN KEY (permission_code)
        REFERENCES prv_operator_permission_catalog(permission_code);

ALTER TABLE prv_configuration_schemas
    ADD CONSTRAINT uk_prv_configuration_schemas_scope
        UNIQUE (namespace, schema_version, scope_kind);

ALTER TABLE prv_configuration_values
    ADD COLUMN scope_kind VARCHAR(20)
        GENERATED ALWAYS AS (
            CASE
                WHEN organization_id IS NOT NULL THEN 'ORGANIZATION'
                WHEN provider_tenant_id IS NOT NULL THEN 'TENANT'
                WHEN tenant_service_instance_id IS NOT NULL THEN 'SERVICE'
                ELSE NULL
            END
        ) STORED;

ALTER TABLE prv_configuration_values
    ALTER COLUMN scope_kind SET NOT NULL,
    DROP CONSTRAINT fk_prv_configuration_values_schema,
    ADD CONSTRAINT fk_prv_configuration_values_schema_scope
        FOREIGN KEY (namespace, schema_version, scope_kind)
        REFERENCES prv_configuration_schemas(namespace, schema_version, scope_kind);

ALTER TABLE prv_organizations
    ADD CONSTRAINT ck_prv_organizations_key
        CHECK (
            organization_key = LOWER(BTRIM(organization_key))
            AND organization_key ~ '^[a-z][a-z0-9-]{1,79}$'
        );

ALTER TABLE prv_tenants
    ADD CONSTRAINT ck_prv_tenants_key
        CHECK (
            tenant_key = LOWER(BTRIM(tenant_key))
            AND tenant_key ~ '^[a-z][a-z0-9-]{1,79}$'
        );

ALTER TABLE prv_regions
    ADD CONSTRAINT ck_prv_regions_key
        CHECK (region_key = LOWER(BTRIM(region_key)) AND region_key ~ '^[a-z][a-z0-9-]{1,39}$');

ALTER TABLE prv_deployment_cells
    ADD CONSTRAINT ck_prv_deployment_cells_key
        CHECK (cell_key = LOWER(BTRIM(cell_key)) AND cell_key ~ '^[a-z][a-z0-9-]{1,79}$');

ALTER TABLE prv_service_catalog
    ADD CONSTRAINT ck_prv_service_catalog_key
        CHECK (service_key = LOWER(BTRIM(service_key)) AND service_key ~ '^[a-z][a-z0-9-]{1,79}$');

ALTER TABLE prv_entitlement_catalog
    ADD CONSTRAINT ck_prv_entitlement_catalog_key
        CHECK (
            entitlement_key = LOWER(BTRIM(entitlement_key))
            AND entitlement_key ~ '^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$'
        );

ALTER TABLE prv_service_plans
    ADD CONSTRAINT ck_prv_service_plans_key
        CHECK (plan_key = UPPER(BTRIM(plan_key)) AND plan_key ~ '^[A-Z][A-Z0-9_]{1,79}$');

ALTER TABLE prv_configuration_schemas
    ADD CONSTRAINT ck_prv_configuration_schemas_namespace
        CHECK (
            namespace = LOWER(BTRIM(namespace))
            AND namespace ~ '^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$'
        );

ALTER TABLE prv_tenant_administrators
    ADD CONSTRAINT ck_prv_tenant_administrators_email_canonical
        CHECK (email = LOWER(BTRIM(email)) AND LENGTH(email) BETWEEN 3 AND 255);

ALTER TABLE prv_organization_subscriptions
    ADD CONSTRAINT ck_prv_organization_subscriptions_contract
        CHECK (contract_reference IS NULL OR LENGTH(BTRIM(contract_reference)) > 0);

ALTER TABLE prv_support_sessions
    ADD CONSTRAINT ck_prv_support_sessions_revocation_consistency
        CHECK ((lifecycle_state = 'REVOKED') = (revoked_at IS NOT NULL));

CREATE INDEX idx_prv_organization_subscriptions_history
    ON prv_organization_subscriptions(organization_id, starts_at DESC);
CREATE INDEX idx_prv_support_sessions_operator_time
    ON prv_support_sessions(provider_operator_id, created_at DESC);
CREATE INDEX idx_prv_audit_operator_time
    ON prv_audit_events(provider_operator_id, occurred_at DESC)
    WHERE provider_operator_id IS NOT NULL;
CREATE INDEX idx_prv_service_instances_catalog_state
    ON prv_tenant_service_instances(service_key, lifecycle_state, provider_tenant_id);
