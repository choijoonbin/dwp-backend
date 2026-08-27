CREATE TABLE msg_tenant_appearance_policies (
    tenant_id BIGINT PRIMARY KEY,
    allowed_theme_keys JSONB NOT NULL DEFAULT '["DEFAULT", "MIST", "SAGE", "ROSE"]'::jsonb,
    allow_personal_backgrounds BOOLEAN NOT NULL DEFAULT FALSE,
    allow_theme_sharing BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_msg_appearance_theme_catalog CHECK (
        jsonb_typeof(allowed_theme_keys) = 'array'
        AND jsonb_array_length(allowed_theme_keys) BETWEEN 1 AND 12)
);

INSERT INTO msg_tenant_appearance_policies (tenant_id)
SELECT tenant_id
  FROM msg_tenant_policies
ON CONFLICT (tenant_id) DO NOTHING;

CREATE TABLE msg_user_display_preferences (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    layout_mode VARCHAR(24) NOT NULL DEFAULT 'AUTO',
    density VARCHAR(24) NOT NULL DEFAULT 'COMFORTABLE',
    theme_key VARCHAR(24) NOT NULL DEFAULT 'DEFAULT',
    show_avatars BOOLEAN NOT NULL DEFAULT TRUE,
    timestamp_mode VARCHAR(24) NOT NULL DEFAULT 'SMART',
    message_preview BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_msg_user_display_layout CHECK (
        layout_mode IN ('AUTO', 'CONVERSATIONAL', 'COLLABORATIVE')),
    CONSTRAINT ck_msg_user_display_density CHECK (
        density IN ('COMFORTABLE', 'COMPACT')),
    CONSTRAINT ck_msg_user_display_theme CHECK (
        theme_key IN ('DEFAULT', 'MIST', 'SAGE', 'ROSE')),
    CONSTRAINT ck_msg_user_display_timestamp CHECK (
        timestamp_mode IN ('SMART', 'ALWAYS')),
    CONSTRAINT ck_msg_user_display_version CHECK (version > 0)
);

CREATE TABLE msg_user_conversation_display_preferences (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    layout_mode VARCHAR(24) NOT NULL DEFAULT 'INHERIT',
    density VARCHAR(24) NOT NULL DEFAULT 'INHERIT',
    theme_key VARCHAR(24) NOT NULL DEFAULT 'INHERIT',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    PRIMARY KEY (tenant_id, user_id, conversation_id),
    CONSTRAINT fk_msg_conversation_display_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES msg_conversations (tenant_id, conversation_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_msg_conversation_display_layout CHECK (
        layout_mode IN ('INHERIT', 'AUTO', 'CONVERSATIONAL', 'COLLABORATIVE')),
    CONSTRAINT ck_msg_conversation_display_density CHECK (
        density IN ('INHERIT', 'COMFORTABLE', 'COMPACT')),
    CONSTRAINT ck_msg_conversation_display_theme CHECK (
        theme_key IN ('INHERIT', 'DEFAULT', 'MIST', 'SAGE', 'ROSE')),
    CONSTRAINT ck_msg_conversation_display_version CHECK (version > 0)
);

CREATE INDEX ix_msg_conversation_display_lookup
    ON msg_user_conversation_display_preferences (tenant_id, conversation_id, user_id);

ALTER TABLE msg_messages
    DROP CONSTRAINT ck_msg_body_length,
    ADD CONSTRAINT ck_msg_body_length CHECK (length(btrim(body)) BETWEEN 0 AND 20000);

COMMENT ON TABLE msg_user_display_preferences IS
    'Server-synchronized personal defaults for the Messaging presentation layer.';
COMMENT ON TABLE msg_user_conversation_display_preferences IS
    'Sparse user-owned presentation overrides. They never alter another member''s view.';
COMMENT ON TABLE msg_tenant_appearance_policies IS
    'Tenant allow-list for safe Messaging themes. Arbitrary remote backgrounds remain disabled.';
COMMENT ON CONSTRAINT ck_msg_body_length ON msg_messages IS
    'Blank text is valid only for attachment-only messages and is enforced transactionally by MessagingService.';
