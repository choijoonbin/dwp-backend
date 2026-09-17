CREATE TABLE wp_resource_command_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    booking_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    command_type VARCHAR(40) NOT NULL,
    expected_booking_version BIGINT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    provider_capability VARCHAR(24) NOT NULL,
    provider_code VARCHAR(80),
    provider_configuration_version BIGINT,
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, preview_id),
    FOREIGN KEY (tenant_id, booking_id)
        REFERENCES wp_bookings(tenant_id, booking_id),
    FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    CONSTRAINT ck_wp_resource_command_preview_type CHECK (command_type IN (
        'PARKING_EXTEND','PARKING_EXIT_STATUS','LOCKER_UNLOCK',
        'NFC_KEY_RESEND','ROOM_PRE_ENTRY')),
    CONSTRAINT ck_wp_resource_command_preview_capability CHECK
        (provider_capability IN ('NFC','SPEED_GATE')),
    CONSTRAINT ck_wp_resource_command_preview_version CHECK
        (expected_booking_version >= 0),
    CONSTRAINT ck_wp_resource_command_preview_json CHECK
        (jsonb_typeof(payload)='object' AND jsonb_typeof(limitations)='array'),
    CONSTRAINT ck_wp_resource_command_preview_provider CHECK (
        (provider_code IS NULL AND provider_configuration_version IS NULL)
        OR (provider_code ~ '^[A-Za-z0-9._-]{1,80}$'
            AND provider_configuration_version > 0)),
    CONSTRAINT ck_wp_resource_command_preview_clock CHECK (expires_at > created_at)
);

CREATE INDEX idx_wp_resource_command_preview_actor
    ON wp_resource_command_previews(tenant_id, actor_user_id, booking_id, created_at DESC);

CREATE TABLE wp_resource_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    booking_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    preview_id UUID NOT NULL,
    command_type VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    provider_configuration_version BIGINT NOT NULL,
    credential_reference VARCHAR(180) NOT NULL,
    provider_operation_reference VARCHAR(320),
    result_code VARCHAR(120),
    reason VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 1,
    accepted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, booking_id)
        REFERENCES wp_bookings(tenant_id, booking_id),
    FOREIGN KEY (tenant_id, resource_id)
        REFERENCES wp_resources(tenant_id, resource_id),
    FOREIGN KEY (tenant_id, preview_id)
        REFERENCES wp_resource_command_previews(tenant_id, preview_id),
    CONSTRAINT ck_wp_resource_command_type CHECK (command_type IN (
        'PARKING_EXTEND','PARKING_EXIT_STATUS','LOCKER_UNLOCK',
        'NFC_KEY_RESEND','ROOM_PRE_ENTRY')),
    CONSTRAINT ck_wp_resource_command_state CHECK
        (command_state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_resource_command_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_resource_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_resource_command_provider CHECK
        (provider_code ~ '^[A-Za-z0-9._-]{1,80}$'
         AND provider_configuration_version > 0
         AND credential_reference ~ '^secret-manager://[A-Za-z0-9._/-]{1,143}$'),
    CONSTRAINT ck_wp_resource_command_reason CHECK
        (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_resource_command_version CHECK (version > 0),
    CONSTRAINT ck_wp_resource_command_completion CHECK (
        (command_state='RESULT_UNKNOWN' AND completed_at IS NULL)
        OR (command_state IN ('SUCCEEDED','FAILED') AND completed_at IS NOT NULL))
);

CREATE INDEX idx_wp_resource_command_status
    ON wp_resource_commands(tenant_id, actor_user_id, updated_at DESC, command_id);

CREATE TABLE wp_resource_command_reconciliations (
    reconciliation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    result_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_resource_commands(tenant_id, command_id),
    CONSTRAINT ck_wp_resource_command_reconcile_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_resource_command_reconcile_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_resource_command_reconcile_snapshot CHECK
        (jsonb_typeof(result_snapshot)='object')
);

COMMENT ON TABLE wp_resource_commands IS
    'Durable user-owned receipts for provider-bound Workplace resource actions. RESULT_UNKNOWN is never inferred as success or failure.';
COMMENT ON COLUMN wp_resource_commands.credential_reference IS
    'Opaque secret-manager reference captured at confirmation; reusable provider credentials are prohibited.';
