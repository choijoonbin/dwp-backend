CREATE TABLE apr_incidents (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    incident_id UUID NOT NULL,
    incident_key VARCHAR(100) NOT NULL,
    title VARCHAR(240) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    source_kind VARCHAR(32) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    opened_by BIGINT NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, resource_set_key, incident_id),
    UNIQUE (tenant_id, resource_set_key, incident_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (incident_key ~ '^[A-Z][A-Z0-9_.-]{2,99}$'),
    CHECK (btrim(title) <> ''),
    CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CHECK (status IN ('OPEN', 'INVESTIGATING', 'MITIGATING', 'MONITORING', 'RESOLVED', 'CLOSED')),
    CHECK (source_kind IN ('DELIVERY', 'CONNECTOR', 'POLICY', 'WORKFLOW', 'MANUAL')),
    CHECK (btrim(source_reference) <> ''),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (opened_by > 0 AND updated_by > 0),
    CHECK ((status IN ('RESOLVED', 'CLOSED')) = (resolved_at IS NOT NULL))
);

CREATE TABLE apr_incident_timeline (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    incident_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status_before VARCHAR(24),
    status_after VARCHAR(24),
    summary VARCHAR(1000) NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, resource_set_key, incident_id, sequence),
    FOREIGN KEY (tenant_id, resource_set_key, incident_id)
        REFERENCES apr_incidents(tenant_id, resource_set_key, incident_id),
    CHECK (sequence BETWEEN 1 AND 9007199254740991),
    CHECK (event_type IN (
        'OPENED', 'STATUS_CHANGED', 'DIAGNOSTIC_ADDED', 'PLAN_CREATED',
        'PLAN_VALIDATED', 'STAGE_STARTED', 'STAGE_COMPLETED',
        'RECONCILED', 'POSTMORTEM_RECORDED')),
    CHECK (btrim(summary) <> ''),
    CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (actor_user_id > 0)
);

CREATE TABLE apr_incident_diagnostics (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    incident_id UUID NOT NULL,
    diagnostic_id UUID NOT NULL,
    diagnostic_kind VARCHAR(32) NOT NULL,
    redacted_payload JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    source_revision VARCHAR(240) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, incident_id, diagnostic_id),
    FOREIGN KEY (tenant_id, resource_set_key, incident_id)
        REFERENCES apr_incidents(tenant_id, resource_set_key, incident_id),
    CHECK (diagnostic_kind IN ('SUMMARY', 'DELIVERY', 'CONNECTOR', 'TRACE', 'METRIC')),
    CHECK (jsonb_typeof(redacted_payload) = 'object'),
    CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (btrim(source_revision) <> '' AND recorded_by > 0)
);

