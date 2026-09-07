-- Structured recurrence and preparation references. This migration stores no file bytes,
-- access tokens, device identifiers, notification payloads, or invitation message content.
CREATE TABLE vm_meeting_series (
    series_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    organizer_user_id BIGINT NOT NULL,
    frequency VARCHAR(16) NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY')),
    recurrence_interval INTEGER NOT NULL CHECK (recurrence_interval BETWEEN 1 AND 12),
    occurrence_count INTEGER NOT NULL CHECK (occurrence_count BETWEEN 2 AND 52),
    anchor_local_start TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 5 AND 1440),
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    UNIQUE (tenant_id, organizer_user_id, idempotency_key),
    UNIQUE (tenant_id, series_id)
);

CREATE TABLE vm_meeting_occurrences (
    tenant_id BIGINT NOT NULL,
    series_id UUID NOT NULL,
    occurrence_index INTEGER NOT NULL CHECK (occurrence_index BETWEEN 1 AND 52),
    meeting_id UUID NOT NULL,
    original_local_start TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    exception_state VARCHAR(16) NOT NULL DEFAULT 'NONE'
        CHECK (exception_state IN ('NONE', 'RESCHEDULED', 'CANCELLED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, series_id, occurrence_index),
    UNIQUE (tenant_id, meeting_id),
    FOREIGN KEY (tenant_id, series_id)
        REFERENCES vm_meeting_series (tenant_id, series_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE
);

CREATE TABLE vm_meeting_schedule_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(24) NOT NULL CHECK (operation IN ('RESCHEDULE', 'CANCEL')),
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    result_version BIGINT NOT NULL CHECK (result_version >= 0),
    result_projection JSONB NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, meeting_id, actor_user_id, operation, idempotency_key),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CHECK (jsonb_typeof(result_projection) = 'object'),
    CHECK ((result_projection - ARRAY[
        'meetingId', 'lifecycleState', 'startsAt', 'endsAt', 'timeZone',
        'meetingVersion', 'seriesId', 'occurrenceIndex', 'occurrenceCount',
        'frequency', 'recurrenceInterval', 'seriesVersion', 'exceptionState',
        'invitationRevision', 'deliveryState'
    ]) = '{}'::jsonb)
);

CREATE TABLE vm_meeting_invitation_outbox (
    event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL CHECK (event_type IN (
        'MEETING_SCHEDULED', 'MEETING_RESCHEDULED', 'MEETING_CANCELLED',
        'PREPARATION_MATERIAL_ADDED', 'PREPARATION_MATERIAL_REMOVED')),
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version >= 0),
    invitation_revision BIGINT NOT NULL CHECK (invitation_revision > 0),
    audience VARCHAR(24) NOT NULL DEFAULT 'CURRENT_INVITEES'
        CHECK (audience = 'CURRENT_INVITEES'),
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (delivery_state IN ('PENDING', 'DELIVERED', 'FAILED', 'CANCELLED')),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, meeting_id, event_type, aggregate_version),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meetings (tenant_id, meeting_id) ON DELETE CASCADE,
    CHECK ((delivery_state = 'DELIVERED') = (delivered_at IS NOT NULL))
);

ALTER TABLE vm_meeting_preparations
    ADD COLUMN materials_version BIGINT NOT NULL DEFAULT 0 CHECK (materials_version >= 0);

CREATE TABLE vm_meeting_preparation_materials (
    material_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    display_name VARCHAR(240) NOT NULL CHECK (length(trim(display_name)) > 0),
    content_type VARCHAR(120) NOT NULL,
    reference_provider VARCHAR(24) NOT NULL
        CHECK (reference_provider IN ('DWP_FILES', 'SHAREPOINT', 'CONFLUENCE')),
    opaque_reference VARCHAR(160) NOT NULL
        CHECK (opaque_reference ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,159}$'),
    source_version VARCHAR(160)
        CHECK (source_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$'),
    classification VARCHAR(20) NOT NULL
        CHECK (classification IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    size_bytes BIGINT CHECK (size_bytes BETWEEN 0 AND 10737418240),
    content_sha256 CHAR(64) CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    retention_until TIMESTAMPTZ NOT NULL,
    access_verification_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVALIDATION'
        CHECK (access_verification_state = 'PENDING_REVALIDATION'),
    last_verified_at TIMESTAMPTZ,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle_state IN ('ACTIVE', 'REMOVED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    UNIQUE (tenant_id, meeting_id, material_id),
    UNIQUE (tenant_id, meeting_id, reference_provider, opaque_reference),
    FOREIGN KEY (tenant_id, meeting_id)
        REFERENCES vm_meeting_preparations (tenant_id, meeting_id) ON DELETE CASCADE,
    CHECK (last_verified_at IS NULL)
);

CREATE TABLE vm_meeting_material_retention_state (
    worker_key VARCHAR(40) PRIMARY KEY CHECK (worker_key = 'PREPARATION_MATERIALS'),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error_code VARCHAR(40),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((last_failure_at IS NULL) = (last_error_code IS NULL))
);

INSERT INTO vm_meeting_material_retention_state (worker_key)
VALUES ('PREPARATION_MATERIALS');

CREATE TABLE vm_meeting_material_retention_evidence (
    execution_id UUID PRIMARY KEY,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    deleted_count INTEGER NOT NULL CHECK (deleted_count >= 0),
    error_code VARCHAR(40),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((outcome = 'FAILED') = (error_code IS NOT NULL))
);

ALTER TABLE vm_meeting_preparation_commands
    DROP CONSTRAINT vm_meeting_preparation_commands_operation_check,
    ADD CONSTRAINT vm_meeting_preparation_commands_operation_check
        CHECK (operation IN ('AGENDA_REPLACE', 'INVITATION_RESPOND',
                             'MATERIAL_REGISTER', 'MATERIAL_REMOVE'));

CREATE INDEX ix_vm_meeting_occurrence_meeting
    ON vm_meeting_occurrences (tenant_id, meeting_id);
CREATE INDEX ix_vm_meeting_invitation_delivery
    ON vm_meeting_invitation_outbox (delivery_state, available_at, tenant_id);
CREATE INDEX ix_vm_meeting_preparation_materials_active
    ON vm_meeting_preparation_materials (tenant_id, meeting_id, created_at)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON TABLE vm_meeting_series IS
    'Structured recurrence rule; wall-clock anchor and IANA zone preserve deterministic DST projection.';
COMMENT ON TABLE vm_meeting_schedule_commands IS
    'Content-free idempotency evidence plus the canonical safe schedule projection returned to lost-response retries.';
COMMENT ON TABLE vm_meeting_invitation_outbox IS
    'Payload-free delivery intent. A trusted dispatcher resolves current invitees under tenant ACL.';
COMMENT ON TABLE vm_meeting_preparation_materials IS
    'Metadata and opaque governed-storage reference only. References remain pending revalidation until an owner adapter exists; never arbitrary file bytes, URL credentials or tokens.';
COMMENT ON TABLE vm_meeting_material_retention_evidence IS
    'Payload-free bounded purge evidence; it stores counts and stable error categories only.';
