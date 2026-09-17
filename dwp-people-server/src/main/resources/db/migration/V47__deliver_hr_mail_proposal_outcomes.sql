CREATE TABLE abs_mail_proposal_outcome_outbox (
    outcome_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    proposal_id UUID NOT NULL,
    command_id UUID NOT NULL,
    proposal_version BIGINT NOT NULL,
    leave_request_id UUID NOT NULL,
    result_ref VARCHAR(500) NOT NULL,
    correlation_id TEXT,
    delivery_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_abs_mail_proposal_outcome UNIQUE (tenant_id, proposal_id),
    CONSTRAINT ck_abs_mail_proposal_version CHECK (proposal_version >= 0),
    CONSTRAINT ck_abs_mail_proposal_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_abs_mail_proposal_delivery_state
        CHECK (delivery_state IN ('PENDING', 'PUBLISHED', 'UNKNOWN_RECONCILE')),
    CONSTRAINT ck_abs_mail_proposal_result_ref
        CHECK (result_ref LIKE 'hr-leave-request:%')
);

CREATE INDEX idx_abs_mail_proposal_outcome_delivery
    ON abs_mail_proposal_outcome_outbox(next_attempt_at, created_at, outcome_id)
    WHERE delivery_state = 'PENDING';

COMMENT ON TABLE abs_mail_proposal_outcome_outbox IS
    'Durable owner callback from committed HR leave requests to trusted Mail proposals.';
