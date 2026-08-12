CREATE TABLE usr_saved_views (
    saved_view_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    surface_key VARCHAR(80) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    scope VARCHAR(16) NOT NULL DEFAULT 'PERSONAL',
    configuration JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_usr_saved_views_tenant_surface_id
        UNIQUE (tenant_id, surface_key, saved_view_id),
    CONSTRAINT ck_usr_saved_views_surface
        CHECK (surface_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$'),
    CONSTRAINT ck_usr_saved_views_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_usr_saved_views_scope CHECK (scope IN ('PERSONAL', 'TENANT')),
    CONSTRAINT ck_usr_saved_views_configuration
        CHECK (jsonb_typeof(configuration) = 'object')
);

CREATE UNIQUE INDEX uk_usr_saved_views_personal_name
    ON usr_saved_views (tenant_id, owner_user_id, surface_key, LOWER(name))
    WHERE scope = 'PERSONAL';

CREATE UNIQUE INDEX uk_usr_saved_views_tenant_name
    ON usr_saved_views (tenant_id, surface_key, LOWER(name))
    WHERE scope = 'TENANT';

CREATE INDEX idx_usr_saved_views_visible
    ON usr_saved_views (tenant_id, surface_key, scope, updated_at DESC);

CREATE TABLE usr_saved_view_preferences (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    surface_key VARCHAR(80) NOT NULL,
    saved_view_id UUID NOT NULL,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id, surface_key, saved_view_id),
    CONSTRAINT fk_usr_saved_view_preferences_view
        FOREIGN KEY (tenant_id, surface_key, saved_view_id)
        REFERENCES usr_saved_views (tenant_id, surface_key, saved_view_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_usr_saved_view_preferences_surface
        CHECK (surface_key ~ '^[a-z0-9][a-z0-9._-]{2,79}$')
);

CREATE UNIQUE INDEX uk_usr_saved_view_preferences_default
    ON usr_saved_view_preferences (tenant_id, user_id, surface_key)
    WHERE is_default = TRUE;

COMMENT ON TABLE usr_saved_views IS
    'Tenant-isolated, owner-controlled filter and presentation views for operational surfaces.';
COMMENT ON TABLE usr_saved_view_preferences IS
    'Per-user favorite, default, and recent-use state for an accessible saved view.';

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES (
    'PLATFORM.SAVED_VIEW.SCOPE', 'dwp-platform-server',
    'Saved view scope', 'Ownership boundary for reusable workspace views.',
    'SYSTEM', 'CHECK', 'usr_saved_views.scope', 'SECURITY');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.SAVED_VIEW.SCOPE', 'PERSONAL', 'Personal',
     '{"ko":"개인","en":"Personal"}', 10, '{"visibility":"OWNER"}'),
    ('PLATFORM.SAVED_VIEW.SCOPE', 'TENANT', 'Organization',
     '{"ko":"조직 공유","en":"Organization"}', 20, '{"visibility":"TENANT"}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES (
    'PLATFORM.SAVED_VIEW.SCOPE', 'dwp-platform-server', 'DATABASE_COLUMN',
    'usr_saved_views.scope', 'CHECK');
