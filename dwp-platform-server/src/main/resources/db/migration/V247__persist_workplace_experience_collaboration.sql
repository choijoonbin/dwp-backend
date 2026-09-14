CREATE TABLE wp_experience_sharing_policies (
    tenant_id BIGINT PRIMARY KEY REFERENCES wp_tenant_policies(tenant_id),
    sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    maximum_visibility VARCHAR(16) NOT NULL DEFAULT 'SITE',
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_wp_experience_policy_visibility CHECK
        (maximum_visibility IN ('PRIVATE', 'SITE', 'FLOOR', 'RESOURCE'))
);
CREATE TABLE wp_experience_sharing_preferences (
    tenant_id BIGINT NOT NULL REFERENCES wp_tenant_policies(tenant_id),
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_wp_experience_preference_visibility CHECK
        (visibility IN ('PRIVATE', 'SITE', 'FLOOR', 'RESOURCE'))
);
CREATE TABLE wp_experience_work_plans (
    plan_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    plan_date DATE NOT NULL,
    plan_mode VARCHAR(16) NOT NULL,
    site_id UUID,
    floor_id UUID,
    resource_id UUID,
    group_ref UUID,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    expires_at DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, user_id, plan_date),
    FOREIGN KEY (tenant_id, user_id) REFERENCES wp_experience_sharing_preferences(tenant_id, user_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, floor_id) REFERENCES wp_floors(tenant_id, floor_id),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_experience_plan_mode CHECK (plan_mode IN ('OFFICE', 'REMOTE', 'OFF')),
    CONSTRAINT ck_wp_experience_plan_visibility CHECK
        (visibility IN ('PRIVATE', 'SITE', 'FLOOR', 'RESOURCE')),
    CONSTRAINT ck_wp_experience_plan_location CHECK
        ((plan_mode = 'OFFICE' AND site_id IS NOT NULL)
         OR (plan_mode <> 'OFFICE' AND site_id IS NULL AND floor_id IS NULL AND resource_id IS NULL)),
    CONSTRAINT ck_wp_experience_plan_hierarchy CHECK
        ((resource_id IS NULL OR floor_id IS NOT NULL) AND (floor_id IS NULL OR site_id IS NOT NULL)),
    CONSTRAINT ck_wp_experience_plan_expiry CHECK (expires_at > plan_date)
);
CREATE INDEX idx_wp_experience_plan_group_date
    ON wp_experience_work_plans (tenant_id, group_ref, plan_date)
    WHERE visibility <> 'PRIVATE';
CREATE TABLE wp_experience_connector_configurations (
    tenant_id BIGINT NOT NULL REFERENCES wp_tenant_policies(tenant_id),
    connector_kind VARCHAR(24) NOT NULL,
    provider VARCHAR(80),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    configuration_reference VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY (tenant_id, connector_kind),
    CONSTRAINT ck_wp_experience_connector_kind CHECK
        (connector_kind IN ('CALENDAR', 'ACTUAL_PRESENCE', 'SIGNAGE', 'VISITOR', 'VEHICLE')),
    CONSTRAINT ck_wp_experience_connector_provider CHECK
        (provider IS NULL OR provider ~ '^[A-Za-z0-9._-]{1,80}$'),
    CONSTRAINT ck_wp_experience_connector_reference CHECK
        (configuration_reference IS NULL OR configuration_reference ~ '^[A-Za-z0-9._:/-]{1,160}$')
);
CREATE TABLE wp_experience_resource_photos (
    tenant_id BIGINT NOT NULL,
    resource_id UUID NOT NULL,
    storage_key VARCHAR(320) NOT NULL,
    alt_text VARCHAR(160) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY (tenant_id, resource_id),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_experience_photo_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT ck_wp_experience_photo_tenant_key CHECK (storage_key LIKE tenant_id::TEXT || '/%')
);
COMMENT ON TABLE wp_experience_work_plans IS
    'Native planned locations, never a statement of actual employee presence. Visibility requires tenant policy, opt-in, group membership and current site VIEW.';
COMMENT ON COLUMN wp_experience_work_plans.expires_at IS
    'Expiry derived from the existing Workplace booking retention policy; no independent privacy policy owner.';
COMMENT ON TABLE wp_experience_connector_configurations IS
    'Allowlisted configuration metadata only. Configuration does not assert external connection, healthy signals or actual employee presence.';
