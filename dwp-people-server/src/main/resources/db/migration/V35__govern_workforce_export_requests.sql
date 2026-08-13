CREATE TABLE ppl_workforce_export_datasets (
    dataset_key VARCHAR(80) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    required_field_groups VARCHAR(40)[] NOT NULL,
    allowed_selection_keys VARCHAR(80)[] NOT NULL DEFAULT '{}',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_workforce_export_dataset_key
        CHECK (dataset_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_workforce_export_dataset_fields
        CHECK (required_field_groups <@ ARRAY[
            'DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT', 'JOB_GRADE'
        ]::VARCHAR[] AND cardinality(required_field_groups) > 0),
    CONSTRAINT ck_workforce_export_dataset_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

INSERT INTO ppl_workforce_export_datasets (
    dataset_key, name, description, required_field_groups,
    allowed_selection_keys)
VALUES
    ('ORGANIZATION_INTELLIGENCE', 'Organization intelligence',
     'Organization health, comparison, and data quality decision evidence.',
     ARRAY['DIRECTORY']::VARCHAR[], ARRAY['view', 'asOf']::VARCHAR[]),
    ('WORKFORCE_DIRECTORY', 'Workforce directory',
     'Governed workforce directory rows within the resolved population.',
     ARRAY['DIRECTORY', 'EMPLOYMENT']::VARCHAR[],
     ARRAY['query', 'status', 'organization', 'location', 'grade', 'role', 'asOf']::VARCHAR[]),
    ('ASSIGNMENT_REGISTER', 'Assignment register',
     'Effective-dated worker and assignment records.',
     ARRAY['DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT']::VARCHAR[],
     ARRAY['organization', 'status', 'asOf']::VARCHAR[]);

CREATE TABLE ppl_workforce_export_requests (
    workforce_export_request_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    dataset_key VARCHAR(80) NOT NULL,
    selection JSONB NOT NULL DEFAULT '{}'::jsonb,
    population_type VARCHAR(24) NOT NULL,
    organization_ids UUID[] NOT NULL DEFAULT '{}',
    field_groups VARCHAR(40)[] NOT NULL,
    export_format VARCHAR(16) NOT NULL,
    masking_profile VARCHAR(80) NOT NULL,
    watermark_text VARCHAR(500) NOT NULL,
    recipient_reference VARCHAR(320) NOT NULL,
    purpose VARCHAR(1000) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    execution_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    blockers VARCHAR(80)[] NOT NULL DEFAULT '{}',
    policy_snapshot JSONB NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    artifact_reference VARCHAR(1000),
    artifact_sha256 CHAR(64),
    artifact_size_bytes BIGINT,
    artifact_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    cancellation_requested_at TIMESTAMPTZ,
    cancellation_requested_by BIGINT,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workforce_export_idempotency
        UNIQUE (tenant_id, requested_by, idempotency_key),
    CONSTRAINT fk_workforce_export_dataset
        FOREIGN KEY (dataset_key) REFERENCES ppl_workforce_export_datasets(dataset_key),
    CONSTRAINT ck_workforce_export_selection
        CHECK (jsonb_typeof(selection) = 'object'),
    CONSTRAINT ck_workforce_export_population
        CHECK (population_type IN ('TENANT', 'ORGANIZATION_SET')),
    CONSTRAINT ck_workforce_export_population_ids
        CHECK ((population_type = 'TENANT' AND cardinality(organization_ids) = 0)
            OR (population_type = 'ORGANIZATION_SET' AND cardinality(organization_ids) > 0)),
    CONSTRAINT ck_workforce_export_fields
        CHECK (field_groups <@ ARRAY[
            'DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT', 'JOB_GRADE'
        ]::VARCHAR[] AND cardinality(field_groups) > 0),
    CONSTRAINT ck_workforce_export_format CHECK (export_format IN ('CSV')),
    CONSTRAINT ck_workforce_export_state CHECK (lifecycle_state IN (
        'BLOCKED_PENDING_APPROVAL', 'QUEUED', 'RUNNING', 'RETRY_WAIT',
        'CANCEL_REQUESTED', 'CANCELLED', 'COMPLETED', 'FAILED', 'EXPIRED'
    )),
    CONSTRAINT ck_workforce_export_blocked_state
        CHECK ((lifecycle_state = 'BLOCKED_PENDING_APPROVAL'
                AND execution_enabled = FALSE AND cardinality(blockers) > 0)
            OR lifecycle_state <> 'BLOCKED_PENDING_APPROVAL'),
    CONSTRAINT ck_workforce_export_snapshot
        CHECK (jsonb_typeof(policy_snapshot) = 'object'),
    CONSTRAINT ck_workforce_export_request_hash
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workforce_export_artifact_hash
        CHECK (artifact_sha256 IS NULL OR artifact_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workforce_export_artifact_size
        CHECK (artifact_size_bytes IS NULL OR artifact_size_bytes >= 0),
    CONSTRAINT ck_workforce_export_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_workforce_export_completed_artifact CHECK (
        lifecycle_state <> 'COMPLETED'
        OR (artifact_reference IS NOT NULL AND artifact_sha256 IS NOT NULL
            AND artifact_size_bytes IS NOT NULL AND artifact_expires_at IS NOT NULL
            AND completed_at IS NOT NULL)),
    CONSTRAINT ck_workforce_export_artifact_expiry
        CHECK (artifact_expires_at IS NULL OR artifact_expires_at > created_at)
);

CREATE INDEX idx_workforce_export_request_owner
    ON ppl_workforce_export_requests (tenant_id, requested_by, created_at DESC);

CREATE INDEX idx_workforce_export_request_worker
    ON ppl_workforce_export_requests (lifecycle_state, next_attempt_at, created_at)
    WHERE execution_enabled = TRUE
      AND lifecycle_state IN ('QUEUED', 'RETRY_WAIT');

CREATE TABLE ppl_workforce_export_attempt_events (
    workforce_export_attempt_event_id UUID PRIMARY KEY,
    workforce_export_request_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    attempt_number INTEGER NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    worker_reference VARCHAR(240),
    failure_code VARCHAR(120),
    redacted_failure_message VARCHAR(1000),
    artifact_sha256 CHAR(64),
    artifact_size_bytes BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workforce_export_attempt_request
        FOREIGN KEY (workforce_export_request_id)
        REFERENCES ppl_workforce_export_requests(workforce_export_request_id),
    CONSTRAINT ck_workforce_export_attempt_number CHECK (attempt_number >= 0),
    CONSTRAINT ck_workforce_export_attempt_event CHECK (event_type IN (
        'BLOCKED', 'QUEUED', 'CLAIMED', 'RETRY_SCHEDULED', 'FAILED', 'COMPLETED', 'CANCELLED'
    )),
    CONSTRAINT ck_workforce_export_attempt_hash
        CHECK (artifact_sha256 IS NULL OR artifact_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workforce_export_attempt_size
        CHECK (artifact_size_bytes IS NULL OR artifact_size_bytes >= 0)
);

CREATE INDEX idx_workforce_export_attempt_timeline
    ON ppl_workforce_export_attempt_events (
        tenant_id, workforce_export_request_id, occurred_at, attempt_number);

CREATE OR REPLACE FUNCTION reject_workforce_export_attempt_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'workforce export attempt events are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workforce_export_attempt_append_only
BEFORE UPDATE OR DELETE ON ppl_workforce_export_attempt_events
FOR EACH ROW EXECUTE FUNCTION reject_workforce_export_attempt_mutation();

COMMENT ON TABLE ppl_workforce_export_requests IS
    'Tenant-scoped governed workforce export request with immutable policy snapshot and artifact integrity evidence.';

COMMENT ON TABLE ppl_workforce_export_attempt_events IS
    'Append-only worker attempt timeline for retry, cancellation, and artifact integrity evidence.';
