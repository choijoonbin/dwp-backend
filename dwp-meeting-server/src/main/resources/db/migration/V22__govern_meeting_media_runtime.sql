ALTER TABLE vm_meetings
    ADD COLUMN media_incarnation UUID,
    ADD COLUMN media_access_state VARCHAR(16) NOT NULL DEFAULT 'INACTIVE',
    ADD COLUMN provider_room_sid VARCHAR(80),
    ADD COLUMN provider_room_closed_at TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM vm_meetings
         WHERE lifecycle_state = 'LIVE' AND provider <> 'LIVEKIT') THEN
        RAISE EXCEPTION
            'V22 requires a provider-specific drain for every non-LiveKit live meeting';
    END IF;
END $$;

WITH compatibility_hash AS (
    SELECT meeting_id,
           md5('dwp-meeting-media-compat-v1|' || tenant_id || '|' || meeting_id) AS value
      FROM vm_meetings
     WHERE lifecycle_state = 'LIVE' AND provider = 'LIVEKIT'
), compatibility_uuid AS (
    SELECT meeting_id,
           (substr(value, 1, 8) || '-' || substr(value, 9, 4) || '-3'
                || substr(value, 14, 3) || '-'
                || substr(
                    '89ab89ab89ab89ab',
                    strpos('0123456789abcdef', substr(value, 17, 1)), 1)
                || substr(value, 18, 3) || '-' || substr(value, 21, 12))::uuid
                AS incarnation
      FROM compatibility_hash
)
UPDATE vm_meetings meeting
   SET media_incarnation = compatibility_uuid.incarnation,
       media_access_state = 'MIGRATING'
  FROM compatibility_uuid
 WHERE meeting.meeting_id = compatibility_uuid.meeting_id;

UPDATE vm_meeting_participants participant
   SET attendance_state = 'LEFT', left_at = CURRENT_TIMESTAMP,
       version = participant.version + 1, updated_at = CURRENT_TIMESTAMP
  FROM vm_meetings meeting
 WHERE meeting.tenant_id = participant.tenant_id
   AND meeting.meeting_id = participant.meeting_id
   AND meeting.lifecycle_state = 'LIVE'
   AND meeting.media_access_state = 'MIGRATING'
   AND participant.attendance_state = 'JOINED';

UPDATE vm_meetings
   SET media_incarnation = gen_random_uuid(), media_access_state = 'ENDED'
 WHERE lifecycle_state = 'ENDED';

ALTER TABLE vm_meetings
    ADD CONSTRAINT ck_vm_meeting_media_access_state CHECK (
        media_access_state IN ('INACTIVE', 'MIGRATING', 'ACTIVE', 'ENDING', 'ENDED')),
    ADD CONSTRAINT ck_vm_meeting_media_incarnation CHECK (
        (lifecycle_state IN ('LIVE', 'ENDED') AND media_incarnation IS NOT NULL)
        OR lifecycle_state NOT IN ('LIVE', 'ENDED'));

ALTER TABLE vm_meetings DROP CONSTRAINT ck_vm_meeting_end;
ALTER TABLE vm_meetings
    ADD CONSTRAINT ck_vm_meeting_end CHECK (
        lifecycle_state <> 'ENDED'
        OR (ended_at IS NOT NULL
            AND (ended_by IS NOT NULL OR provider_room_closed_at IS NOT NULL)));

ALTER TABLE vm_meeting_media_operations
    ADD COLUMN room_incarnation UUID,
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM vm_meeting_media_operations operation
         WHERE operation.operation_state IN ('RUNNING', 'FAILED')
           AND operation.provider_code <> 'LIVEKIT') THEN
        RAISE EXCEPTION
            'V22 cannot migrate a non-LiveKit active meeting media command';
    END IF;
END $$;

CREATE TEMP TABLE vm_v22_legacy_start_operations ON COMMIT DROP AS
SELECT operation_id, provider_code, provider_room_name, requested_at
  FROM vm_meeting_media_operations
 WHERE operation_type = 'START'
   AND operation_state IN ('RUNNING', 'FAILED');