CREATE TABLE apr_incident_recovery_plans (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    incident_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    plan_kind VARCHAR(24) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    target_snapshot JSONB NOT NULL,
    target_sha256 CHAR(64) NOT NULL,
    dry_run_result JSONB,
    dry_run_evidence_sha256 CHAR(64),
    version BIGINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, resource_set_key, incident_id, plan_id),
    UNIQUE (tenant_id, resource_set_key, plan_id),
    FOREIGN KEY (tenant_id, resource_set_key, incident_id)
        REFERENCES apr_incidents(tenant_id, resource_set_key, incident_id),
    CHECK (plan_kind IN ('REPLAY', 'RECONCILE', 'REASSIGN', 'MIXED')),
    CHECK (state IN (
        'DRAFT', 'VALIDATED', 'BLOCKED', 'EXECUTING', 'PARTIAL',
        'COMPLETED', 'FAILED', 'UNKNOWN_REMOTE_OUTCOME')),
    CHECK (jsonb_typeof(target_snapshot) = 'object'),
    CHECK (target_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((dry_run_result IS NULL AND dry_run_evidence_sha256 IS NULL)
        OR (dry_run_result IS NOT NULL AND dry_run_evidence_sha256 IS NOT NULL
            AND jsonb_typeof(dry_run_result) = 'object'
            AND dry_run_evidence_sha256 ~ '^[0-9a-f]{64}$')),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (created_by > 0 AND updated_by > 0),
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE TABLE apr_incident_recovery_stages (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    incident_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    stage_number SMALLINT NOT NULL,
    action_kind VARCHAR(24) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    expected_target_version BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    execution_key VARCHAR(120),
    attempt SMALLINT NOT NULL DEFAULT 0,
    result JSONB,
    evidence_sha256 CHAR(64),
    receipt_issuer VARCHAR(160),
    receipt_key_id VARCHAR(120),
    receipt_verification_reference VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 1,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (
        tenant_id, resource_set_key, incident_id, plan_id, stage_number),
    UNIQUE (tenant_id, resource_set_key, plan_id, stage_number),
    FOREIGN KEY (tenant_id, resource_set_key, incident_id, plan_id)
        REFERENCES apr_incident_recovery_plans(
            tenant_id, resource_set_key, incident_id, plan_id),
    CHECK (stage_number BETWEEN 1 AND 100),
    CHECK (action_kind IN ('REPLAY', 'RECONCILE', 'REASSIGN', 'VERIFY')),
    CHECK (target_type IN ('OUTBOX_EVENT', 'APPROVAL_TASK', 'CONNECTOR', 'POLICY')),
    CHECK (expected_target_version BETWEEN 0 AND 9007199254740991),
    CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN_REMOTE_OUTCOME')),
    CHECK (attempt BETWEEN 0 AND 20 AND version BETWEEN 1 AND 9007199254740991),
    CHECK (execution_key IS NULL OR execution_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK ((state = 'PENDING' AND execution_key IS NULL
            AND attempt = 0 AND result IS NULL AND evidence_sha256 IS NULL
            AND receipt_issuer IS NULL AND receipt_key_id IS NULL
            AND receipt_verification_reference IS NULL
            AND started_at IS NULL AND completed_at IS NULL)
        OR (state = 'RUNNING' AND execution_key IS NOT NULL
            AND attempt > 0 AND result IS NULL AND evidence_sha256 IS NULL
            AND receipt_issuer IS NULL AND receipt_key_id IS NULL
            AND receipt_verification_reference IS NULL
            AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN_REMOTE_OUTCOME')
            AND execution_key IS NOT NULL AND attempt > 0
            AND result IS NOT NULL
            AND jsonb_typeof(result) = 'object'
            AND evidence_sha256 IS NOT NULL
            AND evidence_sha256 ~ '^[0-9a-f]{64}$'
            AND ((state = 'UNKNOWN_REMOTE_OUTCOME'
                    AND receipt_issuer IS NULL AND receipt_key_id IS NULL
                    AND receipt_verification_reference IS NULL)
                OR (receipt_issuer IS NOT NULL AND receipt_key_id IS NOT NULL
                    AND receipt_verification_reference IS NOT NULL
                    AND btrim(receipt_issuer) <> '' AND btrim(receipt_key_id) <> ''
                    AND receipt_verification_reference ~ '^verified:[0-9a-f]{64}$'))
            AND started_at IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE TABLE apr_incident_postmortems (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    incident_id UUID NOT NULL,
    postmortem_id UUID NOT NULL,
    summary VARCHAR(4000) NOT NULL,
    contributing_factors JSONB NOT NULL,
    corrective_actions JSONB NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    recorded_by BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, resource_set_key, incident_id, postmortem_id),
    UNIQUE (tenant_id, resource_set_key, incident_id),
    FOREIGN KEY (tenant_id, resource_set_key, incident_id)
        REFERENCES apr_incidents(tenant_id, resource_set_key, incident_id),
    CHECK (btrim(summary) <> ''),
    CHECK (jsonb_typeof(contributing_factors) = 'array'
        AND jsonb_typeof(corrective_actions) = 'array'),
    CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (version BETWEEN 1 AND 9007199254740991 AND recorded_by > 0)
);

CREATE TABLE apr_incident_commands (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    target_id UUID,
    command_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    result_type VARCHAR(240),
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (
        tenant_id, resource_set_key, actor_user_id, operation, idempotency_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (actor_user_id > 0),
    CHECK (operation IN (
        'OPEN_INCIDENT', 'CHANGE_STATUS', 'ADD_DIAGNOSTIC',
        'CREATE_PLAN', 'RECORD_DRY_RUN', 'START_STAGE',
        'COMPLETE_STAGE', 'RECONCILE_PLAN', 'RECORD_POSTMORTEM')),
    CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (command_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('UNKNOWN', 'SUCCEEDED')),
    CHECK ((status = 'UNKNOWN' AND result_type IS NULL
        AND result_payload IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND result_type IS NOT NULL
        AND btrim(result_type) <> '' AND result_payload IS NOT NULL
        AND jsonb_typeof(result_payload) = 'object' AND completed_at IS NOT NULL))
);

CREATE INDEX idx_apr_incident_timeline_order
    ON apr_incident_timeline (
        tenant_id, resource_set_key, incident_id, occurred_at, sequence);

CREATE INDEX idx_apr_incident_open
    ON apr_incidents (
        tenant_id, resource_set_key, severity, updated_at DESC, incident_id)
    WHERE status NOT IN ('RESOLVED', 'CLOSED');

CREATE FUNCTION reject_apr_incident_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Approval incident evidence is append-only';
END;
$$;

CREATE TRIGGER trg_apr_incident_timeline_append_only
    BEFORE UPDATE OR DELETE ON apr_incident_timeline
    FOR EACH ROW EXECUTE FUNCTION reject_apr_incident_evidence_mutation();

CREATE TRIGGER trg_apr_incident_diagnostics_append_only
    BEFORE UPDATE OR DELETE ON apr_incident_diagnostics
    FOR EACH ROW EXECUTE FUNCTION reject_apr_incident_evidence_mutation();

CREATE TRIGGER trg_apr_incident_postmortem_append_only
    BEFORE UPDATE OR DELETE ON apr_incident_postmortems
    FOR EACH ROW EXECUTE FUNCTION reject_apr_incident_evidence_mutation();

COMMENT ON TABLE apr_incident_recovery_plans IS
    'Dry-run-first Approval recovery plans; UNKNOWN remote outcomes are explicit terminal evidence.';
COMMENT ON TABLE apr_incident_diagnostics IS
    'Redacted diagnostics only. Raw provider payloads and credentials are prohibited by the domain service.';
