CREATE TABLE sys_provider_tenant_command_receipts (
    command_id UUID PRIMARY KEY,
    provider_tenant_id UUID NOT NULL,
    command_type VARCHAR(24) NOT NULL,
    expected_revision BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_payload JSONB NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_people_provider_command_revision
        UNIQUE (provider_tenant_id, command_type, target_revision),
    CONSTRAINT ck_people_provider_command_type CHECK (command_type = 'LIFECYCLE'),
    CONSTRAINT ck_people_provider_command_revision
        CHECK (expected_revision >= 0 AND target_revision = expected_revision + 1),
    CONSTRAINT ck_people_provider_command_hash
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_people_provider_command_result CHECK (jsonb_typeof(result_payload) = 'object')
);

CREATE INDEX idx_people_provider_command_tenant
    ON sys_provider_tenant_command_receipts(provider_tenant_id, command_type, target_revision DESC);

COMMENT ON TABLE sys_provider_tenant_command_receipts IS
    'Atomic provider lifecycle command receipt. The people mutation and receipt commit together.';
