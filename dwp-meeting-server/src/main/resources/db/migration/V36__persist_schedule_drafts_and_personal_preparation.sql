-- Persisted schedule drafts are private, user-owned workspace content. They store
-- no guest email, device identifier, consent, access token, or policy outcome.
CREATE TABLE vm_meeting_schedule_drafts (
    draft_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    title VARCHAR(240),
    agenda VARCHAR(8000),
    starts_at TIMESTAMPTZ,
    duration_minutes INTEGER CHECK (duration_minutes BETWEEN 5 AND 1440),
    time_zone VARCHAR(80),
    access_scope VARCHAR(20) CHECK (access_scope IN ('INTERNAL', 'INVITED')),
    waiting_room_enabled BOOLEAN,
    allow_join_before_host BOOLEAN,
    recurrence_frequency VARCHAR(16)
        CHECK (recurrence_frequency IN ('NONE', 'WEEKLY', 'MONTHLY')),
    recurrence_interval INTEGER CHECK (recurrence_interval BETWEEN 1 AND 12),
    recurrence_occurrence_count INTEGER CHECK (recurrence_occurrence_count BETWEEN 2 AND 52),
    source_template_id UUID,
    source_template_version BIGINT CHECK (source_template_version >= 0),
    last_step VARCHAR(24) NOT NULL DEFAULT 'DETAILS'
        CHECK (last_step IN ('DETAILS', 'SCHEDULE', 'RECURRENCE', 'REVIEW')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    retention_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    UNIQUE (tenant_id, owner_user_id),
    UNIQUE (tenant_id, draft_id),
    FOREIGN KEY (tenant_id, owner_user_id)
        REFERENCES vm_people_snapshot (tenant_id, user_id),
    FOREIGN KEY (tenant_id, source_template_id, source_template_version)
        REFERENCES vm_meeting_template_revisions (tenant_id, template_id, revision),
    CHECK ((source_template_id IS NULL) = (source_template_version IS NULL)),
    CHECK ((recurrence_frequency IS NULL
            AND recurrence_interval IS NULL
            AND recurrence_occurrence_count IS NULL)
        OR (recurrence_frequency = 'NONE'
            AND recurrence_interval IS NOT NULL
            AND recurrence_occurrence_count IS NOT NULL)
        OR (recurrence_frequency IN ('WEEKLY', 'MONTHLY')
            AND recurrence_interval IS NOT NULL
            AND recurrence_occurrence_count IS NOT NULL))
);

CREATE TABLE vm_meeting_schedule_draft_participants (
    tenant_id BIGINT NOT NULL,
    draft_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 199),
    user_id BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, draft_id, position),
    UNIQUE (tenant_id, draft_id, user_id),
    FOREIGN KEY (tenant_id, draft_id)
        REFERENCES vm_meeting_schedule_drafts (tenant_id, draft_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, user_id)
        REFERENCES vm_people_snapshot (tenant_id, user_id)
);

CREATE TABLE vm_meeting_schedule_draft_agenda_items (
    tenant_id BIGINT NOT NULL,
    draft_id UUID NOT NULL,
    item_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 49),
    title VARCHAR(240),
    objective VARCHAR(2000),
    owner_user_id BIGINT,
    planned_minutes INTEGER CHECK (planned_minutes BETWEEN 1 AND 1440),
    PRIMARY KEY (tenant_id, draft_id, item_id),
    UNIQUE (tenant_id, draft_id, position),
    FOREIGN KEY (tenant_id, draft_id)
        REFERENCES vm_meeting_schedule_drafts (tenant_id, draft_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, owner_user_id)
        REFERENCES vm_people_snapshot (tenant_id, user_id)
);

-- Draft receipts are bounded with the private draft retention window. Only an
-- opaque result identifier/version and request digest are retained.
CREATE TABLE vm_meeting_schedule_draft_commands (
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    operation VARCHAR(24) NOT NULL
        CHECK (operation IN ('DRAFT_SAVE', 'DRAFT_DISCARD', 'DRAFT_COMMIT')),
    idempotency_key VARCHAR(160) NOT NULL,
    request_sha256 CHAR(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    result_type VARCHAR(16) NOT NULL CHECK (result_type IN ('DRAFT', 'DISCARDED', 'MEETING')),
    result_id UUID NOT NULL,
    result_version BIGINT NOT NULL CHECK (result_version >= 0),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retention_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, owner_user_id, operation, idempotency_key),
    FOREIGN KEY (tenant_id, owner_user_id)
        REFERENCES vm_people_snapshot (tenant_id, user_id)
);

