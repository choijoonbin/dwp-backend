-- Tenant/owner integrity, immutable command receipts, template history and
-- recoverable view deletion for the pre-release Phase 2 schema.

ALTER TABLE usr_home_preferences
    ADD COLUMN is_customized BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE usr_home_composer_proposals
    DROP CONSTRAINT IF EXISTS ck_usr_home_composer_expiry;

CREATE INDEX idx_usr_home_composer_expiry
    ON usr_home_composer_proposals (state, expires_at);

ALTER TABLE usr_home_views
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT;

ALTER TABLE usr_home_views
    DROP CONSTRAINT uk_usr_home_views_scope_key;
CREATE UNIQUE INDEX uk_usr_home_views_scope_key_active
    ON usr_home_views (tenant_id, user_id, surface_key, view_key)
    WHERE deleted_at IS NULL;

ALTER TABLE usr_home_views
    ADD CONSTRAINT uk_usr_home_views_owner_identity
        UNIQUE (view_id, tenant_id, user_id);

ALTER TABLE usr_home_view_revisions
    ADD CONSTRAINT uk_usr_home_view_revision_owner_identity
        UNIQUE (revision_id, view_id, tenant_id, user_id),
    ADD CONSTRAINT fk_usr_home_view_revision_owner
        FOREIGN KEY (view_id, tenant_id, user_id)
        REFERENCES usr_home_views (view_id, tenant_id, user_id) NOT VALID,
    ADD CONSTRAINT ck_usr_home_view_revision_snapshot_object
        CHECK (jsonb_typeof(snapshot) = 'object') NOT VALID;

ALTER TABLE usr_home_view_device_layouts
    ADD CONSTRAINT fk_usr_home_device_layout_owner
        FOREIGN KEY (view_id, tenant_id, user_id)
        REFERENCES usr_home_views (view_id, tenant_id, user_id) NOT VALID,
    ADD CONSTRAINT ck_usr_home_device_overlay_object
        CHECK (jsonb_typeof(overlay_payload) = 'object');

ALTER TABLE usr_home_widget_configurations
    ADD CONSTRAINT fk_usr_home_widget_configuration_owner
        FOREIGN KEY (view_id, tenant_id, user_id)
        REFERENCES usr_home_views (view_id, tenant_id, user_id) NOT VALID,
    ADD CONSTRAINT ck_usr_home_widget_configuration_object
        CHECK (jsonb_typeof(configuration_payload) = 'object');

ALTER TABLE usr_home_composer_proposals
    ADD CONSTRAINT fk_usr_home_composer_view_owner
        FOREIGN KEY (view_id, tenant_id, user_id)
        REFERENCES usr_home_views (view_id, tenant_id, user_id) NOT VALID,
    ADD CONSTRAINT fk_usr_home_composer_applied_revision_owner
        FOREIGN KEY (applied_revision_id, view_id, tenant_id, user_id)
        REFERENCES usr_home_view_revisions (revision_id, view_id, tenant_id, user_id)
        NOT VALID,
    ADD CONSTRAINT fk_usr_home_composer_undone_revision_owner
        FOREIGN KEY (undone_revision_id, view_id, tenant_id, user_id)
        REFERENCES usr_home_view_revisions (revision_id, view_id, tenant_id, user_id)
        NOT VALID,
    ADD CONSTRAINT ck_usr_home_composer_reason_array
        CHECK (jsonb_typeof(reason_codes) = 'array'),
    ADD CONSTRAINT ck_usr_home_composer_changes_array
        CHECK (jsonb_typeof(changes_payload) = 'array'),
    ADD CONSTRAINT ck_usr_home_composer_warnings_array
        CHECK (jsonb_typeof(warnings_payload) = 'array'),
    ADD CONSTRAINT ck_usr_home_composer_before_object
        CHECK (jsonb_typeof(before_layout) = 'object'),
    ADD CONSTRAINT ck_usr_home_composer_proposed_object
        CHECK (jsonb_typeof(proposed_layout) = 'object');

ALTER TABLE usr_home_views
    ADD CONSTRAINT ck_usr_home_view_layout_object
        CHECK (jsonb_typeof(layout_payload) = 'object') NOT VALID;

ALTER TABLE adm_home_templates
    ADD CONSTRAINT uk_adm_home_template_owner_identity
        UNIQUE (template_id, tenant_id),
    ADD CONSTRAINT ck_adm_home_template_audience_object
        CHECK (jsonb_typeof(audience_payload) = 'object'),
    ADD CONSTRAINT ck_adm_home_template_layout_object
        CHECK (jsonb_typeof(layout_payload) = 'object');

CREATE TABLE adm_home_template_revisions (
    template_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    revision_number BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    source VARCHAR(16) NOT NULL,
    command_id UUID,
    request_fingerprint VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    CONSTRAINT fk_adm_home_template_revision_owner
        FOREIGN KEY (template_id, tenant_id)
        REFERENCES adm_home_templates (template_id, tenant_id),
    CONSTRAINT uk_adm_home_template_revision_number
        UNIQUE (template_id, revision_number),
    CONSTRAINT ck_adm_home_template_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_adm_home_template_revision_snapshot
        CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT ck_adm_home_template_revision_source
        CHECK (source IN ('CREATE', 'UPDATE', 'PUBLISH', 'REVOKE'))
);

CREATE INDEX idx_adm_home_template_revision_history
    ON adm_home_template_revisions (template_id, revision_number DESC);

CREATE TABLE usr_home_command_receipts (
    receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    operation VARCHAR(48) NOT NULL,
    target_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_type VARCHAR(48) NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_usr_home_command_receipt
        UNIQUE (tenant_id, actor_id, command_id),
    CONSTRAINT ck_usr_home_command_receipt_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_usr_home_command_receipt_response
        CHECK (jsonb_typeof(response_payload) = 'object'),
    CONSTRAINT ck_usr_home_command_receipt_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_usr_home_command_receipt_expiry
    ON usr_home_command_receipts (expires_at);
