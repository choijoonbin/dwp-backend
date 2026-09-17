CREATE TABLE wp_space_planning_source_observations (
    observation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    series_kind VARCHAR(32) NOT NULL,
    site_id UUID NOT NULL,
    floor_id UUID,
    neighborhood VARCHAR(120),
    resource_type VARCHAR(24),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    availability_state VARCHAR(24) NOT NULL,
    coverage_percent NUMERIC(5,2) NOT NULL,
    source_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    evidence_reference VARCHAR(320) NOT NULL,
    exclusions JSONB NOT NULL DEFAULT '[]'::jsonb,
    series_points JSONB NOT NULL DEFAULT '[]'::jsonb,
    observation_sequence BIGINT NOT NULL,
    payload_fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, series_kind, observation_id),
    UNIQUE (tenant_id, series_kind, site_id, observation_sequence),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors(tenant_id, site_id, floor_id),
    CONSTRAINT ck_wp_plan_source_series CHECK (series_kind IN
        ('WORK_PLAN','RESERVATION','CHECK_IN','ACCESS','SENSOR_OCCUPANCY','NO_SHOW')),
    CONSTRAINT ck_wp_plan_source_availability CHECK (availability_state IN
        ('AVAILABLE','PARTIAL','UNAVAILABLE','COMPUTE_FAILED')),
    CONSTRAINT ck_wp_plan_source_resource_type CHECK (resource_type IS NULL OR resource_type IN
        ('ROOM','DESK','LOCKER','PARKING','FOCUS_POD','PHONE_BOOTH','EQUIPMENT')),
    CONSTRAINT ck_wp_plan_source_window CHECK (window_end > window_start),
    CONSTRAINT ck_wp_plan_source_coverage CHECK (coverage_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_wp_plan_source_clock CHECK (source_at <= received_at),
    CONSTRAINT ck_wp_plan_source_json CHECK
        (jsonb_typeof(exclusions)='array' AND jsonb_typeof(series_points)='array'),
    CONSTRAINT ck_wp_plan_source_sequence CHECK (observation_sequence > 0),
    CONSTRAINT ck_wp_plan_source_evidence CHECK
        (length(trim(evidence_reference)) BETWEEN 1 AND 320),
    CONSTRAINT ck_wp_plan_source_fingerprint CHECK
        (payload_fingerprint ~ '^[A-Za-z0-9:_-]{8,128}$')
);

CREATE INDEX idx_wp_plan_source_latest
    ON wp_space_planning_source_observations
       (tenant_id, site_id, series_kind, received_at DESC, observation_sequence DESC);

CREATE TABLE wp_space_planning_forecasts (
    forecast_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    site_id UUID NOT NULL,
    floor_id UUID,
    neighborhood VARCHAR(120),
    resource_type VARCHAR(24),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    forecast_state VARCHAR(32) NOT NULL,
    calculation_version VARCHAR(120),
    evidence_reference VARCHAR(320),
    source_observation_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    forecast_points JSONB NOT NULL DEFAULT '[]'::jsonb,
    recommendation_metrics JSONB,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, forecast_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors(tenant_id, site_id, floor_id),
    CONSTRAINT ck_wp_plan_forecast_state CHECK (forecast_state IN
        ('READY','DATA_INSUFFICIENT','STALE','PARTIAL','COMPUTE_FAILED')),
    CONSTRAINT ck_wp_plan_forecast_resource CHECK (resource_type IS NULL OR resource_type IN
        ('ROOM','DESK','LOCKER','PARKING','FOCUS_POD','PHONE_BOOTH','EQUIPMENT')),
    CONSTRAINT ck_wp_plan_forecast_window CHECK (window_end > window_start),
    CONSTRAINT ck_wp_plan_forecast_clock CHECK (source_at <= received_at),
    CONSTRAINT ck_wp_plan_forecast_json CHECK
        (jsonb_typeof(source_observation_ids)='array'
         AND jsonb_typeof(forecast_points)='array'
         AND jsonb_typeof(limitations)='array'
         AND (recommendation_metrics IS NULL OR jsonb_typeof(recommendation_metrics)='object')),
    CONSTRAINT ck_wp_plan_forecast_ready CHECK
        ((forecast_state='READY'
          AND calculation_version IS NOT NULL
          AND evidence_reference IS NOT NULL
          AND jsonb_array_length(source_observation_ids) > 0
          AND jsonb_array_length(forecast_points) > 0
          AND recommendation_metrics IS NOT NULL)
         OR (forecast_state<>'READY'
          AND forecast_points='[]'::jsonb
          AND recommendation_metrics IS NULL))
);

