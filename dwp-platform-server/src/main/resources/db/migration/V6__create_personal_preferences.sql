CREATE TABLE usr_personal_preferences (
    personal_preference_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    preference_payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_usr_personal_preferences_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_usr_personal_preferences_schema CHECK (schema_version > 0),
    CONSTRAINT ck_usr_personal_preferences_payload_object
        CHECK (jsonb_typeof(preference_payload) = 'object')
);
