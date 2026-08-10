CREATE TABLE sys_audit_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(255),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_audit_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_sys_audit_outbox_status
        CHECK (status IN ('PENDING', 'SENDING', 'FAILED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_sys_audit_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_sys_audit_outbox_delivery
    ON sys_audit_outbox(status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'SENDING');
CREATE INDEX idx_sys_audit_outbox_published
    ON sys_audit_outbox(published_at)
    WHERE status = 'PUBLISHED';

CREATE TABLE sys_audit_events (
    event_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_version VARCHAR(20) NOT NULL,
    tenant_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    action VARCHAR(160) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    risk_score SMALLINT NOT NULL DEFAULT 0,
    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(160),
    actor_principal VARCHAR(320),
    actor_display_name VARCHAR(240),
    actor_roles TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    source_service VARCHAR(120) NOT NULL,
    source_module VARCHAR(120) NOT NULL,
    source_instance VARCHAR(160),
    environment VARCHAR(40) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id VARCHAR(240) NOT NULL,
    target_display_name VARCHAR(320),
    reason VARCHAR(1000),
    correlation_id VARCHAR(128),
    trace_id CHAR(32),
    session_id_hash CHAR(64),
    client_address_hash CHAR(64),
    authentication_method VARCHAR(40),
    policy_id VARCHAR(160),
    policy_decision VARCHAR(20),
    approval_id VARCHAR(160),
    before_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    changed_fields TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    retention_class VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    record_hash CHAR(64) NOT NULL,
    PRIMARY KEY (occurred_at, event_id),
    CONSTRAINT ck_sys_audit_event_category CHECK (category IN (
        'ADMIN_CHANGE', 'AUTHENTICATION', 'AUTHORIZATION', 'DATA_ACCESS',
        'DATA_EXPORT', 'PROVISIONING', 'AI_ACTION', 'POLICY_DENIED', 'SYSTEM_EVENT')),
    CONSTRAINT ck_sys_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT ck_sys_audit_event_severity
        CHECK (severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_sys_audit_event_risk CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_sys_audit_event_actor
        CHECK (actor_type IN ('ANONYMOUS', 'USER', 'SERVICE', 'SYSTEM', 'AGENT')),
    CONSTRAINT ck_sys_audit_event_policy
        CHECK (policy_decision IS NULL OR policy_decision IN ('ALLOW', 'DENY', 'NOT_APPLICABLE')),
    CONSTRAINT ck_sys_audit_event_retention
        CHECK (retention_class IN ('STANDARD', 'EXTENDED', 'LEGAL_HOLD')),
    CONSTRAINT ck_sys_audit_event_before CHECK (jsonb_typeof(before_state) = 'object'),
    CONSTRAINT ck_sys_audit_event_after CHECK (jsonb_typeof(after_state) = 'object'),
    CONSTRAINT ck_sys_audit_event_metadata CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_sys_audit_event_trace CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_sys_audit_event_session
        CHECK (session_id_hash IS NULL OR session_id_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sys_audit_event_client
        CHECK (client_address_hash IS NULL OR client_address_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sys_audit_event_hash CHECK (record_hash ~ '^[0-9a-f]{64}$')
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE sys_audit_events IS
    'Tenant-scoped, append-only business audit evidence. Secrets and raw request payloads are prohibited.';

CREATE OR REPLACE FUNCTION sys_ensure_audit_event_partition(p_month DATE)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    month_start DATE := date_trunc('month', p_month)::DATE;
    month_end DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::DATE;
    partition_name TEXT := 'sys_audit_events_' || to_char(month_start, 'YYYYMM');
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('sys_audit_events_partition'));
    IF to_regclass(partition_name) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF sys_audit_events FOR VALUES FROM (%L) TO (%L)',
            partition_name, month_start, month_end);
    END IF;
END;
$$;

SELECT sys_ensure_audit_event_partition(CURRENT_DATE);
SELECT sys_ensure_audit_event_partition((CURRENT_DATE + INTERVAL '1 month')::DATE);
SELECT sys_ensure_audit_event_partition((CURRENT_DATE + INTERVAL '2 months')::DATE);

CREATE TABLE sys_audit_events_default
    PARTITION OF sys_audit_events DEFAULT;

CREATE INDEX idx_sys_audit_event_tenant_time
    ON sys_audit_events(tenant_id, occurred_at DESC, event_id);
CREATE INDEX idx_sys_audit_event_tenant_risk
    ON sys_audit_events(tenant_id, severity, occurred_at DESC)
    WHERE severity IN ('HIGH', 'CRITICAL');
CREATE INDEX idx_sys_audit_event_tenant_category
    ON sys_audit_events(tenant_id, category, occurred_at DESC);
CREATE INDEX idx_sys_audit_event_actor
    ON sys_audit_events(tenant_id, actor_id, occurred_at DESC);
CREATE INDEX idx_sys_audit_event_target
    ON sys_audit_events(tenant_id, target_type, target_id, occurred_at DESC);
CREATE INDEX idx_sys_audit_event_correlation
    ON sys_audit_events(tenant_id, correlation_id, occurred_at DESC)
    WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_sys_audit_event_trace
    ON sys_audit_events(trace_id, occurred_at DESC)
    WHERE trace_id IS NOT NULL;
CREATE INDEX idx_sys_audit_event_occurred_brin
    ON sys_audit_events USING BRIN(occurred_at);

CREATE OR REPLACE FUNCTION sys_reject_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('dwp.audit_retention_bypass', TRUE) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'sys_audit_events is append-only';
END;
$$;

CREATE TRIGGER trg_sys_audit_events_immutable
BEFORE UPDATE OR DELETE ON sys_audit_events
FOR EACH ROW EXECUTE FUNCTION sys_reject_audit_event_mutation();

CREATE TABLE sys_audit_cases (
    case_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number BIGSERIAL UNIQUE,
    tenant_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    description VARCHAR(4000),
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    owner_actor_id VARCHAR(160),
    resolution VARCHAR(4000),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMPTZ,
    created_by VARCHAR(160) NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_audit_case_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_sys_audit_case_status
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_sys_audit_case_closed
        CHECK ((status <> 'CLOSED' AND closed_at IS NULL) OR (status = 'CLOSED' AND closed_at IS NOT NULL))
);

CREATE INDEX idx_sys_audit_case_tenant_status
    ON sys_audit_cases(tenant_id, status, updated_at DESC);

CREATE TABLE sys_audit_findings (
    finding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    event_id UUID,
    event_occurred_at TIMESTAMPTZ,
    finding_type VARCHAR(80) NOT NULL,
    rule_key VARCHAR(120) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    risk_score SMALLINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    title VARCHAR(240) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    source_service VARCHAR(120) NOT NULL,
    actor_id VARCHAR(160),
    target_type VARCHAR(100),
    target_id VARCHAR(240),
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    assigned_to VARCHAR(160),
    case_id UUID REFERENCES sys_audit_cases(case_id),
    resolution VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_audit_finding_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_sys_audit_finding_risk CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_sys_audit_finding_status
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_sys_audit_finding_count CHECK (occurrence_count > 0)
);

CREATE UNIQUE INDEX uk_sys_audit_finding_event_rule
    ON sys_audit_findings(tenant_id, event_id, rule_key)
    WHERE event_id IS NOT NULL;
CREATE INDEX idx_sys_audit_finding_tenant_status
    ON sys_audit_findings(tenant_id, status, severity, last_seen_at DESC);

CREATE TABLE sys_audit_case_events (
    case_id UUID NOT NULL REFERENCES sys_audit_cases(case_id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    event_occurred_at TIMESTAMPTZ NOT NULL,
    added_by VARCHAR(160) NOT NULL,
    note VARCHAR(2000),
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (case_id, event_id)
);

CREATE TABLE sys_audit_saved_searches (
    saved_search_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    owner_actor_id VARCHAR(160) NOT NULL,
    name VARCHAR(160) NOT NULL,
    criteria JSONB NOT NULL,
    shared BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_audit_saved_search_criteria CHECK (jsonb_typeof(criteria) = 'object'),
    CONSTRAINT uk_sys_audit_saved_search_name UNIQUE (tenant_id, owner_actor_id, name)
);

CREATE TABLE sys_audit_export_jobs (
    export_job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    requested_by VARCHAR(160) NOT NULL,
    criteria JSONB NOT NULL,
    format VARCHAR(20) NOT NULL DEFAULT 'CSV',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    row_count INTEGER,
    content BYTEA,
    content_sha256 CHAR(64),
    error_message VARCHAR(1000),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    CONSTRAINT ck_sys_audit_export_criteria CHECK (jsonb_typeof(criteria) = 'object'),
    CONSTRAINT ck_sys_audit_export_format CHECK (format IN ('CSV', 'JSONL')),
    CONSTRAINT ck_sys_audit_export_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED'))
);

CREATE INDEX idx_sys_audit_export_tenant_time
    ON sys_audit_export_jobs(tenant_id, requested_at DESC);

CREATE TABLE sys_audit_retention_policies (
    tenant_id BIGINT PRIMARY KEY,
    standard_retention_days INTEGER NOT NULL DEFAULT 365,
    extended_retention_days INTEGER NOT NULL DEFAULT 2555,
    export_limit_rows INTEGER NOT NULL DEFAULT 50000,
    require_export_reason BOOLEAN NOT NULL DEFAULT TRUE,
    integrity_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    high_risk_threshold SMALLINT NOT NULL DEFAULT 70,
    updated_by VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sys_audit_policy_standard CHECK (standard_retention_days BETWEEN 90 AND 3650),
    CONSTRAINT ck_sys_audit_policy_extended CHECK (extended_retention_days BETWEEN 365 AND 3650),
    CONSTRAINT ck_sys_audit_policy_order CHECK (extended_retention_days >= standard_retention_days),
    CONSTRAINT ck_sys_audit_policy_export CHECK (export_limit_rows BETWEEN 100 AND 500000),
    CONSTRAINT ck_sys_audit_policy_risk CHECK (high_risk_threshold BETWEEN 50 AND 100)
);

CREATE TABLE sys_audit_integrity_checkpoints (
    checkpoint_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    checkpoint_date DATE NOT NULL,
    record_count BIGINT NOT NULL,
    first_event_at TIMESTAMPTZ,
    last_event_at TIMESTAMPTZ,
    root_hash CHAR(64) NOT NULL,
    previous_checkpoint_hash CHAR(64),
    checkpoint_hash CHAR(64) NOT NULL,
    signature_algorithm VARCHAR(40) NOT NULL DEFAULT 'HMAC-SHA256',
    signature VARCHAR(512) NOT NULL,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'VERIFIED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at TIMESTAMPTZ,
    CONSTRAINT uk_sys_audit_integrity_day UNIQUE (tenant_id, checkpoint_date),
    CONSTRAINT ck_sys_audit_integrity_count CHECK (record_count >= 0),
    CONSTRAINT ck_sys_audit_integrity_status
        CHECK (verification_status IN ('VERIFIED', 'FAILED', 'UNAVAILABLE'))
);

CREATE INDEX idx_sys_audit_integrity_tenant_date
    ON sys_audit_integrity_checkpoints(tenant_id, checkpoint_date DESC);

CREATE TABLE sys_audit_source_health (
    tenant_id BIGINT NOT NULL,
    source_service VARCHAR(120) NOT NULL,
    last_event_at TIMESTAMPTZ,
    last_ingested_at TIMESTAMPTZ,
    event_count_24h BIGINT NOT NULL DEFAULT 0,
    rejected_count_24h BIGINT NOT NULL DEFAULT 0,
    delivery_status VARCHAR(24) NOT NULL DEFAULT 'HEALTHY',
    last_error VARCHAR(1000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, source_service),
    CONSTRAINT ck_sys_audit_source_status
        CHECK (delivery_status IN ('HEALTHY', 'DEGRADED', 'STALE', 'ERROR'))
);

CREATE OR REPLACE FUNCTION sys_platform_audit_to_outbox()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
BEGIN
    payload := jsonb_build_object(
        'eventId', NEW.audit_event_id,
        'eventVersion', '1.0',
        'occurredAt', NEW.occurred_at,
        'tenantId', NEW.tenant_id,
        'category', 'ADMIN_CHANGE',
        'action', NEW.action,
        'outcome', NEW.outcome,
        'severity', CASE WHEN NEW.outcome = 'SUCCESS' THEN 'INFO' ELSE 'MEDIUM' END,
        'riskScore', CASE WHEN NEW.outcome = 'SUCCESS' THEN 10 ELSE 50 END,
        'actorType', NEW.actor_type,
        'actorId', NEW.actor_id::TEXT,
        'actorRoles', '[]'::jsonb,
        'sourceService', 'dwp-platform-server',
        'sourceModule', 'platform-administration',
        'environment', COALESCE(current_setting('dwp.environment', TRUE), 'local'),
        'targetType', NEW.target_type,
        'targetId', NEW.target_id,
        'targetDisplayName', NEW.target_id,
        'correlationId', NEW.correlation_id,
        'beforeState', CASE WHEN NEW.before_snapshot IS NULL THEN '{}'::jsonb ELSE NEW.before_snapshot::jsonb END,
        'afterState', CASE WHEN NEW.after_snapshot IS NULL THEN '{}'::jsonb ELSE NEW.after_snapshot::jsonb END,
        'metadata', jsonb_build_object('legacyAuditEventId', NEW.audit_event_id),
        'retentionClass', 'STANDARD');
    INSERT INTO sys_audit_outbox (
        outbox_id, event_id, tenant_id, payload, status, attempt_count,
        available_at, created_at, updated_at)
    VALUES (
        gen_random_uuid(), NEW.audit_event_id, NEW.tenant_id, payload,
        'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sys_platform_audit_outbox
AFTER INSERT ON sys_platform_audit_events
FOR EACH ROW EXECUTE FUNCTION sys_platform_audit_to_outbox();

INSERT INTO sys_audit_outbox (
    outbox_id, event_id, tenant_id, payload, status, attempt_count,
    available_at, created_at, updated_at)
SELECT
    gen_random_uuid(), event.audit_event_id, event.tenant_id,
    jsonb_build_object(
        'eventId', event.audit_event_id,
        'eventVersion', '1.0',
        'occurredAt', event.occurred_at,
        'tenantId', event.tenant_id,
        'category', 'ADMIN_CHANGE',
        'action', event.action,
        'outcome', event.outcome,
        'severity', CASE WHEN event.outcome = 'SUCCESS' THEN 'INFO' ELSE 'MEDIUM' END,
        'riskScore', CASE WHEN event.outcome = 'SUCCESS' THEN 10 ELSE 50 END,
        'actorType', event.actor_type,
        'actorId', event.actor_id::TEXT,
        'actorRoles', '[]'::jsonb,
        'sourceService', 'dwp-platform-server',
        'sourceModule', 'platform-administration',
        'environment', 'local',
        'targetType', event.target_type,
        'targetId', event.target_id,
        'targetDisplayName', event.target_id,
        'correlationId', event.correlation_id,
        'beforeState', CASE WHEN event.before_snapshot IS NULL THEN '{}'::jsonb ELSE event.before_snapshot::jsonb END,
        'afterState', CASE WHEN event.after_snapshot IS NULL THEN '{}'::jsonb ELSE event.after_snapshot::jsonb END,
        'metadata', jsonb_build_object('legacyAuditEventId', event.audit_event_id),
        'retentionClass', 'STANDARD'),
    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM sys_platform_audit_events event
ON CONFLICT (event_id) DO NOTHING;