CREATE INDEX idx_wp_plan_forecast_latest
    ON wp_space_planning_forecasts(tenant_id, site_id, received_at DESC);

CREATE TABLE wp_space_planning_emission_evidence (
    emission_evidence_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    site_id UUID NOT NULL,
    floor_id UUID,
    evidence_kind VARCHAR(24) NOT NULL,
    energy_value NUMERIC(18,6) NOT NULL,
    energy_unit VARCHAR(24) NOT NULL,
    co2e_value NUMERIC(18,6) NOT NULL,
    co2e_unit VARCHAR(24) NOT NULL,
    factor_version VARCHAR(120) NOT NULL,
    region_code VARCHAR(80) NOT NULL,
    evidence_reference VARCHAR(320) NOT NULL,
    source_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    approved_by BIGINT,
    approval_authority_reference VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, emission_evidence_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors(tenant_id, site_id, floor_id),
    CONSTRAINT ck_wp_plan_emission_kind CHECK
        (evidence_kind IN ('METER','APPROVED_MODEL')),
    CONSTRAINT ck_wp_plan_emission_values CHECK
        (energy_value >= 0 AND co2e_value >= 0),
    CONSTRAINT ck_wp_plan_emission_clock CHECK (source_at <= received_at),
    CONSTRAINT ck_wp_plan_emission_approval CHECK
        ((evidence_kind='METER')
         OR (approved_by IS NOT NULL
             AND approval_authority_reference IS NOT NULL
             AND length(trim(approval_authority_reference)) BETWEEN 1 AND 160)),
    CONSTRAINT ck_wp_plan_emission_metadata CHECK
        (length(trim(energy_unit)) BETWEEN 1 AND 24
         AND length(trim(co2e_unit)) BETWEEN 1 AND 24
         AND length(trim(factor_version)) BETWEEN 1 AND 120
         AND length(trim(region_code)) BETWEEN 1 AND 80
         AND length(trim(evidence_reference)) BETWEEN 1 AND 320)
);

CREATE INDEX idx_wp_plan_emission_latest
    ON wp_space_planning_emission_evidence(tenant_id, site_id, received_at DESC);