UPDATE vm_meeting_media_operations
   SET room_incarnation = gen_random_uuid(),
       next_attempt_at = CASE
           WHEN operation_state = 'FAILED' THEN CURRENT_TIMESTAMP
           ELSE NULL
       END;

WITH start_hash AS (
    SELECT operation.operation_id,
           operation.tenant_id,
           operation.meeting_id,
           operation.idempotency_key,
           operation.expected_meeting_version,
           operation.provider_code,
           md5('dwp-meeting-media-v1|' || operation.tenant_id || '|'
               || operation.meeting_id || '|' || operation.idempotency_key) AS value
      FROM vm_meeting_media_operations operation
      JOIN vm_meetings meeting
        ON meeting.tenant_id = operation.tenant_id
       AND meeting.meeting_id = operation.meeting_id
     WHERE operation.operation_type = 'START'
       AND operation.operation_state IN ('RUNNING', 'FAILED')
       AND meeting.lifecycle_state IN ('DRAFT', 'SCHEDULED', 'LOBBY')
), start_uuid AS (
    SELECT *,
           (substr(value, 1, 8) || '-' || substr(value, 9, 4) || '-3'
                || substr(value, 14, 3) || '-'
                || substr(
                    '89ab89ab89ab89ab',
                    strpos('0123456789abcdef', substr(value, 17, 1)), 1)
                || substr(value, 18, 3) || '-' || substr(value, 21, 12))::uuid
                AS incarnation
      FROM start_hash
), start_binding AS (
    SELECT *,
           'dwp-meeting-t' || tenant_id || '-'
               || replace(meeting_id::text, '-', '') || '-i'
               || replace(incarnation::text, '-', '') AS target_room_name
      FROM start_uuid
)
UPDATE vm_meeting_media_operations operation
   SET room_incarnation = binding.incarnation,
       provider_room_name = binding.target_room_name,
       request_sha256 = encode(digest(
           'START' || chr(31) || binding.meeting_id::text || chr(31)
           || binding.expected_meeting_version::text || chr(31)
           || binding.provider_code || chr(31) || binding.target_room_name,
           'sha256'), 'hex')
  FROM start_binding binding
 WHERE operation.operation_id = binding.operation_id;

UPDATE vm_meeting_media_operations operation
   SET room_incarnation = meeting.media_incarnation
  FROM vm_meetings meeting
 WHERE meeting.tenant_id = operation.tenant_id
   AND meeting.meeting_id = operation.meeting_id
   AND operation.operation_type = 'END'
   AND operation.operation_state IN ('RUNNING', 'FAILED')
   AND meeting.media_incarnation IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM vm_meeting_media_operations operation
          LEFT JOIN vm_meetings meeting
            ON meeting.tenant_id = operation.tenant_id
           AND meeting.meeting_id = operation.meeting_id
         WHERE operation.operation_state IN ('RUNNING', 'FAILED')
           AND (operation.operation_type = 'START'
                    AND operation.provider_room_name NOT LIKE '%-i%'
                OR operation.operation_type = 'END'
                    AND (meeting.media_incarnation IS NULL
                         OR operation.room_incarnation <> meeting.media_incarnation))) THEN
        RAISE EXCEPTION
            'V22 could not bind an active meeting media command to an incarnation';
    END IF;
END $$;

ALTER TABLE vm_meeting_media_operations
    ALTER COLUMN room_incarnation SET NOT NULL;

CREATE INDEX idx_vm_media_operation_retry
    ON vm_meeting_media_operations (
        operation_state, next_attempt_at, lease_expires_at, requested_at)
    WHERE operation_state IN ('RUNNING', 'FAILED');

