CREATE TABLE wp_hold_release_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    intent_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    released_hold_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    intent_version BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    explicit_confirmation BOOLEAN NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_wp_hold_release_command_key UNIQUE (
        tenant_id, actor_user_id, intent_id, idempotency_key),
    CONSTRAINT fk_wp_hold_release_command_intent FOREIGN KEY (tenant_id, intent_id)
        REFERENCES wp_booking_intents(tenant_id, intent_id),
    CONSTRAINT ck_wp_hold_release_command_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_wp_hold_release_command_key CHECK (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_hold_release_command_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_hold_release_command_state CHECK (
        command_state IN ('SUCCEEDED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_hold_release_command_ids CHECK (
        jsonb_typeof(released_hold_ids) = 'array'),
    CONSTRAINT ck_wp_hold_release_command_version CHECK (intent_version > 0),
    CONSTRAINT ck_wp_hold_release_command_reason CHECK (
        length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_hold_release_command_confirmation CHECK (explicit_confirmation),
    CONSTRAINT ck_wp_hold_release_command_completion CHECK (
        (command_state = 'SUCCEEDED' AND completed_at IS NOT NULL)
        OR command_state = 'RESULT_UNKNOWN')
);

CREATE INDEX idx_wp_hold_release_command_intent
    ON wp_hold_release_commands (tenant_id, actor_user_id, intent_id, created_at DESC);
