-- External transcript objects require the same durable, fenced crypto-shred evidence as media.
-- This migration also quarantines legacy locators that older schemas allowed in non-terminal
-- states so every retained object becomes eligible for a governed deletion command.

ALTER TABLE vm_meeting_recording_deletion_health
    ADD COLUMN last_provider_code VARCHAR(48),
    ADD CONSTRAINT ck_vm_recording_deletion_health_provider CHECK (
        last_provider_code IS NULL
        OR last_provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$');

ALTER TABLE vm_meeting_artifacts
    DROP CONSTRAINT ck_vm_artifact_finalization_evidence,
    DROP CONSTRAINT ck_vm_artifact_registration_evidence;

ALTER TABLE vm_meeting_artifacts
    ADD COLUMN transcript_plan_version BIGINT,
    ADD COLUMN transcript_provider_code VARCHAR(48),
    ADD COLUMN transcript_storage_provider_code VARCHAR(32);

UPDATE vm_meeting_artifacts
   SET artifact_state = 'UNAVAILABLE',
       server_side_processing_allowed = FALSE,
       metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
           'reason', 'LEGACY_RECORDING_LOCATOR_QUARANTINED',
           'locatorQuarantined', TRUE),
       version = version + 1, updated_at = CURRENT_TIMESTAMP
 WHERE artifact_type = 'RECORDING'
   AND artifact_state IN ('NONE', 'PROCESSING')
   AND storage_provider IS NOT NULL AND object_key IS NOT NULL;

UPDATE vm_meeting_artifacts
   SET artifact_state = CASE
           WHEN registration_idempotency_key IS NULL THEN 'UNAVAILABLE'
           ELSE 'FAILED'
       END,
       metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
           'reason', 'LEGACY_TRANSCRIPT_LOCATOR_QUARANTINED',
           'locatorQuarantined', TRUE),
       version = version + 1, updated_at = CURRENT_TIMESTAMP
 WHERE artifact_type = 'TRANSCRIPT'
   AND artifact_state IN ('NONE', 'PROCESSING', 'DELETED')
   AND storage_provider IS NOT NULL AND object_key IS NOT NULL;

-- No pre-V25 transcript row has a verifiable immutable registration/provider snapshot.
-- Quarantine every snapshot-null row before adding the new snapshot/deletion constraints.
-- This deliberately includes an apparently legal AVAILABLE row with registration_idempotency_key
-- NULL and server_side_processing_allowed=TRUE: legacy processing evidence is not a trusted
-- immutable snapshot. Preserve any locator only for the retention worker and prevent reads or
-- refinalization, including direct-finalization AVAILABLE rows that never used registration
-- evidence.
UPDATE vm_meeting_artifacts
   SET artifact_state = CASE
           WHEN registration_idempotency_key IS NOT NULL
                OR finalization_idempotency_key IS NOT NULL THEN 'FAILED'
           ELSE 'UNAVAILABLE'
       END,
       server_side_processing_allowed = FALSE,
       metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
           'reason', 'LEGACY_TRANSCRIPT_SNAPSHOT_MISSING',
           'locatorQuarantined', storage_provider IS NOT NULL AND object_key IS NOT NULL),
       version = version + 1, updated_at = CURRENT_TIMESTAMP
 WHERE artifact_type = 'TRANSCRIPT'
   AND transcript_plan_version IS NULL;

