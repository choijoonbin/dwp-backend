CREATE TABLE adm_tenant_branding (
    tenant_id BIGINT PRIMARY KEY,
    organization_name VARCHAR(160),
    logo_asset_key VARCHAR(320),
    logo_original_name VARCHAR(255),
    logo_content_type VARCHAR(64),
    logo_size_bytes BIGINT,
    logo_sha256 VARCHAR(64),
    logo_width INTEGER,
    logo_height INTEGER,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_adm_tenant_branding_logo_metadata
        CHECK (
            (logo_asset_key IS NULL
                AND logo_original_name IS NULL
                AND logo_content_type IS NULL
                AND logo_size_bytes IS NULL
                AND logo_sha256 IS NULL
                AND logo_width IS NULL
                AND logo_height IS NULL)
            OR
            (logo_asset_key IS NOT NULL
                AND logo_original_name IS NOT NULL
                AND logo_content_type IS NOT NULL
                AND logo_size_bytes > 0
                AND logo_sha256 IS NOT NULL
                AND logo_width > 0
                AND logo_height > 0)
        )
);

CREATE TABLE usr_home_preferences (
    home_preference_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    layout_payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_usr_home_preferences_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_usr_home_preferences_schema CHECK (schema_version > 0)
);

CREATE TABLE adm_announcements (
    announcement_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'INFO',
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    audience_type VARCHAR(16) NOT NULL DEFAULT 'ALL',
    audience_value VARCHAR(80),
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    action_label VARCHAR(80),
    action_url VARCHAR(1000),
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_adm_announcements_severity
        CHECK (severity IN ('INFO', 'SUCCESS', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_adm_announcements_state
        CHECK (lifecycle_state IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_adm_announcements_audience
        CHECK (
            (audience_type = 'ALL' AND audience_value IS NULL)
            OR
            (audience_type = 'ROLE' AND audience_value IS NOT NULL)
        ),
    CONSTRAINT ck_adm_announcements_schedule
        CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_adm_announcements_action
        CHECK (
            (action_label IS NULL AND action_url IS NULL)
            OR
            (action_label IS NOT NULL AND action_url IS NOT NULL)
        )
);

CREATE INDEX idx_adm_announcements_runtime
    ON adm_announcements (tenant_id, lifecycle_state, starts_at, ends_at, pinned);
CREATE INDEX idx_adm_announcements_admin
    ON adm_announcements (tenant_id, updated_at DESC);
