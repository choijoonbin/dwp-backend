-- Content-free, durable bridge from governed transcript finalization to AI recap execution.
-- Raw transcript text, storage locators, credentials and provider tokens are prohibited here.
CREATE TABLE vm_meeting_intelligence_auto_requests (
    request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    source_artifact_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    content_notice_id UUID NOT NULL,
    expected_content_plan_version BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    output_language VARCHAR(16) NOT NULL,
    processing_region VARCHAR(32) NOT NULL,
    intelligence_idempotency_key VARCHAR(160) NOT NULL,
    request_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    execution_fence UUID,
    lease_expires_at TIMESTAMPTZ,
    execution_generation INTEGER NOT NULL DEFAULT 1,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    run_id UUID,
    last_failure_code VARCHAR(48),
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vm_intelligence_auto_source UNIQUE (
        tenant_id, meeting_id, source_artifact_id, source_sha256,
        expected_content_plan_version),
    CONSTRAINT uk_vm_intelligence_auto_idempotency UNIQUE (
        tenant_id, meeting_id, requested_by, intelligence_idempotency_key),
    CONSTRAINT fk_vm_intelligence_auto_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_intelligence_auto_artifact FOREIGN KEY (
        tenant_id, meeting_id, source_artifact_id)
        REFERENCES vm_meeting_artifacts (tenant_id, meeting_id, artifact_id),
    CONSTRAINT fk_vm_intelligence_auto_notice FOREIGN KEY (
        tenant_id, meeting_id, content_notice_id)
        REFERENCES vm_meeting_content_notices (tenant_id, meeting_id, notice_id),
    CONSTRAINT fk_vm_intelligence_auto_run FOREIGN KEY (
        tenant_id, meeting_id, run_id)
        REFERENCES vm_meeting_intelligence_runs (tenant_id, meeting_id, run_id),
    CONSTRAINT ck_vm_intelligence_auto_state CHECK (
        request_state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_vm_intelligence_auto_language CHECK (
        output_language ~ '^[a-z]{2}(-[A-Z]{2})?$'),
    CONSTRAINT ck_vm_intelligence_auto_region CHECK (
        processing_region ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'),
    CONSTRAINT ck_vm_intelligence_auto_hash CHECK (
        source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_vm_intelligence_auto_idempotency CHECK (
        intelligence_idempotency_key
            ~ '^auto-recap:[0-9a-f-]{36}:[1-9][0-9]{0,8}$'),
    CONSTRAINT ck_vm_intelligence_auto_attempt CHECK (
        execution_generation > 0 AND attempt_count >= 0),
    CONSTRAINT ck_vm_intelligence_auto_failure CHECK (
        last_failure_code IS NULL
        OR last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$'),
    CONSTRAINT ck_vm_intelligence_auto_lifecycle CHECK (
        (request_state = 'PENDING'
            AND execution_fence IS NULL AND lease_expires_at IS NULL
            AND run_id IS NULL AND completed_at IS NULL)
        OR (request_state = 'RUNNING'
            AND execution_fence IS NOT NULL AND lease_expires_at IS NOT NULL
            AND run_id IS NULL AND completed_at IS NULL)
        OR (request_state = 'SUCCEEDED'
            AND execution_fence IS NULL AND lease_expires_at IS NULL
            AND run_id IS NOT NULL AND last_failure_code IS NULL
            AND completed_at IS NOT NULL)
        OR (request_state = 'FAILED'
            AND execution_fence IS NULL AND lease_expires_at IS NULL
            AND last_failure_code IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_vm_intelligence_auto_dispatch
    ON vm_meeting_intelligence_auto_requests (
        request_state, available_at, lease_expires_at, created_at);

COMMENT ON TABLE vm_meeting_intelligence_auto_requests IS
    'Content-free fenced requests from transcript finalization to AI recap; never stores transcript text, object locators or credentials.';
