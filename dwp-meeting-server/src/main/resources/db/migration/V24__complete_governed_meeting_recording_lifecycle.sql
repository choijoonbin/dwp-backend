-- Recording artifacts are produced asynchronously after a fenced STOP command.  Keep their
-- provenance separate from transcript registration/finalization evidence so neither contract
-- can weaken the other and so sequential recording sessions can retain distinct artifacts.
ALTER TABLE vm_meeting_recording_sessions
    ADD COLUMN artifact_retention_days INTEGER,
    ADD COLUMN recording_provider_code VARCHAR(48),
    ADD COLUMN recording_processing_region VARCHAR(32),
    ADD COLUMN stop_consent_snapshot_sha256 CHAR(64),
    ADD CONSTRAINT ck_vm_recording_session_retention_snapshot CHECK (
        artifact_retention_days IS NULL
        OR artifact_retention_days BETWEEN 1 AND 3650),
    ADD CONSTRAINT ck_vm_recording_session_provider_snapshot CHECK (
        (recording_provider_code IS NULL AND recording_processing_region IS NULL)
        OR (recording_provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$'
            AND recording_processing_region
                ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$')),
    ADD CONSTRAINT ck_vm_recording_session_consent_snapshot CHECK (
        stop_consent_snapshot_sha256 IS NULL
        OR stop_consent_snapshot_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE vm_meeting_artifacts
    DROP CONSTRAINT uk_vm_artifact_kind,
    ADD COLUMN recording_session_id UUID,
    ADD COLUMN recording_plan_version BIGINT,
    ADD COLUMN recording_provider_code VARCHAR(48),
    ADD COLUMN recording_finalization_idempotency_key VARCHAR(160),
    ADD COLUMN recording_finalization_request_sha256 CHAR(64),
    ADD COLUMN recording_finalized_at TIMESTAMPTZ,
    ADD COLUMN recording_finalized_by BIGINT,
    ADD CONSTRAINT fk_vm_artifact_recording_session FOREIGN KEY (
        tenant_id, meeting_id, recording_session_id)
        REFERENCES vm_meeting_recording_sessions (
            tenant_id, meeting_id, recording_session_id);

-- Pre-V23 recording rows have no trustworthy session, consent or provider provenance.
-- Quarantine them without inventing evidence. Keep the locator in restricted DB custody so the
-- governed deletion worker can remove the external object; public projections and tickets reject
-- the UNAVAILABLE state, and only verified deletion may clear the locator.
UPDATE vm_meeting_artifacts
   SET artifact_state = 'UNAVAILABLE',
       server_side_processing_allowed = FALSE, processing_region = NULL,
       content_notice_id = NULL, consent_snapshot_sha256 = NULL,
       metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
           'reason', 'LEGACY_RECORDING_PROVENANCE_MISSING',
           'locatorQuarantined', TRUE),
       version = version + 1, updated_at = CURRENT_TIMESTAMP
 WHERE artifact_type = 'RECORDING' AND artifact_state = 'AVAILABLE';

ALTER TABLE vm_meeting_artifacts
    ADD CONSTRAINT ck_vm_recording_artifact_evidence CHECK (
        (artifact_type <> 'RECORDING'
            AND recording_session_id IS NULL
            AND recording_plan_version IS NULL
            AND recording_provider_code IS NULL
            AND recording_finalization_idempotency_key IS NULL
            AND recording_finalization_request_sha256 IS NULL
            AND recording_finalized_at IS NULL
            AND recording_finalized_by IS NULL)
        OR (artifact_type = 'RECORDING'
            AND recording_session_id IS NULL
            AND recording_plan_version IS NULL
            AND recording_provider_code IS NULL
            AND recording_finalization_idempotency_key IS NULL
            AND recording_finalization_request_sha256 IS NULL
            AND recording_finalized_at IS NULL
            AND recording_finalized_by IS NULL
            AND artifact_state <> 'AVAILABLE')
        OR (artifact_type = 'RECORDING'
            AND artifact_state IN ('AVAILABLE', 'DELETED')
            AND recording_session_id IS NOT NULL
            AND recording_plan_version >= 0
            AND recording_provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$'
            AND recording_finalization_idempotency_key
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$'
            AND recording_finalization_request_sha256 ~ '^[0-9a-f]{64}$'
            AND recording_finalized_at IS NOT NULL
            AND recording_finalized_by > 0
            AND server_side_processing_allowed = FALSE
            AND processing_region
                ~ '^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$'
            AND content_notice_id IS NOT NULL
            AND consent_snapshot_sha256 ~ '^[0-9a-f]{64}$'
            AND retention_until IS NOT NULL
            AND sha256 ~ '^[0-9a-f]{64}$'));

CREATE UNIQUE INDEX uk_vm_artifact_nonrecording_kind
    ON vm_meeting_artifacts (tenant_id, meeting_id, artifact_type)
    WHERE artifact_type <> 'RECORDING';

CREATE UNIQUE INDEX uk_vm_recording_artifact_session
    ON vm_meeting_artifacts (tenant_id, meeting_id, recording_session_id)
    WHERE artifact_type = 'RECORDING' AND recording_session_id IS NOT NULL;

CREATE UNIQUE INDEX uk_vm_recording_artifact_finalization_idempotency
    ON vm_meeting_artifacts (
        tenant_id, meeting_id, recording_finalization_idempotency_key)
    WHERE recording_finalization_idempotency_key IS NOT NULL;

CREATE TABLE vm_meeting_recording_artifact_assertion_replay (
    jti UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    recording_session_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_vm_recording_artifact_assertion_session FOREIGN KEY (
        tenant_id, meeting_id, recording_session_id)
        REFERENCES vm_meeting_recording_sessions (
            tenant_id, meeting_id, recording_session_id) ON DELETE CASCADE,
    CONSTRAINT fk_vm_recording_artifact_assertion_artifact FOREIGN KEY (
        tenant_id, meeting_id, artifact_id)
        REFERENCES vm_meeting_artifacts (
            tenant_id, meeting_id, artifact_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_recording_artifact_assertion_expiry CHECK (
        expires_at > consumed_at)
);

CREATE INDEX ix_vm_recording_artifact_assertion_expiry
    ON vm_meeting_recording_artifact_assertion_replay (expires_at);

COMMENT ON COLUMN vm_meeting_artifacts.recording_finalization_request_sha256 IS
    'Semantic callback hash only; raw media URLs, object locators and PII are prohibited.';
COMMENT ON TABLE vm_meeting_recording_artifact_assertion_replay IS
    'Single-use inbound workload assertions for trusted recording artifact finalization.';

CREATE TABLE vm_meeting_recording_deletion_commands (
    deletion_command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    artifact_version BIGINT NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    command_state VARCHAR(16) NOT NULL,
    execution_fence UUID,
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    worker_id VARCHAR(120) NOT NULL,
    provider_code VARCHAR(48) NOT NULL,
    provider_deletion_id VARCHAR(160),
    failure_code VARCHAR(48),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_vm_recording_deletion_artifact UNIQUE (
        tenant_id, meeting_id, artifact_id),
    CONSTRAINT fk_vm_recording_deletion_artifact FOREIGN KEY (
        tenant_id, meeting_id, artifact_id)
        REFERENCES vm_meeting_artifacts (
            tenant_id, meeting_id, artifact_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_recording_deletion_state CHECK (
        command_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_vm_recording_deletion_request CHECK (
        artifact_version >= 0
        AND request_sha256 ~ '^[0-9a-f]{64}$'
        AND attempt_count > 0
        AND worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'
        AND provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$'),
    CONSTRAINT ck_vm_recording_deletion_terminal CHECK (
        (command_state = 'RUNNING' AND execution_fence IS NOT NULL
            AND lease_expires_at IS NOT NULL AND completed_at IS NULL
            AND failure_code IS NULL AND provider_deletion_id IS NULL)
        OR (command_state = 'SUCCEEDED' AND execution_fence IS NULL
            AND lease_expires_at IS NULL AND completed_at IS NOT NULL
            AND failure_code IS NULL
            AND provider_deletion_id
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,159}$')
        OR (command_state = 'FAILED' AND execution_fence IS NULL
            AND lease_expires_at IS NULL AND completed_at IS NOT NULL
            AND provider_deletion_id IS NULL
            AND failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$'))
);

CREATE INDEX ix_vm_recording_deletion_recovery
    ON vm_meeting_recording_deletion_commands (
        command_state, lease_expires_at, requested_at)
    WHERE command_state IN ('RUNNING', 'FAILED');

ALTER TABLE vm_meeting_artifacts
    ADD COLUMN recording_deletion_command_id UUID,
    ADD COLUMN recording_deleted_at TIMESTAMPTZ,
    ADD COLUMN recording_deletion_provider_code VARCHAR(48),
    ADD CONSTRAINT fk_vm_artifact_recording_deletion FOREIGN KEY (
        recording_deletion_command_id)
        REFERENCES vm_meeting_recording_deletion_commands (deletion_command_id);

-- Old DELETED rows may still retain an external locator without deletion evidence. They are not
-- considered deleted: quarantine them for the governed worker and preserve restricted custody
-- until a signed broker receipt is atomically committed.
UPDATE vm_meeting_artifacts
   SET artifact_state = 'UNAVAILABLE',
       metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
           'reason', 'LEGACY_RECORDING_DELETION_PENDING',
           'locatorQuarantined', TRUE),
       version = version + 1, updated_at = CURRENT_TIMESTAMP
 WHERE artifact_type = 'RECORDING' AND artifact_state = 'DELETED'
   AND storage_provider IS NOT NULL AND object_key IS NOT NULL;

UPDATE vm_meeting_artifacts
   SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
       'reason', 'LEGACY_RECORDING_DELETION_EVIDENCE_MISSING')
 WHERE artifact_type = 'RECORDING' AND artifact_state = 'DELETED';

ALTER TABLE vm_meeting_artifacts
    ADD CONSTRAINT ck_vm_recording_artifact_deletion_evidence CHECK (
        (artifact_type <> 'RECORDING'
            AND recording_deletion_command_id IS NULL
            AND recording_deleted_at IS NULL
            AND recording_deletion_provider_code IS NULL)
        OR (artifact_type = 'RECORDING' AND artifact_state <> 'DELETED'
            AND recording_deletion_command_id IS NULL
            AND recording_deleted_at IS NULL
            AND recording_deletion_provider_code IS NULL)
        OR (artifact_type = 'RECORDING' AND artifact_state = 'DELETED'
            AND recording_deletion_command_id IS NOT NULL
            AND recording_deleted_at IS NOT NULL
            AND recording_deletion_provider_code
                ~ '^[A-Z][A-Z0-9_-]{2,47}$'
            AND storage_provider IS NULL AND object_key IS NULL)
        OR (artifact_type = 'RECORDING' AND artifact_state = 'DELETED'
            AND recording_deletion_command_id IS NULL
            AND recording_deleted_at IS NULL
            AND recording_deletion_provider_code IS NULL
            AND metadata ->> 'reason'
                = 'LEGACY_RECORDING_DELETION_EVIDENCE_MISSING'));

CREATE TABLE vm_meeting_recording_deletion_health (
    health_key VARCHAR(48) PRIMARY KEY,
    last_success_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_failure_code VARCHAR(48),
    active_fence UUID,
    active_lease_expires_at TIMESTAMPTZ,
    active_worker_id VARCHAR(120),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vm_recording_deletion_health_key CHECK (
        health_key = 'RECORDING_RETENTION'),
    CONSTRAINT ck_vm_recording_deletion_health_failure CHECK (
        (last_failure_at IS NULL AND last_failure_code IS NULL)
        OR (last_failure_at IS NOT NULL
            AND last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$')),
    CONSTRAINT ck_vm_recording_deletion_health_lease CHECK (
        (active_fence IS NULL AND active_lease_expires_at IS NULL
            AND active_worker_id IS NULL)
        OR (active_fence IS NOT NULL AND active_lease_expires_at IS NOT NULL
            AND active_worker_id
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'))
);

INSERT INTO vm_meeting_recording_deletion_health (health_key)
VALUES ('RECORDING_RETENTION');

COMMENT ON TABLE vm_meeting_recording_deletion_commands IS
    'Durable fenced deletion evidence; object locators are read from restricted artifact rows.';
COMMENT ON TABLE vm_meeting_recording_deletion_health IS
    'Distributed worker lease and last-success evidence used by fail-closed recording readiness.';
