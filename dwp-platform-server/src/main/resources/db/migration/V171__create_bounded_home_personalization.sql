-- CORE-007 Flow Home remains rollback-safe: all Phase 2 stores are additive and
-- usr_home_preferences stays intact as the Classic compatibility source.
-- Existing composition documents are deliberately not rewritten. Application
-- readers promote supported v1/v2 documents to CLASSIC in memory, preserving
-- unknown future schemas during rolling or mixed-version deployments.

ALTER TABLE adm_home_experiences
    ALTER COLUMN composition_policy SET DEFAULT
    '{"schemaVersion":3,"experienceVariant":"CLASSIC","personalCustomizationEnabled":true,"governedZones":[{"zoneKey":"announcements","placement":"CANVAS","visible":true,"size":"compact","height":"short","sortOrder":20}]}'::jsonb;

UPDATE sys_code_values
   SET behavior_metadata = behavior_metadata
           || '{"flowBlockMode":"CONTENT","flowScrollPolicy":"DOCUMENT"}'::jsonb,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET_HEIGHT'
   AND code IN ('short', 'standard', 'tall', 'expanded');

CREATE TABLE usr_home_views (
    view_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    surface_key VARCHAR(80) NOT NULL,
    view_key VARCHAR(80) NOT NULL,
    name VARCHAR(80) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    schema_version INTEGER NOT NULL DEFAULT 5,
    layout_payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_usr_home_views_scope_key
        UNIQUE (tenant_id, user_id, surface_key, view_key),
    CONSTRAINT ck_usr_home_views_surface
        CHECK (surface_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_usr_home_views_key
        CHECK (view_key ~ '^[a-z][a-z0-9-]{0,79}$'),
    CONSTRAINT ck_usr_home_views_schema CHECK (schema_version > 0),
    CONSTRAINT ck_usr_home_views_name CHECK (length(btrim(name)) BETWEEN 1 AND 80)
);

CREATE UNIQUE INDEX uk_usr_home_views_default
    ON usr_home_views (tenant_id, user_id, surface_key)
    WHERE is_default;
CREATE INDEX idx_usr_home_views_owner
    ON usr_home_views (tenant_id, user_id, surface_key, updated_at DESC);

CREATE TABLE usr_home_view_revisions (
    revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id UUID NOT NULL REFERENCES usr_home_views(view_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    revision_number BIGINT NOT NULL,
    schema_version INTEGER NOT NULL,
    snapshot JSONB NOT NULL,
    source VARCHAR(16) NOT NULL,
    change_summary VARCHAR(240),
    command_id UUID,
    request_fingerprint VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT uk_usr_home_view_revisions_number
        UNIQUE (view_id, revision_number),
    CONSTRAINT uk_usr_home_view_revisions_command
        UNIQUE (tenant_id, user_id, command_id),
    CONSTRAINT ck_usr_home_view_revisions_source
        CHECK (source IN ('USER', 'TEMPLATE', 'AI', 'RESTORE', 'UNDO')),
    CONSTRAINT ck_usr_home_view_revisions_number CHECK (revision_number > 0)
);

CREATE INDEX idx_usr_home_view_revisions_history
    ON usr_home_view_revisions (view_id, revision_number DESC);

CREATE TABLE usr_home_view_device_layouts (
    device_layout_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id UUID NOT NULL REFERENCES usr_home_views(view_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    device_class VARCHAR(16) NOT NULL,
    overlay_payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_usr_home_view_device_layout
        UNIQUE (view_id, device_class),
    CONSTRAINT ck_usr_home_view_device_class
        CHECK (device_class IN ('DESKTOP', 'MOBILE'))
);

CREATE TABLE usr_home_widget_configurations (
    widget_configuration_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    view_id UUID NOT NULL REFERENCES usr_home_views(view_id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    widget_key VARCHAR(40) NOT NULL,
    configuration_payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_usr_home_widget_configuration
        UNIQUE (view_id, widget_key),
    CONSTRAINT ck_usr_home_widget_configuration_key
        CHECK (widget_key ~ '^[a-z][a-z0-9-]{0,39}$')
);

CREATE TABLE adm_home_templates (
    template_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    template_key VARCHAR(80) NOT NULL,
    name VARCHAR(80) NOT NULL,
    audience_payload JSONB NOT NULL DEFAULT '{"type":"ALL"}'::jsonb,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    schema_version INTEGER NOT NULL DEFAULT 5,
    layout_payload JSONB NOT NULL,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_adm_home_templates_key UNIQUE (tenant_id, template_key),
    CONSTRAINT ck_adm_home_templates_key
        CHECK (template_key ~ '^[a-z][a-z0-9-]{0,79}$'),
    CONSTRAINT ck_adm_home_templates_state
        CHECK (lifecycle_state IN ('DRAFT', 'PUBLISHED', 'REVOKED')),
    CONSTRAINT ck_adm_home_templates_publish
        CHECK ((lifecycle_state = 'PUBLISHED' AND published_at IS NOT NULL AND published_by IS NOT NULL)
            OR lifecycle_state <> 'PUBLISHED')
);

CREATE INDEX idx_adm_home_templates_runtime
    ON adm_home_templates (tenant_id, lifecycle_state, updated_at DESC);

CREATE TABLE usr_home_composer_proposals (
    proposal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    view_id UUID NOT NULL REFERENCES usr_home_views(view_id) ON DELETE CASCADE,
    state VARCHAR(24) NOT NULL,
    base_view_version BIGINT NOT NULL,
    reason_codes JSONB NOT NULL,
    changes_payload JSONB NOT NULL,
    warnings_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    before_layout JSONB NOT NULL,
    proposed_layout JSONB NOT NULL,
    creation_command_id UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    applied_revision_id UUID REFERENCES usr_home_view_revisions(revision_id),
    applied_view_version BIGINT,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_usr_home_composer_state CHECK (state IN (
        'PREVIEWED', 'CANCELLED', 'APPLIED', 'UNDONE', 'FAILED')),
    CONSTRAINT uk_usr_home_composer_command
        UNIQUE (tenant_id, user_id, creation_command_id)
);

CREATE INDEX idx_usr_home_composer_owner
    ON usr_home_composer_proposals (tenant_id, user_id, created_at DESC);

-- Compatibility backfill is non-destructive. The legacy version is retained so
-- shadow comparison can prove normalized hashes before a read-source switch.
INSERT INTO usr_home_views (
    view_id, tenant_id, user_id, surface_key, view_key, name, is_default,
    schema_version, layout_payload, version, created_at, created_by, updated_at, updated_by)
SELECT gen_random_uuid(), preference.tenant_id, preference.user_id,
       preference.surface_key, 'default', 'My home', TRUE,
       preference.schema_version, preference.layout_payload, preference.version,
       preference.created_at, preference.created_by, preference.updated_at, preference.updated_by
  FROM usr_home_preferences preference
ON CONFLICT (tenant_id, user_id, surface_key, view_key) DO NOTHING;

INSERT INTO usr_home_view_revisions (
    view_id, tenant_id, user_id, revision_number, schema_version, snapshot,
    source, change_summary, created_at, created_by)
SELECT view.view_id, view.tenant_id, view.user_id, 1, view.schema_version,
       view.layout_payload, 'USER', 'Legacy preference backfill',
       view.created_at AT TIME ZONE 'UTC', view.created_by
  FROM usr_home_views view
 WHERE view.view_key = 'default'
ON CONFLICT (view_id, revision_number) DO NOTHING;
