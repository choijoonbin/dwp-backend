CREATE TABLE prv_tenant_mutations (
    mutation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    mutation_type VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(180) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    expected_tenant_version BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    previous_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    desired_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    requested_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    correlation_id VARCHAR(128),
    failure_code VARCHAR(80),
    failure_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_tenant_mutation_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_prv_tenant_mutation_revision UNIQUE (provider_tenant_id, target_revision),
    CONSTRAINT ck_prv_tenant_mutation_type
        CHECK (mutation_type IN ('LIFECYCLE', 'ENTITLEMENTS')),
    CONSTRAINT ck_prv_tenant_mutation_hash
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_prv_tenant_mutation_revision
        CHECK (target_revision > 0 AND expected_tenant_version >= 0),
    CONSTRAINT ck_prv_tenant_mutation_state
        CHECK (lifecycle_state IN (
            'PENDING', 'EXECUTING', 'RETRY_WAIT', 'SUCCEEDED',
            'COMPENSATING', 'COMPENSATED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_prv_tenant_mutation_payloads
        CHECK (jsonb_typeof(previous_payload) = 'object'
           AND jsonb_typeof(desired_payload) = 'object')
);

CREATE UNIQUE INDEX uk_prv_tenant_mutation_single_active
    ON prv_tenant_mutations(provider_tenant_id)
    WHERE lifecycle_state IN (
        'PENDING', 'EXECUTING', 'RETRY_WAIT', 'COMPENSATING', 'RECONCILIATION_REQUIRED');

CREATE INDEX idx_prv_tenant_mutation_recovery
    ON prv_tenant_mutations(lifecycle_state, updated_at, provider_tenant_id);

CREATE TABLE prv_tenant_command_outbox (
    command_id UUID PRIMARY KEY,
    mutation_id UUID NOT NULL REFERENCES prv_tenant_mutations(mutation_id) ON DELETE CASCADE,
    command_order INTEGER NOT NULL,
    target_service VARCHAR(24) NOT NULL,
    command_type VARCHAR(24) NOT NULL,
    expected_revision BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    compensation BOOLEAN NOT NULL DEFAULT FALSE,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(120),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    response_payload JSONB,
    last_error_code VARCHAR(80),
    last_error_message VARCHAR(500),
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_tenant_command_order UNIQUE (mutation_id, command_order),
    CONSTRAINT uk_prv_tenant_command_target_revision
        UNIQUE (mutation_id, target_service, command_type, target_revision),
    CONSTRAINT ck_prv_tenant_command_order CHECK (command_order > 0),
    CONSTRAINT ck_prv_tenant_command_target
        CHECK (target_service IN ('AUTH', 'PLATFORM', 'PEOPLE')),
    CONSTRAINT ck_prv_tenant_command_type
        CHECK (command_type IN ('LIFECYCLE', 'ENTITLEMENTS')),
    CONSTRAINT ck_prv_tenant_command_revision
        CHECK (expected_revision >= 0 AND target_revision = expected_revision + 1),
    CONSTRAINT ck_prv_tenant_command_hash
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_prv_tenant_command_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_prv_tenant_command_state
        CHECK (lifecycle_state IN (
            'PENDING', 'LEASED', 'RETRY_WAIT', 'APPLIED',
            'COMPENSATION_PENDING', 'COMPENSATED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_prv_tenant_command_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_prv_tenant_command_claim
    ON prv_tenant_command_outbox(lifecycle_state, next_attempt_at, command_order)
    WHERE lifecycle_state IN ('PENDING', 'RETRY_WAIT', 'COMPENSATION_PENDING');

CREATE INDEX idx_prv_tenant_command_lease
    ON prv_tenant_command_outbox(lease_expires_at)
    WHERE lifecycle_state = 'LEASED';

COMMENT ON TABLE prv_tenant_mutations IS
    'Durable tenant lifecycle and entitlement mutation aggregate. Only one active mutation is allowed per tenant.';
COMMENT ON TABLE prv_tenant_command_outbox IS
    'Ordered, leased provider commands. A downstream receipt is the authoritative acknowledgement after ambiguous transport failure.';
