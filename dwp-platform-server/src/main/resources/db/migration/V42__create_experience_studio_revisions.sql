ALTER TABLE adm_tenant_branding
    ADD COLUMN accent_color VARCHAR(7) NOT NULL DEFAULT '#2457D6';

ALTER TABLE adm_tenant_branding
    ADD CONSTRAINT ck_adm_tenant_branding_accent_color
        CHECK (accent_color ~ '^#[0-9A-Fa-f]{6}$');

ALTER TABLE adm_home_experiences
    ADD COLUMN localized_content JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN default_locale VARCHAR(32) NOT NULL DEFAULT 'ko';

ALTER TABLE adm_home_experiences
    ADD CONSTRAINT ck_adm_home_experiences_localized_content
        CHECK (jsonb_typeof(localized_content) = 'object'),
    ADD CONSTRAINT ck_adm_home_experiences_default_locale
        CHECK (default_locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$');

CREATE TABLE adm_experience_revisions (
    revision_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    experience_type VARCHAR(24) NOT NULL,
    source_version BIGINT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    snapshot_payload JSONB NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    CONSTRAINT ck_adm_experience_revisions_type
        CHECK (experience_type IN ('BRANDING', 'HOME')),
    CONSTRAINT ck_adm_experience_revisions_change
        CHECK (change_type IN (
            'BASELINE',
            'SETTINGS_PUBLISHED',
            'ASSET_PUBLISHED',
            'ASSET_RESET',
            'ROLLBACK'
        )),
    CONSTRAINT ck_adm_experience_revisions_snapshot
        CHECK (jsonb_typeof(snapshot_payload) = 'object')
);

CREATE UNIQUE INDEX uk_adm_experience_revisions_baseline
    ON adm_experience_revisions (tenant_id, experience_type)
    WHERE change_type = 'BASELINE';

CREATE INDEX idx_adm_experience_revisions_history
    ON adm_experience_revisions (tenant_id, experience_type, revision_id DESC);
