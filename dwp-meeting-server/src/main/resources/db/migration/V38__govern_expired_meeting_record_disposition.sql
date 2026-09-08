-- Independent controls/evidence intentionally have no FK to the disposable record.
-- No existing records are authorized; the application worker is disabled by default.
CREATE TABLE vm_meeting_record_dispositions (
    tenant_id BIGINT NOT NULL, meeting_id UUID NOT NULL,
    meeting_version BIGINT NOT NULL CHECK (meeting_version >= 0),
    policy_version BIGINT NOT NULL CHECK (policy_version >= 0),
    retention_until TIMESTAMPTZ NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    purge_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    control_version BIGINT NOT NULL CHECK (control_version > 0),
    authorization_audit_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    last_evaluated_at TIMESTAMPTZ,
    purged_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, meeting_id),
    CHECK (NOT (legal_hold AND purge_authorized)),
    CHECK (purged_at IS NULL OR purge_authorized)
);
CREATE INDEX ix_vm_record_disposition_pending
    ON vm_meeting_record_dispositions (retention_until, tenant_id, meeting_id)
    WHERE purge_authorized AND NOT legal_hold AND purged_at IS NULL;

CREATE TABLE vm_meeting_record_deletion_evidence (
    deletion_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL, meeting_id UUID NOT NULL,
    control_version BIGINT NOT NULL,
    meeting_version BIGINT NOT NULL, policy_version BIGINT NOT NULL,
    retention_until TIMESTAMPTZ NOT NULL,
    authorization_audit_id UUID NOT NULL, purge_audit_id UUID NOT NULL,
    fence_token UUID NOT NULL, worker_id VARCHAR(120) NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    receipt_manifest JSONB NOT NULL CHECK (jsonb_typeof(receipt_manifest) = 'object'),
    deletion_reason VARCHAR(32) NOT NULL DEFAULT 'RETENTION_EXPIRED'
        CHECK (deletion_reason = 'RETENTION_EXPIRED'),
    UNIQUE (tenant_id, meeting_id),
    CHECK (worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$')
);

CREATE TABLE vm_meeting_record_retention_health (
    health_key VARCHAR(32) PRIMARY KEY CHECK (health_key = 'RECORD_RETENTION'),
    last_attempt_at TIMESTAMPTZ, last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ, last_failure_code VARCHAR(64),
    overdue_remaining BOOLEAN NOT NULL DEFAULT FALSE,
    active_fence UUID, active_worker_id VARCHAR(120), active_lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((last_failure_at IS NULL AND last_failure_code IS NULL)
        OR (last_failure_at IS NOT NULL AND last_failure_code='RECORD_RETENTION_PURGE_FAILED')),
    CHECK (active_worker_id IS NULL OR active_worker_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'),
    CHECK ((active_fence IS NULL AND active_worker_id IS NULL AND active_lease_expires_at IS NULL)
        OR (active_fence IS NOT NULL AND active_worker_id IS NOT NULL AND active_lease_expires_at IS NOT NULL))
);
INSERT INTO vm_meeting_record_retention_health (health_key) VALUES ('RECORD_RETENTION');
