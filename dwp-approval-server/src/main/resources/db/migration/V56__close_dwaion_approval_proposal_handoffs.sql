CREATE TABLE apr_dwaion_proposal_handoffs (
    binding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    person_public_id UUID,
    handoff_id UUID NOT NULL UNIQUE,
    proposal_id UUID NOT NULL,
    action_key VARCHAR(128) NOT NULL,
    handoff_version BIGINT NOT NULL,
    auth_session_id VARCHAR(160) NOT NULL,
    roles VARCHAR(4000) NOT NULL,
    permissions VARCHAR(8000) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    delivery_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    next_observation VARCHAR(20) NOT NULL DEFAULT 'HANDED_OFF',
    domain_status VARCHAR(40),
    domain_version BIGINT,
    domain_committed_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(160),
    locked_until TIMESTAMPTZ,
    receipt_id UUID,
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_apr_dwaion_proposal_request UNIQUE (tenant_id, request_id),
    CONSTRAINT fk_apr_dwaion_proposal_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT ck_apr_dwaion_proposal_action CHECK (action_key = 'APPROVAL.REQUEST.CREATE'),
    CONSTRAINT ck_apr_dwaion_proposal_version CHECK (handoff_version >= 1),
    CONSTRAINT ck_apr_dwaion_proposal_delivery CHECK (
        delivery_state IN ('DRAFT', 'PENDING', 'SENDING', 'RETRY', 'COMPLETED', 'TERMINAL')),
    CONSTRAINT ck_apr_dwaion_proposal_observation CHECK (
        next_observation IN ('HANDED_OFF', 'RUNNING', 'COMPLETED')),
    CONSTRAINT ck_apr_dwaion_proposal_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_apr_dwaion_proposal_domain CHECK (
        (delivery_state = 'DRAFT' AND domain_committed_at IS NULL)
        OR (delivery_state <> 'DRAFT' AND domain_committed_at IS NOT NULL)),
    CONSTRAINT ck_apr_dwaion_proposal_completion CHECK (
        (delivery_state = 'COMPLETED' AND receipt_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (delivery_state <> 'COMPLETED' AND completed_at IS NULL))
);

CREATE INDEX idx_apr_dwaion_proposal_delivery
    ON apr_dwaion_proposal_handoffs (available_at, created_at, binding_id)
    WHERE delivery_state IN ('PENDING', 'RETRY', 'SENDING');

COMMENT ON TABLE apr_dwaion_proposal_handoffs IS
    'Durable, owner-bound completion bridge from reviewed DWAI-ON proposals to committed Approval requests.';
COMMENT ON COLUMN apr_dwaion_proposal_handoffs.delivery_state IS
    'DRAFT never emits completion; PENDING starts only in the same transaction as the Approval domain submit commit.';