CREATE TABLE vm_meeting_media_upgrades (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    room_incarnation UUID NOT NULL,
    legacy_room_name VARCHAR(180) NOT NULL,
    target_room_name VARCHAR(180) NOT NULL,
    upgrade_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    execution_fence UUID,
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_failure_code VARCHAR(80),
    token_fenced_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    cleanup_not_before TIMESTAMPTZ NOT NULL
        DEFAULT (clock_timestamp() + INTERVAL '11 minutes'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, meeting_id),
    CONSTRAINT fk_vm_media_upgrade_meeting FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT ck_vm_media_upgrade_state CHECK (
        upgrade_state IN (
            'PENDING', 'PROVISIONING', 'FAILED_PROVISION', 'SWITCHED',
            'CLEANING', 'FAILED_CLEANUP', 'SUCCEEDED')),
    CONSTRAINT ck_vm_media_upgrade_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_vm_media_upgrade_token_drain CHECK (
        cleanup_not_before >= token_fenced_at + INTERVAL '10 minutes'),
    CONSTRAINT ck_vm_media_upgrade_distinct_room CHECK (
        legacy_room_name <> target_room_name),
    CONSTRAINT ck_vm_media_upgrade_lease CHECK (
        (upgrade_state IN ('PROVISIONING', 'CLEANING')
            AND execution_fence IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (upgrade_state NOT IN ('PROVISIONING', 'CLEANING')
            AND execution_fence IS NULL AND lease_expires_at IS NULL))
);

INSERT INTO vm_meeting_media_upgrades (
    tenant_id, meeting_id, room_incarnation, legacy_room_name, target_room_name)
SELECT tenant_id, meeting_id, media_incarnation, room_name,
       'dwp-meeting-t' || tenant_id || '-' || replace(meeting_id::text, '-', '')
           || '-i' || replace(media_incarnation::text, '-', '')
  FROM vm_meetings
 WHERE lifecycle_state = 'LIVE' AND provider = 'LIVEKIT'
   AND media_access_state = 'MIGRATING';

CREATE INDEX idx_vm_media_upgrade_recovery
    ON vm_meeting_media_upgrades (
        upgrade_state, next_attempt_at, lease_expires_at, updated_at)
    WHERE upgrade_state <> 'SUCCEEDED';

CREATE OR REPLACE FUNCTION vm_reject_fenced_media_token_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.event_type = 'TOKEN_ISSUED' AND EXISTS (
        SELECT 1 FROM vm_meetings meeting
         WHERE meeting.tenant_id = NEW.tenant_id
           AND meeting.meeting_id = NEW.meeting_id
           AND meeting.media_access_state <> 'ACTIVE') THEN
        RAISE EXCEPTION 'Meeting media token issuance is fenced';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_vm_reject_fenced_media_token_event
BEFORE INSERT ON vm_meeting_events
FOR EACH ROW EXECUTE FUNCTION vm_reject_fenced_media_token_event();

CREATE TABLE vm_meeting_provider_events (
    provider_code VARCHAR(24) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    tenant_id BIGINT,
    meeting_id UUID,
    room_incarnation UUID,
    provider_room_name VARCHAR(180),
    provider_room_sid VARCHAR(80),
    participant_id UUID,
    provider_participant_sid VARCHAR(80),
    provider_created_at TIMESTAMPTZ NOT NULL,
    processing_state VARCHAR(24) NOT NULL,
    reason_code VARCHAR(80),
    cleanup_fence UUID,
    cleanup_lease_expires_at TIMESTAMPTZ,
    cleanup_attempt_count INTEGER NOT NULL DEFAULT 0,
    next_cleanup_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_code, provider_event_id),
    CONSTRAINT ck_vm_provider_event_type CHECK (event_type IN (
        'ROOM_STARTED', 'ROOM_FINISHED', 'PARTICIPANT_JOINED',
        'PARTICIPANT_LEFT', 'PARTICIPANT_CONNECTION_ABORTED')),
    CONSTRAINT ck_vm_provider_event_state CHECK (processing_state IN (
        'APPLIED', 'IGNORED', 'CLEANUP_REQUIRED', 'CLEANUP_RUNNING',
        'CLEANUP_FAILED', 'CLEANED')),
    CONSTRAINT ck_vm_provider_event_reason CHECK (
        (processing_state = 'APPLIED' AND reason_code IS NULL)
        OR (processing_state <> 'APPLIED' AND reason_code IS NOT NULL)),
    CONSTRAINT ck_vm_provider_event_cleanup_lease CHECK (
        (processing_state = 'CLEANUP_RUNNING'
            AND cleanup_fence IS NOT NULL AND cleanup_lease_expires_at IS NOT NULL)
        OR processing_state <> 'CLEANUP_RUNNING'),
    CONSTRAINT ck_vm_provider_event_cleanup_attempt CHECK (
        cleanup_attempt_count >= 0)
);

