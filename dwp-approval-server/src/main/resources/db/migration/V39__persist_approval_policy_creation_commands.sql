CREATE TABLE apr_policy_creation_commands (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    command_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    policy_id UUID,
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (
        tenant_id, management_resource_set_key, actor_user_id, idempotency_key),
    CONSTRAINT ck_apr_policy_creation_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_policy_creation_key CHECK (
        idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CONSTRAINT ck_apr_policy_creation_digest CHECK (
        command_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_apr_policy_creation_status CHECK (
        status IN ('UNKNOWN', 'SUCCEEDED')),
    CONSTRAINT ck_apr_policy_creation_result CHECK (
        (status = 'UNKNOWN' AND policy_id IS NULL
            AND result_payload IS NULL AND completed_at IS NULL)
        OR
        (status = 'SUCCEEDED' AND policy_id IS NOT NULL
            AND jsonb_typeof(result_payload) = 'object'
            AND completed_at IS NOT NULL)),
    CONSTRAINT fk_apr_policy_creation_policy FOREIGN KEY (tenant_id, policy_id)
        REFERENCES apr_policy_rules(tenant_id, policy_id)
);

CREATE INDEX idx_apr_policy_creation_policy
    ON apr_policy_creation_commands (
        tenant_id, management_resource_set_key, policy_id)
    WHERE status = 'SUCCEEDED';

COMMENT ON TABLE apr_policy_creation_commands IS
    'Durable exact-once receipts for APR-15 governance policy draft creation. UNKNOWN is fail-closed.';