CREATE TABLE wp_space_planning_scenarios (
    scenario_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    site_id UUID NOT NULL,
    floor_id UUID,
    neighborhood VARCHAR(120),
    resource_type VARCHAR(24),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    proposed_capacity INTEGER NOT NULL,
    proposed_room_capacity INTEGER NOT NULL,
    proposed_accessible_resource_count INTEGER NOT NULL,
    operating_start TIME NOT NULL,
    operating_end TIME NOT NULL,
    policy_reference VARCHAR(320),
    affected_resource_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    neighborhood_allocations JSONB NOT NULL DEFAULT '[]'::jsonb,
    emission_evidence_id UUID,
    active_preview_id UUID,
    version BIGINT NOT NULL DEFAULT 1,
    submitted_at TIMESTAMPTZ,
    submitted_by BIGINT,
    approved_at TIMESTAMPTZ,
    approved_by BIGINT,
    approval_authority_reference VARCHAR(160),
    published_at TIMESTAMPTZ,
    published_by BIGINT,
    last_rejected_at TIMESTAMPTZ,
    last_rejected_by BIGINT,
    last_rejection_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    UNIQUE (tenant_id, scenario_id),
    FOREIGN KEY (tenant_id, site_id) REFERENCES wp_sites(tenant_id, site_id),
    FOREIGN KEY (tenant_id, site_id, floor_id)
        REFERENCES wp_floors(tenant_id, site_id, floor_id),
    FOREIGN KEY (tenant_id, emission_evidence_id)
        REFERENCES wp_space_planning_emission_evidence(tenant_id, emission_evidence_id),
    CONSTRAINT ck_wp_plan_scenario_state CHECK (lifecycle_state IN
        ('DRAFT','PREVIEWED','SUBMITTED','APPROVED','PUBLISHED')),
    CONSTRAINT ck_wp_plan_scenario_resource CHECK (resource_type IS NULL OR resource_type IN
        ('ROOM','DESK','LOCKER','PARKING','FOCUS_POD','PHONE_BOOTH','EQUIPMENT')),
    CONSTRAINT ck_wp_plan_scenario_window CHECK (window_end > window_start),
    CONSTRAINT ck_wp_plan_scenario_capacity CHECK
        (proposed_capacity >= 0 AND proposed_room_capacity >= 0
         AND proposed_accessible_resource_count >= 0
         AND proposed_room_capacity <= proposed_capacity
         AND proposed_accessible_resource_count <= proposed_capacity),
    CONSTRAINT ck_wp_plan_scenario_hours CHECK (operating_end > operating_start),
    CONSTRAINT ck_wp_plan_scenario_json CHECK
        (jsonb_typeof(affected_resource_ids)='array'
         AND jsonb_typeof(neighborhood_allocations)='array'),
    CONSTRAINT ck_wp_plan_scenario_approval CHECK
        (lifecycle_state NOT IN ('APPROVED','PUBLISHED')
         OR (approved_at IS NOT NULL AND approved_by IS NOT NULL
             AND approval_authority_reference IS NOT NULL
             AND length(trim(approval_authority_reference)) BETWEEN 1 AND 160)),
    CONSTRAINT ck_wp_plan_scenario_publication CHECK
        (lifecycle_state<>'PUBLISHED'
         OR (published_at IS NOT NULL AND published_by IS NOT NULL)),
    CONSTRAINT ck_wp_plan_scenario_version CHECK (version > 0)
);

CREATE INDEX idx_wp_plan_scenario_scope
    ON wp_space_planning_scenarios
       (tenant_id, site_id, lifecycle_state, updated_at DESC);

CREATE TABLE wp_space_planning_scenario_previews (
    preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    scenario_id UUID NOT NULL,
    scenario_version BIGINT NOT NULL,
    forecast_id UUID,
    forecast_state VARCHAR(32) NOT NULL,
    forecast_projection JSONB NOT NULL,
    comparison JSONB NOT NULL,
    emission_projection JSONB,
    eligible BOOLEAN NOT NULL,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, preview_id),
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    FOREIGN KEY (tenant_id, forecast_id)
        REFERENCES wp_space_planning_forecasts(tenant_id, forecast_id),
    CONSTRAINT ck_wp_plan_preview_forecast CHECK (forecast_state IN
        ('READY','DATA_INSUFFICIENT','STALE','PARTIAL','COMPUTE_FAILED')),
    CONSTRAINT ck_wp_plan_preview_json CHECK
        (jsonb_typeof(forecast_projection)='object'
         AND jsonb_typeof(comparison)='object'
         AND (emission_projection IS NULL OR jsonb_typeof(emission_projection)='object')
         AND jsonb_typeof(limitations)='array'),
    CONSTRAINT ck_wp_plan_preview_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_wp_plan_preview_version CHECK (scenario_version > 0)
);

ALTER TABLE wp_space_planning_scenarios
    ADD CONSTRAINT fk_wp_plan_scenario_active_preview
        FOREIGN KEY (tenant_id, active_preview_id)
        REFERENCES wp_space_planning_scenario_previews(tenant_id, preview_id);

CREATE TABLE wp_space_planning_booking_impact_previews (
    impact_preview_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    scenario_id UUID NOT NULL,
    scenario_version BIGINT NOT NULL,
    impact_state VARCHAR(24) NOT NULL,
    impacted_booking_count INTEGER,
    booking_items JSONB NOT NULL DEFAULT '[]'::jsonb,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, impact_preview_id),
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    CONSTRAINT ck_wp_plan_impact_state CHECK
        (impact_state IN ('READY','SCOPE_INCOMPLETE','COMPUTE_FAILED')),
    CONSTRAINT ck_wp_plan_impact_count CHECK
        (impacted_booking_count IS NULL OR impacted_booking_count >= 0),
    CONSTRAINT ck_wp_plan_impact_json CHECK
        (jsonb_typeof(booking_items)='array' AND jsonb_typeof(limitations)='array'),
    CONSTRAINT ck_wp_plan_impact_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_wp_plan_impact_version CHECK (scenario_version > 0)
);

