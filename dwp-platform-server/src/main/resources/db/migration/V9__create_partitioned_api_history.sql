CREATE TABLE sys_api_history (
    history_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id BIGINT,
    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(160),
    auth_type VARCHAR(20) NOT NULL,
    service_name VARCHAR(120) NOT NULL,
    service_version VARCHAR(60),
    service_instance VARCHAR(160),
    environment VARCHAR(40) NOT NULL,
    observation_point VARCHAR(20) NOT NULL,
    route_id VARCHAR(120),
    http_method VARCHAR(12) NOT NULL,
    route_template VARCHAR(500) NOT NULL,
    request_path VARCHAR(500) NOT NULL,
    http_scheme VARCHAR(12),
    http_protocol VARCHAR(20),
    status_code SMALLINT NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    duration_ms BIGINT NOT NULL,
    request_size_bytes BIGINT,
    response_size_bytes BIGINT,
    correlation_id VARCHAR(128),
    trace_id CHAR(32),
    span_id CHAR(16),
    parent_span_id CHAR(16),
    client_address_hash CHAR(64),
    user_agent_family VARCHAR(40),
    user_agent_hash CHAR(64),
    error_type VARCHAR(80),
    capture_policy_version VARCHAR(40) NOT NULL,
    PRIMARY KEY (occurred_at, history_id),
    CONSTRAINT ck_sys_api_history_time CHECK (completed_at >= occurred_at),
    CONSTRAINT ck_sys_api_history_actor
        CHECK (actor_type IN ('ANONYMOUS', 'USER', 'SERVICE', 'SYSTEM', 'AGENT')),
    CONSTRAINT ck_sys_api_history_auth
        CHECK (auth_type IN ('NONE', 'SESSION', 'BEARER', 'SERVICE', 'SCIM', 'UNKNOWN')),
    CONSTRAINT ck_sys_api_history_observation
        CHECK (observation_point IN ('GATEWAY', 'SERVICE')),
    CONSTRAINT ck_sys_api_history_status CHECK (status_code BETWEEN 100 AND 599),
    CONSTRAINT ck_sys_api_history_outcome
        CHECK (outcome IN ('SUCCESS', 'REDIRECTION', 'CLIENT_ERROR', 'SERVER_ERROR', 'CANCELLED')),
    CONSTRAINT ck_sys_api_history_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_sys_api_history_request_size
        CHECK (request_size_bytes IS NULL OR request_size_bytes >= 0),
    CONSTRAINT ck_sys_api_history_response_size
        CHECK (response_size_bytes IS NULL OR response_size_bytes >= 0),
    CONSTRAINT ck_sys_api_history_trace
        CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_sys_api_history_span
        CHECK (span_id IS NULL OR span_id ~ '^[0-9a-f]{16}$'),
    CONSTRAINT ck_sys_api_history_parent_span
        CHECK (parent_span_id IS NULL OR parent_span_id ~ '^[0-9a-f]{16}$')
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE sys_api_history IS
    'Privacy-minimized, append-only HTTP exchange index. Payloads, query strings, tokens, cookies, and raw network identifiers are prohibited.';

CREATE OR REPLACE FUNCTION sys_ensure_api_history_partition(p_month DATE)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    month_start DATE := date_trunc('month', p_month)::DATE;
    month_end DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::DATE;
    partition_name TEXT := 'sys_api_history_' || to_char(month_start, 'YYYYMM');
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('sys_api_history_partition'));
    IF to_regclass(partition_name) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF sys_api_history FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            month_start,
            month_end);
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION sys_maintain_api_history_partitions(p_retention_days INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    partition_record RECORD;
    partition_month DATE;
    cutoff_month DATE := date_trunc(
        'month', CURRENT_DATE - GREATEST(30, p_retention_days))::DATE;
    dropped_count INTEGER := 0;
BEGIN
    PERFORM sys_ensure_api_history_partition(CURRENT_DATE);
    PERFORM sys_ensure_api_history_partition((CURRENT_DATE + INTERVAL '1 month')::DATE);
    PERFORM sys_ensure_api_history_partition((CURRENT_DATE + INTERVAL '2 months')::DATE);

    FOR partition_record IN
        SELECT child.relname AS partition_name
        FROM pg_inherits inheritance
        JOIN pg_class parent ON parent.oid = inheritance.inhparent
        JOIN pg_class child ON child.oid = inheritance.inhrelid
        WHERE parent.relname = 'sys_api_history'
          AND child.relname ~ '^sys_api_history_[0-9]{6}$'
    LOOP
        partition_month := to_date(substring(partition_record.partition_name FROM '[0-9]{6}$'), 'YYYYMM');
        IF partition_month < cutoff_month THEN
            EXECUTE format('DROP TABLE %I', partition_record.partition_name);
            dropped_count := dropped_count + 1;
        END IF;
    END LOOP;
    RETURN dropped_count;
END;
$$;

SELECT sys_ensure_api_history_partition(CURRENT_DATE);
SELECT sys_ensure_api_history_partition((CURRENT_DATE + INTERVAL '1 month')::DATE);
SELECT sys_ensure_api_history_partition((CURRENT_DATE + INTERVAL '2 months')::DATE);

CREATE INDEX idx_sys_api_history_tenant_time
    ON sys_api_history(tenant_id, occurred_at DESC, history_id);
CREATE INDEX idx_sys_api_history_service_time
    ON sys_api_history(tenant_id, service_name, observation_point, occurred_at DESC);
CREATE INDEX idx_sys_api_history_route_time
    ON sys_api_history(tenant_id, route_id, occurred_at DESC);
CREATE INDEX idx_sys_api_history_trace
    ON sys_api_history(trace_id, occurred_at DESC);
CREATE INDEX idx_sys_api_history_server_errors
    ON sys_api_history(tenant_id, occurred_at DESC)
    WHERE status_code >= 500;
CREATE INDEX idx_sys_api_history_occurred_brin
    ON sys_api_history USING BRIN(occurred_at);
