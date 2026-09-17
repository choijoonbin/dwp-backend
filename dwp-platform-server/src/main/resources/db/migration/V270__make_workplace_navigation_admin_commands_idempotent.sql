ALTER TABLE wp_navigation_audit_events
    ADD CONSTRAINT uk_wp_navigation_audit_tenant_event
        UNIQUE (tenant_id, audit_event_id);

CREATE TABLE wp_navigation_admin_commands (
    admin_command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    command_type VARCHAR(40) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    result_type VARCHAR(32) NOT NULL,
    result_snapshot JSONB NOT NULL,
    audit_event_id UUID NOT NULL,
    correlation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    UNIQUE (tenant_id, admin_command_id),
    FOREIGN KEY (tenant_id, audit_event_id)
        REFERENCES wp_navigation_audit_events(tenant_id, audit_event_id),
    CONSTRAINT ck_wp_navigation_admin_command_type CHECK (command_type IN
        ('DEVICE_APPROVE','DEVICE_BIND','PROVIDER_CONFIGURE','DEVICE_COMMAND_PREVIEW')),
    CONSTRAINT ck_wp_navigation_admin_resource_type CHECK (resource_type IN
        ('DEVICE','PROVIDER_TRUTH')),
    CONSTRAINT ck_wp_navigation_admin_result_type CHECK (result_type IN
        ('DEVICE','PROVIDER_TRUTH','DEVICE_COMMAND_PREVIEW')),
    CONSTRAINT ck_wp_navigation_admin_key CHECK
        (idempotency_key ~ '^[!-~]{1,160}$'),
    CONSTRAINT ck_wp_navigation_admin_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_navigation_admin_result CHECK
        (jsonb_typeof(result_snapshot) = 'object')
);

COMMENT ON TABLE wp_navigation_admin_commands IS
    'Durable exact-replay receipts for navigation/device administrator writes and persisted previews.';
