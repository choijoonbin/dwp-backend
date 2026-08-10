CREATE TABLE adm_home_experiences (
    tenant_id BIGINT PRIMARY KEY,
    headline VARCHAR(160),
    subheadline VARCHAR(500),
    background_position VARCHAR(16) NOT NULL DEFAULT 'CENTER',
    overlay_opacity INTEGER NOT NULL DEFAULT 18,
    background_asset_key VARCHAR(320),
    background_original_name VARCHAR(255),
    background_content_type VARCHAR(64),
    background_size_bytes BIGINT,
    background_sha256 VARCHAR(64),
    background_width INTEGER,
    background_height INTEGER,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_adm_home_experiences_position
        CHECK (background_position IN ('LEFT', 'CENTER', 'RIGHT')),
    CONSTRAINT ck_adm_home_experiences_overlay
        CHECK (overlay_opacity BETWEEN 0 AND 70),
    CONSTRAINT ck_adm_home_experiences_asset_metadata
        CHECK (
            (background_asset_key IS NULL
                AND background_original_name IS NULL
                AND background_content_type IS NULL
                AND background_size_bytes IS NULL
                AND background_sha256 IS NULL
                AND background_width IS NULL
                AND background_height IS NULL)
            OR
            (background_asset_key IS NOT NULL
                AND background_original_name IS NOT NULL
                AND background_content_type IS NOT NULL
                AND background_size_bytes > 0
                AND background_sha256 IS NOT NULL
                AND background_width > 0
                AND background_height > 0)
        )
);
