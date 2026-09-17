CREATE TABLE abs_mail_proposal_execution_receipts (
    receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    proposal_id UUID NOT NULL,
    command_id UUID NOT NULL,
    proposal_version BIGINT NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    execution_state VARCHAR(24) NOT NULL DEFAULT 'CLAIMED',
    leave_request_id UUID,
    result_ref VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_abs_mail_proposal_execution
        UNIQUE (tenant_id, proposal_id),
    CONSTRAINT ck_abs_mail_execution_version
        CHECK (proposal_version >= 0),
    CONSTRAINT ck_abs_mail_execution_fingerprint
        CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_abs_mail_execution_state
        CHECK (execution_state IN ('CLAIMED', 'EXECUTED')),
    CONSTRAINT ck_abs_mail_execution_result
        CHECK (
            (execution_state = 'CLAIMED'
                AND leave_request_id IS NULL
                AND result_ref IS NULL
                AND completed_at IS NULL)
            OR
            (execution_state = 'EXECUTED'
                AND leave_request_id IS NOT NULL
                AND result_ref = 'hr-leave-request:' || leave_request_id::TEXT
                AND completed_at IS NOT NULL)
        ),
    CONSTRAINT fk_abs_mail_execution_leave_request
        FOREIGN KEY (leave_request_id) REFERENCES abs_leave_requests(public_id)
);

CREATE INDEX idx_abs_mail_proposal_execution_command
    ON abs_mail_proposal_execution_receipts(tenant_id, command_id);

-- Older committed owner writes already have a durable V47 outbox row. Fence them
-- before accepting any new owner command. Their original request payload was not
-- persisted, so a retry fails closed on fingerprint mismatch instead of risking a
-- second leave request.
INSERT INTO abs_mail_proposal_execution_receipts (
    tenant_id, actor_id, proposal_id, command_id, proposal_version,
    request_fingerprint, execution_state, leave_request_id, result_ref,
    created_at, completed_at, updated_at)
SELECT tenant_id, actor_id, proposal_id, command_id, proposal_version,
       repeat('0', 64), 'EXECUTED', leave_request_id,
       'hr-leave-request:' || leave_request_id::TEXT,
       created_at, COALESCE(published_at, terminal_at, created_at),
       COALESCE(published_at, terminal_at, created_at)
  FROM abs_mail_proposal_outcome_outbox
ON CONFLICT (tenant_id, proposal_id) DO NOTHING;

COMMENT ON TABLE abs_mail_proposal_execution_receipts IS
    'Owner-side idempotency claim and result receipt. The unique proposal row serializes Mail handoff execution before any HR mutation.';
