ALTER TABLE vm_meeting_artifacts
    ADD COLUMN server_side_processing_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN processing_region VARCHAR(32),
    ADD COLUMN content_notice_id UUID,
    ADD COLUMN consent_snapshot_sha256 CHAR(64),
    ADD COLUMN finalization_idempotency_key VARCHAR(160),
    ADD COLUMN finalization_request_sha256 CHAR(64),
    ADD COLUMN finalized_at TIMESTAMPTZ,
    ADD COLUMN finalized_by BIGINT,
    ADD CONSTRAINT uk_vm_artifact_intelligence_scope UNIQUE (
        tenant_id, meeting_id, artifact_id),
    ADD CONSTRAINT fk_vm_artifact_processing_notice FOREIGN KEY (
        tenant_id, meeting_id, content_notice_id)
        REFERENCES vm_meeting_content_notices (tenant_id, meeting_id, notice_id),
    ADD CONSTRAINT ck_vm_artifact_processing_evidence CHECK (
        NOT server_side_processing_allowed
        OR (artifact_type = 'TRANSCRIPT'
            AND processing_region IS NOT NULL
            AND processing_region ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'
            AND content_notice_id IS NOT NULL
            AND consent_snapshot_sha256 ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT ck_vm_artifact_finalization_evidence CHECK (
        (finalization_idempotency_key IS NULL
            AND finalization_request_sha256 IS NULL
            AND finalized_at IS NULL AND finalized_by IS NULL)
        OR (artifact_type = 'TRANSCRIPT' AND artifact_state = 'AVAILABLE'
            AND finalization_idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$'
            AND finalization_request_sha256 ~ '^[0-9a-f]{64}$'
            AND finalized_at IS NOT NULL AND finalized_by IS NOT NULL));

CREATE TABLE vm_meeting_intelligence_runs (
    run_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    source_artifact_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    content_notice_id UUID NOT NULL,
    consent_snapshot_sha256 CHAR(64) NOT NULL,
    analysis_profile VARCHAR(48) NOT NULL,
    output_language VARCHAR(16) NOT NULL,
    processing_region VARCHAR(32) NOT NULL,
    execution_fence UUID NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    run_state VARCHAR(20) NOT NULL,
    provider_code VARCHAR(48) NOT NULL,
    provider_model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(48) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    requested_by BIGINT NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_code VARCHAR(48),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vm_intelligence_run_scope UNIQUE (tenant_id, meeting_id, run_id),
    CONSTRAINT uk_vm_intelligence_run_idempotency UNIQUE (
        tenant_id, meeting_id, requested_by, idempotency_key),
    CONSTRAINT fk_vm_intelligence_run_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_intelligence_run_artifact FOREIGN KEY (
        tenant_id, meeting_id, source_artifact_id)
        REFERENCES vm_meeting_artifacts (tenant_id, meeting_id, artifact_id),
    CONSTRAINT fk_vm_intelligence_run_notice FOREIGN KEY (
        tenant_id, meeting_id, content_notice_id)
        REFERENCES vm_meeting_content_notices (tenant_id, meeting_id, notice_id),
    CONSTRAINT ck_vm_intelligence_run_state CHECK (
        run_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_vm_intelligence_run_terminal CHECK (
        (run_state = 'RUNNING' AND started_at IS NOT NULL
            AND completed_at IS NULL AND failure_code IS NULL)
        OR (run_state = 'SUCCEEDED' AND started_at IS NOT NULL
            AND completed_at IS NOT NULL AND failure_code IS NULL)
        OR (run_state = 'FAILED' AND started_at IS NOT NULL
            AND completed_at IS NOT NULL AND failure_code IS NOT NULL)),
    CONSTRAINT ck_vm_intelligence_run_profile CHECK (
        analysis_profile = 'STANDARD_RECAP_V1'),
    CONSTRAINT ck_vm_intelligence_run_language CHECK (
        output_language ~ '^[a-z]{2}(-[A-Z]{2})?$'),
    CONSTRAINT ck_vm_intelligence_run_region CHECK (
        processing_region ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'),
    CONSTRAINT ck_vm_intelligence_run_lease CHECK (
        lease_expires_at > requested_at AND attempt_count > 0),
    CONSTRAINT ck_vm_intelligence_run_failure_code CHECK (
        failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$')
);

CREATE TABLE vm_meeting_intelligence_reports (
    report_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    run_id UUID NOT NULL,
    report_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    audience VARCHAR(32) NOT NULL DEFAULT 'PRIVATE_REVIEWERS',
    encrypted_payload TEXT,
    payload_sha256 CHAR(64),
    source_sha256 CHAR(64) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    retention_until TIMESTAMPTZ NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    approved_at TIMESTAMPTZ,
    approved_by BIGINT,
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_vm_intelligence_report_scope UNIQUE (tenant_id, meeting_id, report_id),
    CONSTRAINT uk_vm_intelligence_report_run UNIQUE (tenant_id, meeting_id, run_id),
    CONSTRAINT fk_vm_intelligence_report_run FOREIGN KEY (tenant_id, meeting_id, run_id)
        REFERENCES vm_meeting_intelligence_runs (tenant_id, meeting_id, run_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_vm_intelligence_report_state CHECK (
        report_state IN ('DRAFT', 'APPROVED', 'PUBLISHED', 'REJECTED', 'DELETED')),
    CONSTRAINT ck_vm_intelligence_report_audience CHECK (
        audience IN ('PRIVATE_REVIEWERS', 'MEETING_PARTICIPANTS')),
    CONSTRAINT ck_vm_intelligence_report_payload CHECK (
        (report_state = 'DELETED' AND encrypted_payload IS NULL
            AND payload_sha256 IS NULL AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
        OR (report_state <> 'DELETED' AND encrypted_payload IS NOT NULL
            AND payload_sha256 ~ '^[0-9a-f]{64}$'
            AND deleted_at IS NULL AND deleted_by IS NULL)),
    CONSTRAINT ck_vm_intelligence_report_approval CHECK (
        (report_state IN ('APPROVED', 'PUBLISHED')
            AND approved_at IS NOT NULL AND approved_by IS NOT NULL)
        OR (report_state NOT IN ('APPROVED', 'PUBLISHED'))),
    CONSTRAINT ck_vm_intelligence_report_publish CHECK (
        (report_state = 'PUBLISHED'
            AND audience = 'MEETING_PARTICIPANTS'
            AND published_at IS NOT NULL AND published_by IS NOT NULL)
        OR (report_state <> 'PUBLISHED'
            AND audience = 'PRIVATE_REVIEWERS'
            AND published_at IS NULL AND published_by IS NULL)),
    CONSTRAINT ck_vm_intelligence_report_retention CHECK (
        retention_until > created_at)
);

CREATE TABLE vm_meeting_intelligence_reviews (
    review_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    report_id UUID NOT NULL,
    reviewed_report_version BIGINT NOT NULL,
    reviewed_payload_sha256 CHAR(64) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    reason_code VARCHAR(48) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL,
    reviewed_by BIGINT NOT NULL,
    CONSTRAINT uk_vm_intelligence_review_scope UNIQUE (
        tenant_id, meeting_id, report_id, review_id),
    CONSTRAINT fk_vm_intelligence_review_report FOREIGN KEY (
        tenant_id, meeting_id, report_id)
        REFERENCES vm_meeting_intelligence_reports (tenant_id, meeting_id, report_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_vm_intelligence_review_decision CHECK (
        decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT ck_vm_intelligence_review_reason CHECK (
        reason_code ~ '^[A-Z][A-Z0-9_]{2,47}$')
);

CREATE TABLE vm_meeting_intelligence_deletions (
    deletion_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    report_id UUID NOT NULL,
    previous_report_state VARCHAR(20) NOT NULL,
    previous_payload_sha256 CHAR(64) NOT NULL,
    deletion_reason VARCHAR(32) NOT NULL,
    fence_token UUID NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL,
    worker_id VARCHAR(120) NOT NULL,
    CONSTRAINT uk_vm_intelligence_deletion_report UNIQUE (
        tenant_id, meeting_id, report_id),
    CONSTRAINT fk_vm_intelligence_deletion_report FOREIGN KEY (
        tenant_id, meeting_id, report_id)
        REFERENCES vm_meeting_intelligence_reports (tenant_id, meeting_id, report_id),
    CONSTRAINT ck_vm_intelligence_deletion_reason CHECK (
        deletion_reason = 'RETENTION_EXPIRED'),
    CONSTRAINT ck_vm_intelligence_deletion_worker CHECK (
        worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$')
);

CREATE TABLE vm_meeting_intelligence_retention_health (
    health_key VARCHAR(32) PRIMARY KEY,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_failure_code VARCHAR(48),
    active_fence UUID,
    active_lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_vm_intelligence_retention_health_key CHECK (
        health_key = 'REPORT_RETENTION'),
    CONSTRAINT ck_vm_intelligence_retention_failure CHECK (
        (last_failure_at IS NULL AND last_failure_code IS NULL)
        OR (last_failure_at IS NOT NULL
            AND last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$')),
    CONSTRAINT ck_vm_intelligence_retention_lease CHECK (
        (active_fence IS NULL AND active_lease_expires_at IS NULL)
        OR (active_fence IS NOT NULL AND active_lease_expires_at IS NOT NULL))
);

INSERT INTO vm_meeting_intelligence_retention_health (health_key)
VALUES ('REPORT_RETENTION');

CREATE TABLE vm_meeting_transcript_finalization_assertion_replay (
    jti UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_vm_transcript_assertion_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_transcript_assertion_artifact FOREIGN KEY (
        tenant_id, meeting_id, artifact_id)
        REFERENCES vm_meeting_artifacts (tenant_id, meeting_id, artifact_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_vm_transcript_assertion_expiry CHECK (expires_at > consumed_at)
);

CREATE INDEX ix_vm_transcript_assertion_expiry
    ON vm_meeting_transcript_finalization_assertion_replay (expires_at);

CREATE TABLE vm_meeting_content_acl (
    acl_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    content_id UUID NOT NULL,
    principal_user_id BIGINT NOT NULL,
    permission VARCHAR(16) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    granted_by BIGINT NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by BIGINT,
    reason_code VARCHAR(48) NOT NULL,
    CONSTRAINT fk_vm_content_acl_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_content_acl_report FOREIGN KEY (
        tenant_id, meeting_id, content_id)
        REFERENCES vm_meeting_intelligence_reports (tenant_id, meeting_id, report_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_vm_content_acl_type CHECK (content_type = 'INTELLIGENCE_REPORT'),
    CONSTRAINT ck_vm_content_acl_permission CHECK (
        permission IN ('VIEW', 'REVIEW', 'MANAGE')),
    CONSTRAINT ck_vm_content_acl_principal CHECK (principal_user_id > 0),
    CONSTRAINT ck_vm_content_acl_expiry CHECK (
        expires_at IS NULL OR expires_at > granted_at),
    CONSTRAINT ck_vm_content_acl_revocation CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL)),
    CONSTRAINT ck_vm_content_acl_reason CHECK (
        reason_code ~ '^[A-Z][A-Z0-9_]{2,47}$')
);

CREATE UNIQUE INDEX uk_vm_content_acl_active
    ON vm_meeting_content_acl (
        tenant_id, meeting_id, content_type, content_id, principal_user_id, permission)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_vm_intelligence_run_history
    ON vm_meeting_intelligence_runs (tenant_id, meeting_id, requested_at DESC);
CREATE UNIQUE INDEX uk_vm_intelligence_run_active_source
    ON vm_meeting_intelligence_runs (
        tenant_id, meeting_id, source_artifact_id, source_sha256,
        analysis_profile, content_notice_id)
    WHERE run_state = 'RUNNING';
CREATE INDEX ix_vm_intelligence_report_retention
    ON vm_meeting_intelligence_reports (retention_until)
    WHERE report_state <> 'DELETED' AND legal_hold = FALSE;
CREATE INDEX ix_vm_content_acl_lookup
    ON vm_meeting_content_acl (
        tenant_id, meeting_id, content_type, content_id, principal_user_id)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE vm_meeting_intelligence_runs IS
    'Governed provider execution evidence; runtime defaults fail closed and never seeds success.';
COMMENT ON TABLE vm_meeting_intelligence_reports IS
    'Encrypted, human-reviewed meeting-level intelligence; no participant emotion or biometric profile.';
COMMENT ON TABLE vm_meeting_intelligence_reviews IS
    'Content-free immutable human approval or rejection evidence.';
COMMENT ON TABLE vm_meeting_intelligence_deletions IS
    'Immutable content-free evidence for fenced retention ciphertext shredding.';
COMMENT ON TABLE vm_meeting_intelligence_retention_health IS
    'Durable worker liveness and failure evidence used to fail closed new report creation.';
COMMENT ON TABLE vm_meeting_content_acl IS
    'Content-specific user grants independent from broad meeting discovery permissions.';