CREATE TABLE wp_space_planning_commands (
    command_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    scenario_id UUID NOT NULL,
    command_type VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    command_state VARCHAR(24) NOT NULL,
    resulting_scenario_state VARCHAR(24) NOT NULL,
    resulting_scenario_version BIGINT NOT NULL,
    result_snapshot JSONB NOT NULL,
    correlation_id VARCHAR(160),
    outbox_id UUID,
    accepted_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, actor_user_id, idempotency_key),
    UNIQUE (tenant_id, command_id),
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    CONSTRAINT ck_wp_plan_command_type CHECK (command_type IN
        ('CREATE','UPDATE','PREVIEW','SUBMIT','APPROVE','REJECT','PUBLISH','BOOKING_IMPACT_PREVIEW')),
    CONSTRAINT ck_wp_plan_command_state CHECK
        (command_state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_plan_command_scenario_state CHECK (resulting_scenario_state IN
        ('DRAFT','PREVIEWED','SUBMITTED','APPROVED','PUBLISHED')),
    CONSTRAINT ck_wp_plan_command_fingerprint CHECK
        (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wp_plan_command_snapshot CHECK (jsonb_typeof(result_snapshot)='object'),
    CONSTRAINT ck_wp_plan_command_version CHECK (resulting_scenario_version > 0),
    CONSTRAINT ck_wp_plan_command_reason CHECK (length(trim(reason)) BETWEEN 1 AND 500)
);

CREATE TABLE wp_space_planning_outbox (
    outbox_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    command_id UUID NOT NULL,
    scenario_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    delivery_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, command_id),
    UNIQUE (tenant_id, outbox_id),
    FOREIGN KEY (tenant_id, command_id)
        REFERENCES wp_space_planning_commands(tenant_id, command_id),
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    CONSTRAINT ck_wp_plan_outbox_state CHECK
        (delivery_state IN ('PENDING','PUBLISHED','FAILED','RESULT_UNKNOWN')),
    CONSTRAINT ck_wp_plan_outbox_payload CHECK (jsonb_typeof(payload)='object'),
    CONSTRAINT ck_wp_plan_outbox_attempt CHECK (attempt_count >= 0)
);

ALTER TABLE wp_space_planning_commands
    ADD CONSTRAINT fk_wp_plan_command_outbox
        FOREIGN KEY (tenant_id, outbox_id)
        REFERENCES wp_space_planning_outbox(tenant_id, outbox_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE wp_space_planning_audit_events (
    audit_event_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_service_tenants(tenant_id),
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    scenario_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    correlation_id VARCHAR(160),
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES wp_space_planning_scenarios(tenant_id, scenario_id),
    CONSTRAINT ck_wp_plan_audit_action CHECK (action ~ '^[a-z][a-z0-9.]{2,99}$'),
    CONSTRAINT ck_wp_plan_audit_snapshot CHECK (jsonb_typeof(snapshot)='object')
);

CREATE INDEX idx_wp_plan_audit_scenario
    ON wp_space_planning_audit_events(tenant_id, scenario_id, occurred_at DESC);

COMMENT ON TABLE wp_space_planning_source_observations IS
    'Immutable, evidence-backed planning series. Work plans, reservations, check-ins, access, sensor occupancy and no-shows remain distinct.';
COMMENT ON TABLE wp_space_planning_forecasts IS
    'Trusted calculation output. Non-ready states are prohibited from storing forecast points or recommendation metrics.';
COMMENT ON TABLE wp_space_planning_scenarios IS
    'Planning decisions only. Publishing a scenario never moves, creates, cancels or mutates a booking.';
COMMENT ON TABLE wp_space_planning_emission_evidence IS
    'Energy and CO2e values require meter evidence or a human-approved calculation model with factor version, units and region.';
