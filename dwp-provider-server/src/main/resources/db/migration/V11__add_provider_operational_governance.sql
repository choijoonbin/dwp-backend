ALTER TABLE prv_deployment_cells
    ADD COLUMN placement_capacity INTEGER NOT NULL DEFAULT 250,
    ADD COLUMN warning_threshold_pct NUMERIC(5, 2) NOT NULL DEFAULT 75.00,
    ADD COLUMN critical_threshold_pct NUMERIC(5, 2) NOT NULL DEFAULT 90.00,
    ADD CONSTRAINT ck_prv_deployment_cells_capacity
        CHECK (placement_capacity > 0),
    ADD CONSTRAINT ck_prv_deployment_cells_thresholds
        CHECK (
            warning_threshold_pct > 0
            AND warning_threshold_pct < critical_threshold_pct
            AND critical_threshold_pct <= 100
        );

CREATE TABLE prv_operation_approvals (
    operation_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id UUID NOT NULL REFERENCES prv_operations(operation_id) ON DELETE CASCADE,
    gate_key VARCHAR(80) NOT NULL,
    gate_order INTEGER NOT NULL DEFAULT 1,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    required_role_code VARCHAR(50) NOT NULL REFERENCES prv_operator_roles(role_code),
    separation_of_duties BOOLEAN NOT NULL DEFAULT TRUE,
    requested_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    decided_by BIGINT REFERENCES prv_operators(provider_operator_id),
    request_reason VARCHAR(1000) NOT NULL,
    decision_reason VARCHAR(1000),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_prv_operation_approvals_gate UNIQUE (operation_id, gate_key),
    CONSTRAINT ck_prv_operation_approvals_key
        CHECK (gate_key ~ '^[A-Z][A-Z0-9_]{1,79}$'),
    CONSTRAINT ck_prv_operation_approvals_order CHECK (gate_order > 0),
    CONSTRAINT ck_prv_operation_approvals_state
        CHECK (lifecycle_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_prv_operation_approvals_window CHECK (expires_at > requested_at),
    CONSTRAINT ck_prv_operation_approvals_decision
        CHECK (
            (lifecycle_state IN ('APPROVED', 'REJECTED')
                AND decided_by IS NOT NULL
                AND decided_at IS NOT NULL
                AND decision_reason IS NOT NULL)
            OR
            (lifecycle_state NOT IN ('APPROVED', 'REJECTED')
                AND decided_by IS NULL
                AND decided_at IS NULL)
        )
);

CREATE INDEX idx_prv_operation_approvals_queue
    ON prv_operation_approvals(lifecycle_state, expires_at, requested_at);
CREATE INDEX idx_prv_operation_approvals_operation
    ON prv_operation_approvals(operation_id, gate_order);

CREATE SEQUENCE prv_incident_number_seq START WITH 1;

CREATE TABLE prv_service_incidents (
    service_incident_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_key VARCHAR(40) NOT NULL UNIQUE,
    title VARCHAR(240) NOT NULL,
    severity VARCHAR(10) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'INVESTIGATING',
    impact_scope VARCHAR(20) NOT NULL,
    service_key VARCHAR(80) REFERENCES prv_service_catalog(service_key),
    region_key VARCHAR(40) REFERENCES prv_regions(region_key),
    deployment_cell_id UUID REFERENCES prv_deployment_cells(deployment_cell_id),
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    customer_impact VARCHAR(2000) NOT NULL,
    public_summary VARCHAR(2000),
    owner_operator_id BIGINT REFERENCES prv_operators(provider_operator_id),
    correlation_id VARCHAR(128),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT REFERENCES prv_operators(provider_operator_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT REFERENCES prv_operators(provider_operator_id),
    CONSTRAINT ck_prv_service_incidents_severity
        CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    CONSTRAINT ck_prv_service_incidents_state
        CHECK (lifecycle_state IN ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_prv_service_incidents_scope
        CHECK (impact_scope IN ('GLOBAL', 'REGION', 'CELL', 'SERVICE', 'TENANT')),
    CONSTRAINT ck_prv_service_incidents_scope_target
        CHECK (
            impact_scope = 'GLOBAL'
            OR (impact_scope = 'REGION' AND region_key IS NOT NULL)
            OR (impact_scope = 'CELL' AND deployment_cell_id IS NOT NULL)
            OR (impact_scope = 'SERVICE' AND service_key IS NOT NULL)
            OR (impact_scope = 'TENANT' AND provider_tenant_id IS NOT NULL)
        ),
    CONSTRAINT ck_prv_service_incidents_resolution
        CHECK ((lifecycle_state IN ('RESOLVED', 'CLOSED')) = (resolved_at IS NOT NULL)),
    CONSTRAINT ck_prv_service_incidents_closed
        CHECK ((lifecycle_state = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_prv_service_incidents_attributes
        CHECK (jsonb_typeof(attributes) = 'object')
);

CREATE TABLE prv_service_incident_updates (
    service_incident_update_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_incident_id UUID NOT NULL
        REFERENCES prv_service_incidents(service_incident_id) ON DELETE CASCADE,
    lifecycle_state VARCHAR(24) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT REFERENCES prv_operators(provider_operator_id),
    CONSTRAINT ck_prv_service_incident_updates_state
        CHECK (lifecycle_state IN ('INVESTIGATING', 'IDENTIFIED', 'MONITORING', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_prv_service_incident_updates_visibility
        CHECK (visibility IN ('INTERNAL', 'CUSTOMER'))
);

CREATE INDEX idx_prv_service_incidents_active
    ON prv_service_incidents(severity, detected_at DESC)
    WHERE lifecycle_state NOT IN ('RESOLVED', 'CLOSED');
CREATE INDEX idx_prv_service_incidents_tenant
    ON prv_service_incidents(provider_tenant_id, detected_at DESC)
    WHERE provider_tenant_id IS NOT NULL;
CREATE INDEX idx_prv_service_incident_updates_timeline
    ON prv_service_incident_updates(service_incident_id, created_at DESC);

CREATE TABLE prv_service_health_observations (
    service_health_observation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_key VARCHAR(80) NOT NULL REFERENCES prv_service_catalog(service_key),
    deployment_cell_id UUID NOT NULL REFERENCES prv_deployment_cells(deployment_cell_id),
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    health_state VARCHAR(20) NOT NULL,
    availability_pct NUMERIC(7, 4),
    latency_p95_ms INTEGER,
    error_rate_pct NUMERIC(7, 4),
    saturation_pct NUMERIC(7, 4),
    source VARCHAR(80) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_prv_service_health_observations_state
        CHECK (health_state IN ('HEALTHY', 'DEGRADED', 'UNAVAILABLE', 'UNKNOWN')),
    CONSTRAINT ck_prv_service_health_observations_availability
        CHECK (availability_pct IS NULL OR availability_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_prv_service_health_observations_latency
        CHECK (latency_p95_ms IS NULL OR latency_p95_ms >= 0),
    CONSTRAINT ck_prv_service_health_observations_error_rate
        CHECK (error_rate_pct IS NULL OR error_rate_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_prv_service_health_observations_saturation
        CHECK (saturation_pct IS NULL OR saturation_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_prv_service_health_observations_details
        CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX idx_prv_service_health_observations_lookup
    ON prv_service_health_observations(service_key, deployment_cell_id, observed_at DESC);
CREATE INDEX idx_prv_service_health_observations_tenant
    ON prv_service_health_observations(provider_tenant_id, observed_at DESC)
    WHERE provider_tenant_id IS NOT NULL;
CREATE INDEX idx_prv_service_health_observations_brin_time
    ON prv_service_health_observations USING BRIN (observed_at);

ALTER TABLE prv_support_sessions
    ADD COLUMN access_mode VARCHAR(20) NOT NULL DEFAULT 'BREAK_GLASS',
    ADD COLUMN approval_reference VARCHAR(160),
    ADD COLUMN risk_tier VARCHAR(10) NOT NULL DEFAULT 'L3',
    ADD CONSTRAINT ck_prv_support_sessions_access_mode
        CHECK (access_mode IN ('STANDARD', 'BREAK_GLASS')),
    ADD CONSTRAINT ck_prv_support_sessions_risk_tier
        CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    ADD CONSTRAINT ck_prv_support_sessions_approval
        CHECK (
            access_mode = 'BREAK_GLASS'
            OR (approval_reference IS NOT NULL AND LENGTH(BTRIM(approval_reference)) > 0)
        );

ALTER TABLE prv_support_sessions
    ALTER COLUMN access_mode SET DEFAULT 'STANDARD',
    ALTER COLUMN risk_tier SET DEFAULT 'L1';

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('HEALTH_READ', 'Read service health', 'L1', 'View service posture, cells, and incidents'),
    ('INCIDENT_WRITE', 'Manage service incidents', 'L3', 'Declare and update provider incidents'),
    ('COMMERCIAL_READ', 'Read commercial portfolio', 'L2', 'View plans, subscriptions, and entitlement adoption'),
    ('CHANGE_APPROVE', 'Approve provider changes', 'L3', 'Approve or reject gated provider operations'),
    ('BREAK_GLASS_SUPPORT', 'Use emergency support access', 'L3', 'Create audited support access without a customer approval reference')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'HEALTH_READ'),
    ('PROVIDER_ADMIN', 'INCIDENT_WRITE'),
    ('PROVIDER_ADMIN', 'COMMERCIAL_READ'),
    ('PROVIDER_ADMIN', 'CHANGE_APPROVE'),
    ('PROVIDER_ADMIN', 'BREAK_GLASS_SUPPORT'),
    ('PROVIDER_OPERATOR', 'HEALTH_READ'),
    ('PROVIDER_OPERATOR', 'INCIDENT_WRITE'),
    ('PROVIDER_OPERATOR', 'COMMERCIAL_READ'),
    ('PROVIDER_SUPPORT', 'HEALTH_READ'),
    ('PROVIDER_AUDITOR', 'HEALTH_READ'),
    ('PROVIDER_AUDITOR', 'COMMERCIAL_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;

UPDATE prv_audit_events
   SET event_category = CASE
       WHEN action LIKE 'provider.support-%' THEN 'PRIVILEGED_ACCESS'
       WHEN action LIKE 'provider.incident.%' THEN 'SERVICE_HEALTH'
       WHEN action LIKE 'provider.operation.%' THEN 'CHANGE_MANAGEMENT'
       WHEN action LIKE 'provider.tenant-%' THEN 'TENANT_LIFECYCLE'
       ELSE event_category
   END;
