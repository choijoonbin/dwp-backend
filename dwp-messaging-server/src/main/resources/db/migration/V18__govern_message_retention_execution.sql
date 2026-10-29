ALTER TABLE msg_messages
    ADD COLUMN retention_purged_at TIMESTAMPTZ;

ALTER TABLE msg_messages
    DROP CONSTRAINT ck_msg_body_length,
    ADD CONSTRAINT ck_msg_body_length CHECK (
        (retention_purged_at IS NULL AND length(btrim(body)) BETWEEN 0 AND 20000)
        OR (retention_purged_at IS NOT NULL AND body = '')
    );

COMMENT ON CONSTRAINT ck_msg_body_length ON msg_messages IS
    'Blank text is allowed for attachment-only messages and is required after a governed retention content purge.';

CREATE INDEX ix_msg_messages_retention_candidates
    ON msg_messages (tenant_id, created_at, message_id)
    WHERE retention_purged_at IS NULL;

CREATE TABLE msg_retention_holds (
    hold_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    conversation_id UUID,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reason_reference VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    released_at TIMESTAMPTZ,
    released_by BIGINT,
    CONSTRAINT fk_msg_retention_hold_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES msg_conversations (tenant_id, conversation_id),
    CONSTRAINT ck_msg_retention_hold_state CHECK (state IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_msg_retention_hold_reason CHECK (
        length(btrim(reason_reference)) BETWEEN 1 AND 200),
    CONSTRAINT ck_msg_retention_hold_lifecycle CHECK (
        (state = 'ACTIVE' AND released_at IS NULL AND released_by IS NULL)
        OR (state = 'RELEASED' AND released_at IS NOT NULL AND released_by IS NOT NULL))
);

CREATE INDEX ix_msg_retention_active_holds
    ON msg_retention_holds (tenant_id, conversation_id)
    WHERE state = 'ACTIVE';

CREATE TABLE msg_retention_runs (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    execution_mode VARCHAR(16) NOT NULL,
    cutoff_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    candidate_count BIGINT NOT NULL DEFAULT 0,
    purged_count BIGINT NOT NULL DEFAULT 0,
    skipped_hold_count BIGINT NOT NULL DEFAULT 0,
    attachment_deletion_pending_count BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(80),
    lease_owner UUID NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT fk_msg_retention_run_policy
        FOREIGN KEY (tenant_id) REFERENCES msg_tenant_policies (tenant_id),
    CONSTRAINT ck_msg_retention_run_mode CHECK (
        execution_mode IN ('DRY_RUN', 'ENFORCING')),
    CONSTRAINT ck_msg_retention_run_state CHECK (
        state IN ('RUNNING', 'COMPLETED', 'PAUSED', 'BLOCKED', 'FAILED')),
    CONSTRAINT ck_msg_retention_run_counts CHECK (
        candidate_count >= 0 AND purged_count >= 0 AND skipped_hold_count >= 0
        AND attachment_deletion_pending_count >= 0),
    CONSTRAINT ck_msg_retention_run_finished CHECK (
        (state = 'RUNNING' AND finished_at IS NULL)
        OR (state <> 'RUNNING' AND finished_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_msg_retention_running_tenant
    ON msg_retention_runs (tenant_id)
    WHERE state = 'RUNNING';

CREATE INDEX ix_msg_retention_runs_tenant_time
    ON msg_retention_runs (tenant_id, started_at DESC);

CREATE TABLE msg_retention_attachment_deletions (
    deletion_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES msg_retention_runs (run_id),
    tenant_id BIGINT NOT NULL,
    message_id UUID NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ux_msg_retention_attachment_object UNIQUE (tenant_id, object_key),
    CONSTRAINT ck_msg_retention_attachment_state CHECK (
        state IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_msg_retention_attachment_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_msg_retention_attachment_completed CHECK (
        (state = 'PENDING' AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL))
);

CREATE INDEX ix_msg_retention_attachment_pending
    ON msg_retention_attachment_deletions (tenant_id, created_at, deletion_id)
    WHERE state = 'PENDING';

COMMENT ON COLUMN msg_messages.retention_purged_at IS
    'Content purge timestamp. Structural identifiers and sequence remain for thread and audit integrity.';
COMMENT ON TABLE msg_retention_holds IS
    'Compliance-owned tenant or conversation holds. ENFORCE is blocked unless hold authority readiness is explicit.';
COMMENT ON TABLE msg_retention_runs IS
    'Payload-free retention execution evidence and cross-instance lease.';
COMMENT ON TABLE msg_retention_attachment_deletions IS
    'Retryable provider-object deletion evidence staged atomically before attachment metadata is purged.';