CREATE TABLE vm_meeting_schedule_draft_retention_health (
    health_key VARCHAR(32) PRIMARY KEY CHECK (health_key = 'SCHEDULE_DRAFTS'),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_failure_code VARCHAR(48),
    overdue_remaining BOOLEAN NOT NULL DEFAULT FALSE,
    active_fence UUID,
    active_worker_id VARCHAR(120),
    active_lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((last_failure_at IS NULL AND last_failure_code IS NULL)
        OR (last_failure_at IS NOT NULL
            AND last_failure_code ~ '^[A-Z][A-Z0-9_]{2,47}$')),
    CHECK (active_worker_id IS NULL
        OR active_worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'),
    CHECK ((active_fence IS NULL AND active_worker_id IS NULL
            AND active_lease_expires_at IS NULL)
        OR (active_fence IS NOT NULL AND active_worker_id IS NOT NULL
            AND active_lease_expires_at IS NOT NULL))
);

INSERT INTO vm_meeting_schedule_draft_retention_health (health_key)
VALUES ('SCHEDULE_DRAFTS');

CREATE TABLE vm_meeting_schedule_draft_retention_evidence (
    execution_id UUID PRIMARY KEY,
    deleted_draft_count INTEGER NOT NULL CHECK (deleted_draft_count >= 0),
    deleted_receipt_count INTEGER NOT NULL CHECK (deleted_receipt_count >= 0),
    fence_token UUID NOT NULL,
    worker_id VARCHAR(120) NOT NULL
        CHECK (worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Personal preparation is visible only to the current participant. There is no
-- host/admin aggregate and no free-text field.
CREATE TABLE vm_meeting_personal_preparations (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    agenda_version BIGINT NOT NULL CHECK (agenda_version >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, meeting_id, participant_id),
    FOREIGN KEY (tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_participants (tenant_id, meeting_id, participant_id)
        ON DELETE CASCADE
);

CREATE TABLE vm_meeting_personal_preparation_items (
    tenant_id BIGINT NOT NULL,
    meeting_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    agenda_item_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, meeting_id, participant_id, agenda_item_id),
    FOREIGN KEY (tenant_id, meeting_id, participant_id)
        REFERENCES vm_meeting_personal_preparations
            (tenant_id, meeting_id, participant_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, meeting_id, agenda_item_id)
        REFERENCES vm_meeting_agenda_items (tenant_id, meeting_id, item_id)
        ON DELETE CASCADE
);

ALTER TABLE vm_meeting_preparation_commands
    DROP CONSTRAINT vm_meeting_preparation_commands_operation_check,
    ADD CONSTRAINT vm_meeting_preparation_commands_operation_check
        CHECK (operation IN ('AGENDA_REPLACE', 'INVITATION_RESPOND',
                             'MATERIAL_REGISTER', 'MATERIAL_REMOVE',
                             'PREPARATION_CHECK'));

CREATE INDEX ix_vm_meeting_schedule_draft_retention
    ON vm_meeting_schedule_drafts (retention_until, draft_id);
CREATE INDEX ix_vm_meeting_schedule_draft_command_retention
    ON vm_meeting_schedule_draft_commands (retention_until, owner_user_id);
CREATE INDEX ix_vm_meeting_preparation_material_retention
    ON vm_meeting_preparation_materials (retention_until, material_id);

COMMENT ON TABLE vm_meeting_schedule_drafts IS
    'Private expiring user schedule draft. Guest PII, device IDs, consent, tokens and provider state are prohibited.';
COMMENT ON TABLE vm_meeting_schedule_draft_retention_evidence IS
    'Content-free evidence for physical deletion of expired draft content and bounded receipts.';
COMMENT ON TABLE vm_meeting_personal_preparations IS
    'Self-only preparation state; never project another participant or aggregate completion.';
