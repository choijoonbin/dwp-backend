ALTER TABLE usr_saved_view_transfer_batches
    ADD COLUMN source_owner_display_name VARCHAR(240),
    ADD COLUMN target_owner_display_name VARCHAR(240);

ALTER TABLE usr_saved_views
    DROP CONSTRAINT ck_usr_saved_views_lifecycle,
    ADD CONSTRAINT ck_usr_saved_views_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'ORPHANED', 'ARCHIVED')
            AND ((lifecycle_state = 'ACTIVE' AND owner_user_id IS NOT NULL)
                OR (lifecycle_state = 'ORPHANED' AND owner_user_id IS NULL)
                OR lifecycle_state = 'ARCHIVED'));

COMMENT ON COLUMN usr_saved_view_transfer_batches.source_owner_display_name IS
    'Immutable source-owner display snapshot captured when the custody decision executes.';
COMMENT ON COLUMN usr_saved_view_transfer_batches.target_owner_display_name IS
    'Immutable target-owner display snapshot captured when the custody decision executes.';

CREATE TABLE usr_saved_view_lifecycle_commands (
    command_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    saved_view_id UUID NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    action VARCHAR(24) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    target_owner_user_id BIGINT,
    target_owner_display_name VARCHAR(240),
    previous_lifecycle_state VARCHAR(20) NOT NULL,
    new_lifecycle_state VARCHAR(20) NOT NULL,
    previous_retention_until TIMESTAMPTZ,
    next_retention_until TIMESTAMPTZ,
    reason_code VARCHAR(40) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    previous_version BIGINT NOT NULL,
    resulting_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    CONSTRAINT uk_usr_saved_view_lifecycle_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_usr_saved_view_lifecycle_view
        FOREIGN KEY (tenant_id, saved_view_id)
        REFERENCES usr_saved_views(tenant_id, saved_view_id),
    CONSTRAINT ck_usr_saved_view_lifecycle_action
        CHECK (action IN ('REASSIGN', 'EXTEND_RETENTION', 'ARCHIVE_NOW')),
    CONSTRAINT ck_usr_saved_view_lifecycle_transition
        CHECK ((action = 'REASSIGN'
                    AND target_owner_user_id IS NOT NULL
                    AND new_lifecycle_state = 'ACTIVE'
                    AND next_retention_until IS NULL)
            OR (action = 'EXTEND_RETENTION'
                    AND target_owner_user_id IS NULL
                    AND new_lifecycle_state = 'ORPHANED'
                    AND next_retention_until IS NOT NULL)
            OR (action = 'ARCHIVE_NOW'
                    AND target_owner_user_id IS NULL
                    AND new_lifecycle_state = 'ARCHIVED'
                    AND next_retention_until IS NULL)),
    CONSTRAINT ck_usr_saved_view_lifecycle_origin
        CHECK (previous_lifecycle_state = 'ORPHANED'
            AND previous_retention_until IS NOT NULL),
    CONSTRAINT ck_usr_saved_view_lifecycle_reason
        CHECK (reason_code IN ('OFFBOARDING', 'TEAM_REORGANIZATION', 'OWNER_CORRECTION')
            AND LENGTH(BTRIM(reason)) BETWEEN 10 AND 1000
            AND LENGTH(BTRIM(source_reference)) BETWEEN 3 AND 240),
    CONSTRAINT ck_usr_saved_view_lifecycle_versions
        CHECK (previous_version >= 0 AND resulting_version = previous_version + 1)
);

CREATE INDEX idx_usr_saved_view_lifecycle_view
    ON usr_saved_view_lifecycle_commands (tenant_id, saved_view_id, created_at DESC);

CREATE TRIGGER trg_saved_view_lifecycle_command_immutable
BEFORE UPDATE OR DELETE ON usr_saved_view_lifecycle_commands
FOR EACH ROW EXECUTE FUNCTION prevent_saved_view_transfer_history_mutation();

COMMENT ON TABLE usr_saved_view_lifecycle_commands IS
    'Append-only, idempotent evidence for administrator recovery and early archival of retained saved views.';
