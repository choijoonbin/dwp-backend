ALTER TABLE int_connector_instances
    ADD COLUMN last_attempted_sync_at TIMESTAMPTZ,
    ADD COLUMN last_error_code VARCHAR(80),
    ADD COLUMN consecutive_failure_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_scheduled_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_int_connector_instances_failure_count
        CHECK (consecutive_failure_count >= 0);

CREATE UNIQUE INDEX uk_int_connector_instances_tenant_id
    ON int_connector_instances(tenant_id, connector_instance_id);

ALTER TABLE int_mapping_profiles
    ADD COLUMN mapping_sha256 CHAR(64),
    ADD COLUMN activated_at TIMESTAMPTZ,
    ADD COLUMN activated_by BIGINT;

UPDATE int_mapping_profiles
   SET mapping_sha256 = encode(sha256(convert_to(mapping_definition::text, 'UTF8')), 'hex')
 WHERE mapping_sha256 IS NULL;

ALTER TABLE int_mapping_profiles
    ALTER COLUMN mapping_sha256 SET NOT NULL;

CREATE UNIQUE INDEX uk_int_mapping_profiles_tenant_id
    ON int_mapping_profiles(tenant_id, mapping_profile_id);

CREATE UNIQUE INDEX uk_int_mapping_profiles_one_active_source
    ON int_mapping_profiles(tenant_id, source_system_id)
    WHERE lifecycle_state = 'ACTIVE';

ALTER TABLE int_sync_runs
    ADD COLUMN connector_instance_id UUID,
    ADD COLUMN mapping_profile_id UUID,
    ADD COLUMN retry_of_sync_run_id UUID,
    ADD COLUMN page_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN unchanged_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN failure_code VARCHAR(80),
    ADD COLUMN redacted_failure_message VARCHAR(1000),
    ADD CONSTRAINT fk_int_sync_runs_connector
        FOREIGN KEY (tenant_id, connector_instance_id)
        REFERENCES int_connector_instances(tenant_id, connector_instance_id),
    ADD CONSTRAINT fk_int_sync_runs_mapping
        FOREIGN KEY (tenant_id, mapping_profile_id)
        REFERENCES int_mapping_profiles(tenant_id, mapping_profile_id),
    ADD CONSTRAINT fk_int_sync_runs_retry
        FOREIGN KEY (tenant_id, retry_of_sync_run_id)
        REFERENCES int_sync_runs(tenant_id, sync_run_id),
    ADD CONSTRAINT ck_int_sync_runs_page_count CHECK (page_count >= 0),
    ADD CONSTRAINT ck_int_sync_runs_unchanged_count CHECK (unchanged_count >= 0);

CREATE INDEX idx_int_sync_runs_connector_created
    ON int_sync_runs(tenant_id, connector_instance_id, created_at DESC);

CREATE TABLE int_connector_cursors (
    connector_cursor_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connector_instance_id UUID NOT NULL,
    cursor_type VARCHAR(30) NOT NULL DEFAULT 'WATERMARK',
    committed_cursor VARCHAR(2000),
    committed_at TIMESTAMPTZ,
    sync_run_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_int_connector_cursors_connector
        FOREIGN KEY (tenant_id, connector_instance_id)
        REFERENCES int_connector_instances(tenant_id, connector_instance_id),
    CONSTRAINT fk_int_connector_cursors_run
        FOREIGN KEY (tenant_id, sync_run_id)
        REFERENCES int_sync_runs(tenant_id, sync_run_id),
    CONSTRAINT uk_int_connector_cursors_connector
        UNIQUE (tenant_id, connector_instance_id, cursor_type),
    CONSTRAINT ck_int_connector_cursors_type
        CHECK (cursor_type IN ('WATERMARK', 'OPAQUE_TOKEN', 'OFFSET'))
);

ALTER TABLE int_sync_errors
    ADD COLUMN lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_retry_at TIMESTAMPTZ,
    ADD COLUMN resolved_at TIMESTAMPTZ,
    ADD COLUMN resolved_by BIGINT,
    ADD COLUMN resolution_note VARCHAR(1000),
    ADD CONSTRAINT ck_int_sync_errors_state
        CHECK (lifecycle_state IN ('OPEN', 'RETRYING', 'RESOLVED', 'IGNORED')),
    ADD CONSTRAINT ck_int_sync_errors_retry_count CHECK (retry_count >= 0);

CREATE INDEX idx_int_sync_errors_work_queue
    ON int_sync_errors(tenant_id, lifecycle_state, retryable, occurred_at DESC);

CREATE TABLE int_reconciliation_runs (
    reconciliation_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    connector_instance_id UUID NOT NULL,
    sync_run_id UUID,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    checked_count BIGINT NOT NULL DEFAULT 0,
    issue_count BIGINT NOT NULL DEFAULT 0,
    critical_count BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    created_by BIGINT,
    CONSTRAINT uk_int_reconciliation_runs_tenant_id
        UNIQUE (tenant_id, reconciliation_run_id),
    CONSTRAINT fk_int_reconciliation_runs_connector
        FOREIGN KEY (tenant_id, connector_instance_id)
        REFERENCES int_connector_instances(tenant_id, connector_instance_id),
    CONSTRAINT fk_int_reconciliation_runs_sync
        FOREIGN KEY (tenant_id, sync_run_id)
        REFERENCES int_sync_runs(tenant_id, sync_run_id),
    CONSTRAINT ck_int_reconciliation_runs_state
        CHECK (lifecycle_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_int_reconciliation_runs_counts
        CHECK (checked_count >= 0 AND issue_count >= 0 AND critical_count >= 0)
);

CREATE TABLE int_reconciliation_issues (
    reconciliation_issue_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    reconciliation_run_id UUID NOT NULL,
    connector_instance_id UUID NOT NULL,
    issue_code VARCHAR(80) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    internal_key VARCHAR(160),
    external_id VARCHAR(255),
    redacted_summary VARCHAR(1000) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by BIGINT,
    resolution_note VARCHAR(1000),
    CONSTRAINT fk_int_reconciliation_issues_run
        FOREIGN KEY (tenant_id, reconciliation_run_id)
        REFERENCES int_reconciliation_runs(tenant_id, reconciliation_run_id),
    CONSTRAINT fk_int_reconciliation_issues_connector
        FOREIGN KEY (tenant_id, connector_instance_id)
        REFERENCES int_connector_instances(tenant_id, connector_instance_id),
    CONSTRAINT ck_int_reconciliation_issues_severity
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_int_reconciliation_issues_state
        CHECK (lifecycle_state IN ('OPEN', 'RESOLVED', 'ACCEPTED'))
);

CREATE INDEX idx_int_reconciliation_issues_queue
    ON int_reconciliation_issues(tenant_id, lifecycle_state, severity, first_detected_at DESC);

COMMENT ON TABLE int_connector_cursors IS
    'Opaque or timestamp cursors committed only after a successful HRIS projection transaction.';
COMMENT ON TABLE int_reconciliation_issues IS
    'Human-reviewable HRIS-to-workforce drift; destructive remediation is never automatic.';
