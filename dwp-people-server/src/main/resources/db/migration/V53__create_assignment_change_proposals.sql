-- People migration lineage V49-V52 is reserved by the reconciled HRIS branch.
-- Release packaging must reconcile those immutable predecessors before V53 is deployed;
-- do not ship this migration alone and rely on later out-of-order execution.
CREATE TABLE ppl_assignment_change_proposals (
    assignment_change_proposal_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    target_assignment_id BIGINT NOT NULL,
    target_worker_id BIGINT NOT NULL,
    target_work_relationship_id BIGINT NOT NULL,
    change_type VARCHAR(40) NOT NULL,
    effective_date DATE NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    proposed_changes JSONB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    validation_sha256 CHAR(64),
    validation_findings JSONB NOT NULL DEFAULT '[]'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    target_worker_version BIGINT NOT NULL,
    target_relationship_version BIGINT NOT NULL,
    target_assignment_version BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    validated_at TIMESTAMPTZ,
    validated_by BIGINT,
    submitted_at TIMESTAMPTZ,
    submitted_by BIGINT,
    cancelled_at TIMESTAMPTZ,
    cancelled_by BIGINT,
    cancellation_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_ppl_assignment_change_proposal_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_assignment_change_proposal_id
        UNIQUE (tenant_id, assignment_change_proposal_id),
    CONSTRAINT fk_ppl_assignment_change_proposal_assignment
        FOREIGN KEY (tenant_id, target_assignment_id)
        REFERENCES ppl_assignments(tenant_id, assignment_id),
    CONSTRAINT fk_ppl_assignment_change_proposal_worker
        FOREIGN KEY (tenant_id, target_worker_id)
        REFERENCES ppl_workers(tenant_id, worker_id),
    CONSTRAINT fk_ppl_assignment_change_proposal_relationship
        FOREIGN KEY (tenant_id, target_work_relationship_id)
        REFERENCES ppl_work_relationships(tenant_id, work_relationship_id),
    CONSTRAINT fk_ppl_assignment_change_proposal_reason
        FOREIGN KEY (tenant_id, reason_code)
        REFERENCES ppl_assignment_change_reason_catalog(tenant_id, reason_code),
    CONSTRAINT ck_ppl_assignment_change_proposal_type CHECK (
        change_type IN ('TRANSFER', 'PROMOTION', 'DEMOTION',
                        'CHANGE_MANAGER', 'CHANGE_LOCATION', 'CORRECTION')),
    CONSTRAINT ck_ppl_assignment_change_proposal_state CHECK (
        lifecycle_state IN ('DRAFT', 'VALIDATED', 'SUBMITTED', 'CANCELLED')),
    CONSTRAINT ck_ppl_assignment_change_proposal_changes
        CHECK (jsonb_typeof(proposed_changes) = 'object'),
    CONSTRAINT ck_ppl_assignment_change_proposal_findings
        CHECK (jsonb_typeof(validation_findings) = 'array'),
    CONSTRAINT ck_ppl_assignment_change_proposal_content_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_assignment_change_proposal_validation_hash
        CHECK (validation_sha256 IS NULL OR validation_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_assignment_change_proposal_versions CHECK (
        target_worker_version >= 0 AND target_relationship_version >= 0
        AND target_assignment_version >= 0 AND aggregate_version >= 0),
    CONSTRAINT ck_ppl_assignment_change_proposal_state_times CHECK (
        (lifecycle_state <> 'VALIDATED' OR validated_at IS NOT NULL)
        AND (lifecycle_state <> 'SUBMITTED'
             OR (validated_at IS NOT NULL AND submitted_at IS NOT NULL))
        AND (lifecycle_state <> 'CANCELLED' OR cancelled_at IS NOT NULL))
);

CREATE INDEX idx_ppl_assignment_change_proposal_target
    ON ppl_assignment_change_proposals(
        tenant_id, target_assignment_id, lifecycle_state, created_at DESC);
CREATE INDEX idx_ppl_assignment_change_proposal_queue
    ON ppl_assignment_change_proposals(
        tenant_id, lifecycle_state, effective_date, created_at);

CREATE TABLE ppl_assignment_change_proposal_events (
    event_id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    assignment_change_proposal_id BIGINT NOT NULL,
    proposal_version BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    actor_user_id BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ppl_assignment_change_proposal_event
        UNIQUE (tenant_id, assignment_change_proposal_id, proposal_version, event_type),
    CONSTRAINT fk_ppl_assignment_change_proposal_event_proposal
        FOREIGN KEY (tenant_id, assignment_change_proposal_id)
        REFERENCES ppl_assignment_change_proposals(
            tenant_id, assignment_change_proposal_id),
    CONSTRAINT ck_ppl_assignment_change_proposal_event_type CHECK (
        event_type IN ('CREATED', 'VALIDATED', 'SUBMITTED', 'CANCELLED')),
    CONSTRAINT ck_ppl_assignment_change_proposal_event_payload
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_ppl_assignment_change_proposal_event_version
        CHECK (proposal_version >= 0)
);

CREATE TABLE ppl_assignment_command_receipts (
    assignment_command_receipt_id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    command_id UUID NOT NULL,
    subject_user_id BIGINT NOT NULL,
    subject_principal_public_id UUID,
    originating_action VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    assignment_change_proposal_id BIGINT,
    result_proposal_public_id UUID,
    resulting_version BIGINT,
    result_payload JSONB,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    population_revision VARCHAR(500) NOT NULL,
    decision_revision VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_ppl_assignment_command_receipt_public_id UNIQUE (public_id),
    CONSTRAINT uk_ppl_assignment_command_receipt_id
        UNIQUE (tenant_id, assignment_command_receipt_id),
    CONSTRAINT uk_ppl_assignment_command_receipt_command
        UNIQUE (tenant_id, command_id),
    CONSTRAINT uk_ppl_assignment_command_receipt_idempotency
        UNIQUE (tenant_id, subject_user_id, originating_action, idempotency_key),
    CONSTRAINT fk_ppl_assignment_command_receipt_proposal
        FOREIGN KEY (tenant_id, assignment_change_proposal_id)
        REFERENCES ppl_assignment_change_proposals(
            tenant_id, assignment_change_proposal_id),
    CONSTRAINT ck_ppl_assignment_command_receipt_action CHECK (
        originating_action IN ('CREATE', 'VALIDATE', 'SUBMIT', 'CANCEL')),
    CONSTRAINT ck_ppl_assignment_command_receipt_state CHECK (
        lifecycle_state IN ('IN_PROGRESS', 'SUCCEEDED')),
    CONSTRAINT ck_ppl_assignment_command_receipt_hash
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_assignment_command_receipt_result CHECK (
        (lifecycle_state = 'IN_PROGRESS'
            AND result_proposal_public_id IS NULL
            AND resulting_version IS NULL
            AND result_payload IS NULL
            AND completed_at IS NULL)
        OR
        (lifecycle_state = 'SUCCEEDED'
            AND assignment_change_proposal_id IS NOT NULL
            AND result_proposal_public_id IS NOT NULL
            AND resulting_version IS NOT NULL
            AND result_payload IS NOT NULL
            AND jsonb_typeof(result_payload) = 'object'
            AND completed_at IS NOT NULL))
);

CREATE TABLE ppl_assignment_proposal_outbox (
    event_id UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    assignment_change_proposal_id BIGINT NOT NULL,
    command_receipt_id BIGINT NOT NULL,
    proposal_version BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ppl_assignment_proposal_outbox_event
        UNIQUE (tenant_id, assignment_change_proposal_id, proposal_version, event_type),
    CONSTRAINT uk_ppl_assignment_proposal_outbox_receipt
        UNIQUE (tenant_id, command_receipt_id),
    CONSTRAINT fk_ppl_assignment_proposal_outbox_proposal
        FOREIGN KEY (tenant_id, assignment_change_proposal_id)
        REFERENCES ppl_assignment_change_proposals(
            tenant_id, assignment_change_proposal_id),
    CONSTRAINT fk_ppl_assignment_proposal_outbox_receipt
        FOREIGN KEY (tenant_id, command_receipt_id)
        REFERENCES ppl_assignment_command_receipts(
            tenant_id, assignment_command_receipt_id),
    CONSTRAINT ck_ppl_assignment_proposal_outbox_payload
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_ppl_assignment_proposal_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_ppl_assignment_proposal_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_ppl_assignment_proposal_outbox_delivery
    ON ppl_assignment_proposal_outbox(status, available_at, created_at);

COMMENT ON TABLE ppl_assignment_change_proposals IS
    'Governed employment assignment change proposals. Submission records intent only and never mutates the effective-dated assignment ledger.';
COMMENT ON TABLE ppl_assignment_change_proposal_events IS
    'Append-only lifecycle history for assignment change proposals.';
COMMENT ON TABLE ppl_assignment_command_receipts IS
    'Atomic idempotency receipts for assignment proposal commands.';
COMMENT ON TABLE ppl_assignment_proposal_outbox IS
    'Transactional domain-event outbox for assignment proposal lifecycle changes.';
