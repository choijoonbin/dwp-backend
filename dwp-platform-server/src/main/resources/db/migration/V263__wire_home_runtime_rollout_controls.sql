ALTER TABLE plt_widget_runtime_controls
    ALTER COLUMN target_id TYPE VARCHAR(320);

ALTER TABLE plt_widget_runtime_controls
    DROP CONSTRAINT ck_widget_control_target;
ALTER TABLE plt_widget_runtime_controls
    ADD CONSTRAINT ck_widget_control_target CHECK (
        target_type IN ('GLOBAL', 'PROVIDER', 'TENANT', 'DEFINITION', 'VERSION', 'MODE', 'ACTION'));

ALTER TABLE plt_widget_runtime_controls
    DROP CONSTRAINT ck_widget_control_provider;
ALTER TABLE plt_widget_runtime_controls
    ADD CONSTRAINT ck_widget_control_provider CHECK (
        (target_type IN ('PROVIDER', 'ACTION') AND provider_product_key IS NOT NULL)
        OR target_type NOT IN ('PROVIDER', 'ACTION'));

ALTER TABLE plt_widget_runtime_controls
    ADD CONSTRAINT ck_widget_control_mode CHECK (
        target_type <> 'MODE' OR target_id IN ('CLASSIC', 'FLOW_V1'));

CREATE TABLE usr_home_owner_action_receipts (
    receipt_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    user_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    contract_id VARCHAR(320) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    receipt_state VARCHAR(16) NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_home_owner_action_command UNIQUE (tenant_id, user_id, command_id),
    CONSTRAINT ck_home_owner_action_state CHECK (receipt_state IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_home_owner_action_response CHECK (jsonb_typeof(response_payload) = 'object'),
    CONSTRAINT ck_home_owner_action_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_home_owner_action_receipt_expiry
    ON usr_home_owner_action_receipts(expires_at);
