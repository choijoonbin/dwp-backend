CREATE TABLE sys_login_type_catalog (
    login_type VARCHAR(30) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    protocol_provider_type VARCHAR(20),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_sys_login_type_catalog_key
        CHECK (login_type = UPPER(BTRIM(login_type))
            AND login_type ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_sys_login_type_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_login_type_catalog (
    login_type, display_name, protocol_provider_type, sort_order)
VALUES
    ('LOCAL', 'Company email and password', 'LOCAL', 10),
    ('SSO', 'Enterprise single sign-on', 'OIDC', 20);

CREATE TABLE sys_identity_provider_type_catalog (
    provider_type VARCHAR(20) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    interactive BOOLEAN NOT NULL DEFAULT TRUE,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_sys_identity_provider_type_key
        CHECK (provider_type = UPPER(BTRIM(provider_type))
            AND provider_type ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    CONSTRAINT ck_sys_identity_provider_type_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO sys_identity_provider_type_catalog (
    provider_type, display_name, interactive)
VALUES
    ('LOCAL', 'Local credential', TRUE),
    ('OIDC', 'OpenID Connect', TRUE);

ALTER TABLE sys_login_type_catalog
    ADD CONSTRAINT fk_sys_login_type_protocol_provider
        FOREIGN KEY (protocol_provider_type)
        REFERENCES sys_identity_provider_type_catalog(provider_type);

CREATE TABLE com_resource_type_catalog (
    resource_type VARCHAR(30) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_com_resource_type_catalog_key
        CHECK (resource_type = UPPER(BTRIM(resource_type))
            AND resource_type ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_com_resource_type_catalog_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO com_resource_type_catalog (resource_type, display_name, sort_order)
VALUES
    ('APP', 'Application', 10),
    ('NAVIGATION', 'Navigation', 20),
    ('API', 'API', 30),
    ('ACTION', 'Action', 40),
    ('DATA', 'Data', 50),
    ('ADMIN', 'Administration', 60);

ALTER TABLE com_user_accounts
    ADD CONSTRAINT fk_com_user_accounts_provider_type
        FOREIGN KEY (provider_type)
        REFERENCES sys_identity_provider_type_catalog(provider_type);

ALTER TABLE sys_identity_providers
    ADD CONSTRAINT fk_sys_identity_providers_provider_type
        FOREIGN KEY (provider_type)
        REFERENCES sys_identity_provider_type_catalog(provider_type);

ALTER TABLE sys_login_histories
    ADD CONSTRAINT fk_sys_login_histories_provider_type
        FOREIGN KEY (provider_type)
        REFERENCES sys_identity_provider_type_catalog(provider_type);

ALTER TABLE sys_auth_policies
    ADD CONSTRAINT fk_sys_auth_policies_default_login_type
        FOREIGN KEY (default_login_type)
        REFERENCES sys_login_type_catalog(login_type);

ALTER TABLE com_resources
    ADD CONSTRAINT fk_com_resources_type_catalog
        FOREIGN KEY (type)
        REFERENCES com_resource_type_catalog(resource_type);

ALTER TABLE com_roles
    ADD CONSTRAINT ck_com_roles_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE com_role_permissions
    ADD CONSTRAINT ck_com_role_permissions_effect
        CHECK (effect IN ('ALLOW', 'DENY'));

CREATE TABLE sys_auth_policy_login_types (
    tenant_id BIGINT NOT NULL,
    login_type VARCHAR(30) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, login_type),
    CONSTRAINT fk_sys_auth_policy_login_types_policy
        FOREIGN KEY (tenant_id) REFERENCES sys_auth_policies(tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_auth_policy_login_types_catalog
        FOREIGN KEY (login_type) REFERENCES sys_login_type_catalog(login_type)
);

CREATE OR REPLACE FUNCTION sync_auth_policy_login_types()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    requested_type TEXT;
    requested_order INTEGER := 0;
BEGIN
    DELETE FROM sys_auth_policy_login_types WHERE tenant_id = NEW.tenant_id;

    FOR requested_type IN
        SELECT DISTINCT UPPER(BTRIM(value))
          FROM UNNEST(STRING_TO_ARRAY(NEW.allowed_login_types, ',')) AS value
         WHERE BTRIM(value) <> ''
    LOOP
        IF NOT EXISTS (
            SELECT 1
              FROM sys_login_type_catalog catalog
             WHERE catalog.login_type = requested_type
               AND catalog.lifecycle_state = 'ACTIVE') THEN
            RAISE EXCEPTION 'Unknown or inactive login type: %', requested_type
                USING ERRCODE = '23514';
        END IF;
        requested_order := requested_order + 10;
        INSERT INTO sys_auth_policy_login_types (tenant_id, login_type, sort_order)
        VALUES (NEW.tenant_id, requested_type, requested_order);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1
          FROM sys_auth_policy_login_types allowed
         WHERE allowed.tenant_id = NEW.tenant_id
           AND allowed.login_type = NEW.default_login_type) THEN
        RAISE EXCEPTION 'The default login type must also be allowed.'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_auth_policies_login_types
AFTER INSERT OR UPDATE OF default_login_type, allowed_login_types
ON sys_auth_policies
FOR EACH ROW
EXECUTE FUNCTION sync_auth_policy_login_types();

INSERT INTO sys_auth_policy_login_types (tenant_id, login_type, sort_order)
SELECT policy.tenant_id,
       UPPER(BTRIM(value)),
       ROW_NUMBER() OVER (
           PARTITION BY policy.tenant_id
           ORDER BY value) * 10
  FROM sys_auth_policies policy
 CROSS JOIN LATERAL UNNEST(STRING_TO_ARRAY(policy.allowed_login_types, ',')) AS value
 WHERE BTRIM(value) <> ''
ON CONFLICT (tenant_id, login_type) DO NOTHING;

CREATE INDEX idx_sys_auth_policy_login_types_runtime
    ON sys_auth_policy_login_types(tenant_id, sort_order, login_type);

COMMENT ON TABLE sys_auth_policy_login_types IS
    'Normalized runtime login policy values; the legacy CSV column is retained as a write-compatible boundary.';