CREATE TABLE vm_meeting_transcript_deletion_commands (
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
    CONSTRAINT uk_vm_transcript_deletion_artifact UNIQUE (
        tenant_id, meeting_id, artifact_id),
    CONSTRAINT fk_vm_transcript_deletion_artifact FOREIGN KEY (
        tenant_id, meeting_id, artifact_id)
        REFERENCES vm_meeting_artifacts (
            tenant_id, meeting_id, artifact_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_transcript_deletion_state CHECK (
        command_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_vm_transcript_deletion_request CHECK (
        artifact_version >= 0
        AND request_sha256 ~ '^[0-9a-f]{64}$'
        AND attempt_count > 0
        AND worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'
        AND provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$'),
    CONSTRAINT ck_vm_transcript_deletion_terminal CHECK (
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

CREATE INDEX ix_vm_transcript_deletion_recovery
    ON vm_meeting_transcript_deletion_commands (
        command_state, lease_expires_at, requested_at)
    WHERE command_state IN ('RUNNING', 'FAILED');

ALTER TABLE vm_meeting_artifacts
    ADD COLUMN transcript_deletion_command_id UUID,
    ADD COLUMN transcript_deleted_at TIMESTAMPTZ,
    ADD COLUMN transcript_deletion_provider_code VARCHAR(48),
    ADD CONSTRAINT fk_vm_artifact_transcript_deletion FOREIGN KEY (
        transcript_deletion_command_id)
        REFERENCES vm_meeting_transcript_deletion_commands (deletion_command_id);

UPDATE vm_meeting_artifacts
   SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
       'reason', 'LEGACY_TRANSCRIPT_DELETION_EVIDENCE_MISSING')
 WHERE artifact_type = 'TRANSCRIPT' AND artifact_state = 'DELETED';

ALTER TABLE vm_meeting_artifacts
    ADD CONSTRAINT ck_vm_artifact_finalization_evidence CHECK (
        (finalization_idempotency_key IS NULL
            AND finalization_request_sha256 IS NULL
            AND finalized_at IS NULL AND finalized_by IS NULL)
        OR (artifact_type = 'TRANSCRIPT'
            AND artifact_state IN ('AVAILABLE', 'FAILED', 'DELETED')
            AND finalization_idempotency_key
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$'
            AND finalization_request_sha256 ~ '^[0-9a-f]{64}$'
            AND finalized_at IS NOT NULL AND finalized_by IS NOT NULL)),
    ADD CONSTRAINT ck_vm_artifact_registration_evidence CHECK (
        (registration_idempotency_key IS NULL
            AND registration_request_sha256 IS NULL
            AND registered_at IS NULL AND registered_by IS NULL)
        OR (artifact_type = 'TRANSCRIPT'
            AND artifact_state IN (
                'PROCESSING', 'AVAILABLE', 'UNAVAILABLE', 'FAILED', 'DELETED')
            AND registration_idempotency_key
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$'
            AND registration_request_sha256 ~ '^[0-9a-f]{64}$'
            AND sha256 ~ '^[0-9a-f]{64}$'
            AND retention_until IS NOT NULL AND retention_until > registered_at
            AND (artifact_state IN ('FAILED', 'DELETED')
                OR server_side_processing_allowed = TRUE)
            AND processing_region IS NOT NULL AND content_notice_id IS NOT NULL
            AND consent_snapshot_sha256 ~ '^[0-9a-f]{64}$'
            AND registered_at IS NOT NULL AND registered_by > 0)),
    ADD CONSTRAINT ck_vm_transcript_registration_snapshot CHECK (
        (artifact_type <> 'TRANSCRIPT'
            AND transcript_plan_version IS NULL
            AND transcript_provider_code IS NULL
            AND transcript_storage_provider_code IS NULL)
        OR (artifact_type = 'TRANSCRIPT'
            AND transcript_plan_version IS NULL
            AND transcript_provider_code IS NULL
            AND transcript_storage_provider_code IS NULL
            AND server_side_processing_allowed = FALSE)
        OR (artifact_type = 'TRANSCRIPT'
            AND transcript_plan_version >= 0
            AND transcript_provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$'
            AND transcript_storage_provider_code
                ~ '^[A-Z][A-Z0-9_-]{1,31}$')),
    ADD CONSTRAINT ck_vm_transcript_artifact_deletion_evidence CHECK (
        (artifact_type <> 'TRANSCRIPT'
            AND transcript_deletion_command_id IS NULL
            AND transcript_deleted_at IS NULL
            AND transcript_deletion_provider_code IS NULL)
        OR (artifact_type = 'TRANSCRIPT' AND artifact_state <> 'DELETED'
            AND transcript_deletion_command_id IS NULL
            AND transcript_deleted_at IS NULL
            AND transcript_deletion_provider_code IS NULL)
        OR (artifact_type = 'TRANSCRIPT' AND artifact_state = 'DELETED'
            AND transcript_deletion_command_id IS NOT NULL
            AND transcript_deleted_at IS NOT NULL
            AND transcript_deletion_provider_code
                ~ '^[A-Z][A-Z0-9_-]{2,47}$'
            AND storage_provider IS NULL AND object_key IS NULL)
        OR (artifact_type = 'TRANSCRIPT' AND artifact_state = 'DELETED'
            AND transcript_deletion_command_id IS NULL
            AND transcript_deleted_at IS NULL
            AND transcript_deletion_provider_code IS NULL
            AND storage_provider IS NULL AND object_key IS NULL
            AND metadata ->> 'reason'
                = 'LEGACY_TRANSCRIPT_DELETION_EVIDENCE_MISSING'));

CREATE TABLE vm_meeting_transcript_deletion_health (
    health_key VARCHAR(48) PRIMARY KEY,
    last_success_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_failure_code VARCHAR(48),
    last_provider_code VARCHAR(48),
    last_storage_provider_code VARCHAR(32),
    active_fence UUID,
    active_lease_expires_at TIMESTAMPTZ,
    active_worker_id VARCHAR(120),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vm_transcript_deletion_health_key CHECK (
        health_key = 'TRANSCRIPT_RETENTION'),
    CONSTRAINT ck_vm_transcript_deletion_health_failure CHECK (
        (last_failure_at IS NULL AND last_failure_code IS NULL)
        OR (last_failure_at IS NOT NULL
            AND last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$')),
    CONSTRAINT ck_vm_transcript_deletion_health_provider CHECK (
        (last_provider_code IS NULL AND last_storage_provider_code IS NULL)
        OR (last_provider_code ~ '^[A-Z][A-Z0-9_-]{2,47}$'
            AND last_storage_provider_code ~ '^[A-Z][A-Z0-9_-]{1,31}$')),
    CONSTRAINT ck_vm_transcript_deletion_health_lease CHECK (
        (active_fence IS NULL AND active_lease_expires_at IS NULL
            AND active_worker_id IS NULL)
        OR (active_fence IS NOT NULL AND active_lease_expires_at IS NOT NULL
            AND active_worker_id
                ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'))
);

INSERT INTO vm_meeting_transcript_deletion_health (health_key)
VALUES ('TRANSCRIPT_RETENTION');

COMMENT ON TABLE vm_meeting_transcript_deletion_commands IS
    'Durable fenced crypto-shred evidence; raw transcript text is never stored here.';
COMMENT ON TABLE vm_meeting_transcript_deletion_health IS
    'Fresh broker capability and worker evidence used to fail closed transcript ingestion.';