INSERT INTO vm_meeting_provider_events (
    provider_code, provider_event_id, event_type, tenant_id, meeting_id,
    room_incarnation, provider_room_name, provider_created_at,
    processing_state, reason_code, received_at, processed_at)
SELECT operation.provider_code,
       'migration-v22-start-' || operation.operation_id,
       'ROOM_STARTED', operation.tenant_id, operation.meeting_id,
       operation.room_incarnation, legacy.provider_room_name, legacy.requested_at,
       'CLEANUP_REQUIRED', 'MIGRATION_LEGACY_START_ROOM',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM vm_v22_legacy_start_operations legacy
  JOIN vm_meeting_media_operations operation
    ON operation.operation_id = legacy.operation_id
 WHERE legacy.provider_room_name <> operation.provider_room_name;

CREATE INDEX idx_vm_provider_event_cleanup
    ON vm_meeting_provider_events (processing_state, processed_at)
    WHERE processing_state IN (
        'CLEANUP_REQUIRED', 'CLEANUP_RUNNING', 'CLEANUP_FAILED');

CREATE TABLE vm_meeting_provider_connections (
    provider_code VARCHAR(24) NOT NULL,
    provider_participant_sid VARCHAR(80) NOT NULL,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    room_incarnation UUID NOT NULL,
    participant_id UUID NOT NULL,
    connection_state VARCHAR(16) NOT NULL,
    provider_joined_at TIMESTAMPTZ,
    provider_left_at TIMESTAMPTZ,
    last_provider_event_id VARCHAR(160) NOT NULL,
    last_provider_event_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_code, provider_participant_sid),
    CONSTRAINT fk_vm_provider_connection_participant FOREIGN KEY (
        tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_vm_provider_connection_state CHECK (
        connection_state IN ('JOINED', 'LEFT', 'ABORTED')),
    CONSTRAINT ck_vm_provider_connection_join CHECK (
        connection_state <> 'JOINED' OR provider_joined_at IS NOT NULL),
    CONSTRAINT ck_vm_provider_connection_leave CHECK (
        connection_state = 'JOINED' OR provider_left_at IS NOT NULL)
);

CREATE INDEX idx_vm_provider_connection_active
    ON vm_meeting_provider_connections (
        tenant_id, meeting_id, room_incarnation, participant_id)
    WHERE connection_state = 'JOINED';

COMMENT ON COLUMN vm_meetings.media_incarnation IS
    'Server-issued media session epoch. Tokens and provider events must bind to this UUID.';
COMMENT ON COLUMN vm_meetings.media_access_state IS
    'Fail-closed token issuance fence independent from the public lifecycle projection.';
COMMENT ON TABLE vm_meeting_provider_events IS
    'Content-free, signed provider webhook replay receipts. Raw webhook bodies are never stored.';
COMMENT ON TABLE vm_meeting_provider_connections IS
    'Provider-authoritative participant sessions; a terminal SID cannot be resurrected.';
COMMENT ON TABLE vm_meeting_media_upgrades IS
    'Durable rolling upgrade from legacy unbound rooms to incarnation-bound rooms.';
