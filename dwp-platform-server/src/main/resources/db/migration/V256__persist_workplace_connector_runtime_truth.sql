CREATE TABLE wp_connector_runtime_observations (
    observation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connector_kind VARCHAR(24) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    configuration_version BIGINT NOT NULL,
    adapter_id VARCHAR(120) NOT NULL,
    adapter_version VARCHAR(80) NOT NULL,
    reported_state VARCHAR(24) NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    lag_seconds BIGINT,
    checkpoint_reference VARCHAR(320),
    retry_queue_depth BIGINT,
    dead_letter_queue_depth BIGINT,
    error_code VARCHAR(120),
    observation_sequence BIGINT NOT NULL,
    payload_fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, connector_kind)
        REFERENCES wp_experience_connector_configurations(tenant_id, connector_kind),
    CONSTRAINT uq_wp_connector_observation_sequence
        UNIQUE (tenant_id, connector_kind, observation_sequence),
    CONSTRAINT uq_wp_connector_observation_tenant_kind
        UNIQUE (tenant_id, connector_kind, observation_id),
    CONSTRAINT ck_wp_connector_observation_state
        CHECK (reported_state IN ('HEALTHY', 'DEGRADED', 'UNAVAILABLE')),
    CONSTRAINT ck_wp_connector_observation_capabilities
        CHECK (jsonb_typeof(capabilities) = 'array'),
    CONSTRAINT ck_wp_connector_observation_numbers
        CHECK (configuration_version >= 0 AND observation_sequence > 0
            AND (lag_seconds IS NULL OR lag_seconds >= 0)
            AND (retry_queue_depth IS NULL OR retry_queue_depth >= 0)
            AND (dead_letter_queue_depth IS NULL OR dead_letter_queue_depth >= 0)),
    CONSTRAINT ck_wp_connector_observation_provider
        CHECK (provider ~ '^[A-Za-z0-9._-]{1,80}$'),
    CONSTRAINT ck_wp_connector_observation_fingerprint
        CHECK (payload_fingerprint ~ '^[A-Za-z0-9:_-]{8,128}$')
);

CREATE INDEX idx_wp_connector_observations_tenant_kind_time
    ON wp_connector_runtime_observations
        (tenant_id, connector_kind, received_at DESC);

CREATE TABLE wp_connector_runtime_truth (
    tenant_id BIGINT NOT NULL,
    connector_kind VARCHAR(24) NOT NULL,
    observation_id UUID NOT NULL,
    provider VARCHAR(80) NOT NULL,
    configuration_version BIGINT NOT NULL,
    adapter_id VARCHAR(120) NOT NULL,
    adapter_version VARCHAR(80) NOT NULL,
    reported_state VARCHAR(24) NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    lag_seconds BIGINT,
    checkpoint_reference VARCHAR(320),
    retry_queue_depth BIGINT,
    dead_letter_queue_depth BIGINT,
    error_code VARCHAR(120),
    observation_sequence BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, connector_kind),
    FOREIGN KEY (tenant_id, connector_kind)
        REFERENCES wp_experience_connector_configurations(tenant_id, connector_kind),
    FOREIGN KEY (tenant_id, connector_kind, observation_id)
        REFERENCES wp_connector_runtime_observations(tenant_id, connector_kind, observation_id),
    CONSTRAINT ck_wp_connector_truth_state
        CHECK (reported_state IN ('HEALTHY', 'DEGRADED', 'UNAVAILABLE')),
    CONSTRAINT ck_wp_connector_truth_capabilities
        CHECK (jsonb_typeof(capabilities) = 'array'),
    CONSTRAINT ck_wp_connector_truth_versions
        CHECK (configuration_version >= 0 AND observation_sequence > 0 AND version > 0)
);

CREATE TABLE wp_connector_replay_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    connector_kind VARCHAR(24) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    configuration_version BIGINT NOT NULL,
    runtime_version BIGINT NOT NULL,
    replay_from TIMESTAMPTZ NOT NULL,
    replay_to TIMESTAMPTZ NOT NULL,
    failed_only BOOLEAN NOT NULL,
    maximum_records INTEGER NOT NULL,
    estimated_records BIGINT NOT NULL,
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, connector_kind)
        REFERENCES wp_experience_connector_configurations(tenant_id, connector_kind),
    CONSTRAINT uq_wp_connector_preview_tenant_kind
        UNIQUE (tenant_id, connector_kind, preview_id),
    CONSTRAINT ck_wp_connector_preview_range CHECK (replay_to > replay_from),
    CONSTRAINT ck_wp_connector_preview_counts
        CHECK (maximum_records BETWEEN 1 AND 100000 AND estimated_records >= 0),
    CONSTRAINT ck_wp_connector_preview_versions
        CHECK (configuration_version >= 0 AND runtime_version > 0),
    CONSTRAINT ck_wp_connector_preview_limitations
        CHECK (jsonb_typeof(limitations) = 'array'),
    CONSTRAINT ck_wp_connector_preview_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_wp_connector_replay_previews_expiry
    ON wp_connector_replay_previews (tenant_id, expires_at);

CREATE TABLE wp_connector_replay_jobs (
    replay_job_id UUID PRIMARY KEY,
    preview_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL,
    connector_kind VARCHAR(24) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    configuration_version BIGINT NOT NULL,
    runtime_version BIGINT NOT NULL,
    replay_state VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    requested_by BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    provider_operation_reference VARCHAR(320),
    result_summary VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 1,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, connector_kind)
        REFERENCES wp_experience_connector_configurations(tenant_id, connector_kind),
    FOREIGN KEY (tenant_id, connector_kind, preview_id)
        REFERENCES wp_connector_replay_previews(tenant_id, connector_kind, preview_id),
    CONSTRAINT uq_wp_connector_replay_idempotency
        UNIQUE (tenant_id, requested_by, idempotency_key),
    CONSTRAINT ck_wp_connector_replay_state CHECK
        (replay_state IN ('QUEUED', 'DISPATCHING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_connector_replay_versions
        CHECK (configuration_version >= 0 AND runtime_version > 0 AND version > 0),
    CONSTRAINT ck_wp_connector_replay_reason CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_wp_connector_replay_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_wp_connector_replay_jobs_active
    ON wp_connector_replay_jobs (tenant_id, connector_kind, requested_at DESC)
    WHERE replay_state IN ('QUEUED', 'DISPATCHING', 'RUNNING', 'RESULT_UNKNOWN');

COMMENT ON TABLE wp_connector_runtime_observations IS
    'Immutable provider-adapter observations. Administrator APIs cannot create runtime truth.';
COMMENT ON TABLE wp_connector_runtime_truth IS
    'Latest provider observation only. Public health remains a service-derived state that also checks configuration identity, version and freshness.';
COMMENT ON TABLE wp_connector_replay_jobs IS
    'Idempotent, tenant-scoped replay commands. RESULT_UNKNOWN requires operator status re-query and is never interpreted as success.';
