CREATE TABLE prv_service_level_objectives (
    service_level_objective_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    objective_key VARCHAR(120) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    service_key VARCHAR(80) NOT NULL REFERENCES prv_service_catalog(service_key),
    indicator_type VARCHAR(24) NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    region_key VARCHAR(40) REFERENCES prv_regions(region_key),
    deployment_cell_id UUID REFERENCES prv_deployment_cells(deployment_cell_id),
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    target_pct NUMERIC(7, 4) NOT NULL,
    compliance_window_days INTEGER NOT NULL DEFAULT 28,
    warning_burn_rate NUMERIC(8, 3) NOT NULL DEFAULT 1.000,
    critical_burn_rate NUMERIC(8, 3) NOT NULL DEFAULT 2.000,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_slo_key
        CHECK (objective_key = LOWER(BTRIM(objective_key))
            AND objective_key ~ '^[a-z][a-z0-9.-]{2,119}$'),
    CONSTRAINT ck_prv_slo_indicator
        CHECK (indicator_type IN ('AVAILABILITY', 'SUCCESS_RATE', 'LATENCY')), 
    CONSTRAINT ck_prv_slo_scope
        CHECK (scope_type IN ('GLOBAL', 'REGION', 'CELL', 'TENANT')),
    CONSTRAINT ck_prv_slo_scope_target
        CHECK (
            (scope_type = 'GLOBAL'
                AND region_key IS NULL AND deployment_cell_id IS NULL AND provider_tenant_id IS NULL)
            OR (scope_type = 'REGION'
                AND region_key IS NOT NULL AND deployment_cell_id IS NULL AND provider_tenant_id IS NULL)
            OR (scope_type = 'CELL'
                AND region_key IS NULL AND deployment_cell_id IS NOT NULL AND provider_tenant_id IS NULL)
            OR (scope_type = 'TENANT'
                AND region_key IS NULL AND deployment_cell_id IS NULL AND provider_tenant_id IS NOT NULL)
        ),
    CONSTRAINT ck_prv_slo_target CHECK (target_pct > 0 AND target_pct < 100),
    CONSTRAINT ck_prv_slo_window CHECK (compliance_window_days BETWEEN 1 AND 365),
    CONSTRAINT ck_prv_slo_burn_rates
        CHECK (warning_burn_rate > 0 AND critical_burn_rate > warning_burn_rate),
    CONSTRAINT ck_prv_slo_state CHECK (lifecycle_state IN ('ACTIVE', 'PAUSED', 'RETIRED'))
);

CREATE INDEX idx_prv_slo_service_scope
    ON prv_service_level_objectives(service_key, scope_type, lifecycle_state);

CREATE TABLE prv_service_level_snapshots (
    service_level_snapshot_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_level_objective_id UUID NOT NULL
        REFERENCES prv_service_level_objectives(service_level_objective_id),
    window_started_at TIMESTAMPTZ NOT NULL,
    window_ended_at TIMESTAMPTZ NOT NULL,
    good_events BIGINT,
    total_events BIGINT,
    achieved_pct NUMERIC(9, 5),
    error_budget_remaining_pct NUMERIC(12, 5),
    burn_rate NUMERIC(12, 5),
    compliance_state VARCHAR(20) NOT NULL,
    measurement_source VARCHAR(120) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uk_prv_slo_snapshots_window
        UNIQUE (service_level_objective_id, window_ended_at, measurement_source),
    CONSTRAINT ck_prv_slo_snapshots_window CHECK (window_ended_at > window_started_at),
    CONSTRAINT ck_prv_slo_snapshots_events
        CHECK ((good_events IS NULL AND total_events IS NULL)
            OR (good_events IS NOT NULL AND total_events IS NOT NULL
                AND good_events >= 0 AND total_events >= good_events)),
    CONSTRAINT ck_prv_slo_snapshots_achieved
        CHECK (achieved_pct IS NULL OR achieved_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_prv_slo_snapshots_state
        CHECK (compliance_state IN ('HEALTHY', 'AT_RISK', 'EXHAUSTED', 'NO_DATA')),
    CONSTRAINT ck_prv_slo_snapshots_details CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX idx_prv_slo_snapshots_latest
    ON prv_service_level_snapshots(service_level_objective_id, observed_at DESC);
CREATE INDEX idx_prv_slo_snapshots_brin_time
    ON prv_service_level_snapshots USING BRIN(observed_at);

CREATE TABLE prv_maintenance_windows (
    maintenance_window_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tracking_key VARCHAR(80) NOT NULL UNIQUE,
    title VARCHAR(240) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    service_key VARCHAR(80) REFERENCES prv_service_catalog(service_key),
    region_key VARCHAR(40) REFERENCES prv_regions(region_key),
    deployment_cell_id UUID REFERENCES prv_deployment_cells(deployment_cell_id),
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    impact_type VARCHAR(32) NOT NULL,
    expected_impact_seconds INTEGER NOT NULL DEFAULT 0,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    customer_notice_at TIMESTAMPTZ,
    minimum_notice_hours INTEGER NOT NULL DEFAULT 120,
    operation_id UUID REFERENCES prv_operations(operation_id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT REFERENCES prv_operators(provider_operator_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT REFERENCES prv_operators(provider_operator_id),
    CONSTRAINT ck_prv_maintenance_tracking_key
        CHECK (tracking_key = UPPER(BTRIM(tracking_key))
            AND tracking_key ~ '^[A-Z][A-Z0-9-]{2,79}$'),
    CONSTRAINT ck_prv_maintenance_scope
        CHECK (scope_type IN ('GLOBAL', 'SERVICE', 'REGION', 'CELL', 'TENANT')),
    CONSTRAINT ck_prv_maintenance_scope_target
        CHECK (
            (scope_type = 'GLOBAL'
                AND service_key IS NULL AND region_key IS NULL
                AND deployment_cell_id IS NULL AND provider_tenant_id IS NULL)
            OR (scope_type = 'SERVICE'
                AND service_key IS NOT NULL AND region_key IS NULL
                AND deployment_cell_id IS NULL AND provider_tenant_id IS NULL)
            OR (scope_type = 'REGION'
                AND service_key IS NULL AND region_key IS NOT NULL
                AND deployment_cell_id IS NULL AND provider_tenant_id IS NULL)
            OR (scope_type = 'CELL'
                AND service_key IS NULL AND region_key IS NULL
                AND deployment_cell_id IS NOT NULL AND provider_tenant_id IS NULL)
            OR (scope_type = 'TENANT'
                AND service_key IS NULL AND region_key IS NULL
                AND deployment_cell_id IS NULL AND provider_tenant_id IS NOT NULL)
        ),
    CONSTRAINT ck_prv_maintenance_impact
        CHECK (impact_type IN (
            'NO_IMPACT', 'BRIEF_INTERRUPTION', 'DEGRADED_PERFORMANCE',
            'SERVICE_UNAVAILABLE', 'FAILOVER', 'OTHER')),
    CONSTRAINT ck_prv_maintenance_impact_seconds CHECK (expected_impact_seconds >= 0),
    CONSTRAINT ck_prv_maintenance_no_impact
        CHECK (impact_type <> 'NO_IMPACT' OR expected_impact_seconds = 0),
    CONSTRAINT ck_prv_maintenance_state
        CHECK (lifecycle_state IN ('DRAFT', 'SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_prv_maintenance_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_prv_maintenance_notice_window
        CHECK (customer_notice_at IS NULL OR customer_notice_at <= starts_at),
    CONSTRAINT ck_prv_maintenance_notice_hours CHECK (minimum_notice_hours BETWEEN 0 AND 720),
    CONSTRAINT ck_prv_maintenance_scheduled_notice
        CHECK (lifecycle_state NOT IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED')
            OR customer_notice_at IS NOT NULL),
    CONSTRAINT ck_prv_maintenance_minimum_notice
        CHECK (lifecycle_state NOT IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED')
            OR starts_at >= customer_notice_at + minimum_notice_hours * INTERVAL '1 hour')
);

CREATE INDEX idx_prv_maintenance_schedule
    ON prv_maintenance_windows(lifecycle_state, starts_at, ends_at);
CREATE INDEX idx_prv_maintenance_tenant
    ON prv_maintenance_windows(provider_tenant_id, starts_at DESC)
    WHERE provider_tenant_id IS NOT NULL;

CREATE TABLE prv_governance_controls (
    control_key VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    control_category VARCHAR(30) NOT NULL,
    control_behavior VARCHAR(20) NOT NULL,
    guidance_level VARCHAR(24) NOT NULL,
    risk_tier VARCHAR(10) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    evaluation_schema_version INTEGER NOT NULL DEFAULT 1,
    remediation_operation_type VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_governance_control_key
        CHECK (control_key = UPPER(BTRIM(control_key))
            AND control_key ~ '^[A-Z][A-Z0-9_]{2,99}$'),
    CONSTRAINT ck_prv_governance_control_category
        CHECK (control_category IN ('BASELINE', 'IDENTITY', 'RESILIENCE', 'DATA_GOVERNANCE')),
    CONSTRAINT ck_prv_governance_control_behavior
        CHECK (control_behavior IN ('PREVENTIVE', 'DETECTIVE', 'PROACTIVE')),
    CONSTRAINT ck_prv_governance_guidance
        CHECK (guidance_level IN ('MANDATORY', 'STRONGLY_RECOMMENDED', 'ELECTIVE')),
    CONSTRAINT ck_prv_governance_risk CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_prv_governance_state CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_prv_governance_schema_version CHECK (evaluation_schema_version > 0)
);

CREATE TABLE prv_governance_evaluations (
    governance_evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    control_key VARCHAR(100) NOT NULL REFERENCES prv_governance_controls(control_key),
    target_type VARCHAR(30) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    evaluation_result VARCHAR(24) NOT NULL,
    expected_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    observed_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    evaluator VARCHAR(120) NOT NULL,
    correlation_id VARCHAR(128),
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_governance_evaluation
        UNIQUE (control_key, target_type, target_id, evaluated_at),
    CONSTRAINT ck_prv_governance_target_type
        CHECK (target_type IN ('ORGANIZATION', 'TENANT', 'SERVICE_INSTANCE', 'DOMAIN', 'CELL')),
    CONSTRAINT ck_prv_governance_result
        CHECK (evaluation_result IN ('COMPLIANT', 'NON_COMPLIANT', 'ERROR', 'NOT_APPLICABLE')),
    CONSTRAINT ck_prv_governance_expected CHECK (jsonb_typeof(expected_snapshot) = 'object'),
    CONSTRAINT ck_prv_governance_observed CHECK (jsonb_typeof(observed_snapshot) = 'object')
);

CREATE INDEX idx_prv_governance_evaluations_latest
    ON prv_governance_evaluations(control_key, target_type, target_id, evaluated_at DESC);
CREATE INDEX idx_prv_governance_evaluations_drift
    ON prv_governance_evaluations(evaluation_result, evaluated_at DESC)
    WHERE evaluation_result IN ('NON_COMPLIANT', 'ERROR');
CREATE INDEX idx_prv_governance_evaluations_brin_time
    ON prv_governance_evaluations USING BRIN(evaluated_at);

INSERT INTO prv_service_level_objectives (
    objective_key, display_name, service_key, indicator_type, target_pct)
SELECT service.service_key || '.availability',
       service.display_name || ' availability',
       service.service_key,
       'AVAILABILITY',
       CASE service.criticality
           WHEN 'CRITICAL' THEN 99.9500
           WHEN 'HIGH' THEN 99.9000
           ELSE 99.5000
       END
  FROM prv_service_catalog service
 WHERE service.lifecycle_state = 'ACTIVE'
ON CONFLICT (objective_key) DO NOTHING;

INSERT INTO prv_service_level_snapshots (
    service_level_objective_id, window_started_at, window_ended_at,
    good_events, total_events, achieved_pct, error_budget_remaining_pct,
    burn_rate, compliance_state, measurement_source, details)
SELECT objective.service_level_objective_id,
       CURRENT_TIMESTAMP - make_interval(days => objective.compliance_window_days),
       CURRENT_TIMESTAMP,
       COUNT(instance.tenant_service_instance_id)
           FILTER (WHERE instance.lifecycle_state = 'READY'),
       COUNT(instance.tenant_service_instance_id),
       CASE WHEN COUNT(instance.tenant_service_instance_id) = 0 THEN NULL
            ELSE ROUND(
                COUNT(instance.tenant_service_instance_id)
                    FILTER (WHERE instance.lifecycle_state = 'READY')::numeric
                * 100 / COUNT(instance.tenant_service_instance_id), 5)
       END,
       CASE WHEN COUNT(instance.tenant_service_instance_id) = 0 THEN NULL
            ELSE ROUND((
                COUNT(instance.tenant_service_instance_id)
                    FILTER (WHERE instance.lifecycle_state = 'READY')::numeric
                * 100 / COUNT(instance.tenant_service_instance_id)
                - objective.target_pct
            ) * 100 / (100 - objective.target_pct), 5)
       END,
       CASE WHEN COUNT(instance.tenant_service_instance_id) = 0 THEN NULL
            ELSE ROUND((100 - (
                COUNT(instance.tenant_service_instance_id)
                    FILTER (WHERE instance.lifecycle_state = 'READY')::numeric
                * 100 / COUNT(instance.tenant_service_instance_id)
            )) / (100 - objective.target_pct), 5)
       END,
       CASE WHEN COUNT(instance.tenant_service_instance_id) = 0 THEN 'NO_DATA'
            WHEN COUNT(instance.tenant_service_instance_id)
                    FILTER (WHERE instance.lifecycle_state = 'READY')
                 = COUNT(instance.tenant_service_instance_id) THEN 'HEALTHY'
            ELSE 'AT_RISK'
       END,
       'bootstrap-readiness-proxy',
       jsonb_build_object(
           'measurementKind', 'SERVICE_INSTANCE_READINESS',
           'notice', 'Replace with request or minute based SLI telemetry in production')
  FROM prv_service_level_objectives objective
  LEFT JOIN prv_tenant_service_instances instance
    ON instance.service_key = objective.service_key
   AND instance.lifecycle_state <> 'RETIRED'
 WHERE objective.lifecycle_state = 'ACTIVE'
 GROUP BY objective.service_level_objective_id
ON CONFLICT DO NOTHING;

INSERT INTO prv_governance_controls (
    control_key, display_name, description, control_category,
    control_behavior, guidance_level, risk_tier, remediation_operation_type)
VALUES
    ('SERVICE_BASELINE_CURRENT', 'Service baseline current',
     'Applied service schema and desired configuration schema must match.',
     'BASELINE', 'DETECTIVE', 'MANDATORY', 'L2', 'SERVICE_RECONCILE'),
    ('SERVICE_INSTANCE_READY', 'Service instance ready',
     'Active tenant service instances must remain in READY state.',
     'RESILIENCE', 'DETECTIVE', 'MANDATORY', 'L2', 'SERVICE_RECONCILE'),
    ('PRIMARY_DOMAIN_VERIFIED', 'Primary domain verified',
     'Every active tenant must have one verified primary login domain.',
     'IDENTITY', 'PROACTIVE', 'STRONGLY_RECOMMENDED', 'L2', 'DOMAIN_VERIFY')
ON CONFLICT (control_key) DO NOTHING;

INSERT INTO prv_governance_evaluations (
    control_key, target_type, target_id, provider_tenant_id,
    evaluation_result, expected_snapshot, observed_snapshot, evaluator)
SELECT 'SERVICE_BASELINE_CURRENT',
       'SERVICE_INSTANCE',
       instance.tenant_service_instance_id::text,
       instance.provider_tenant_id,
       CASE WHEN instance.applied_schema_version IS NOT DISTINCT FROM
                      instance.configuration_schema_version
            THEN 'COMPLIANT' ELSE 'NON_COMPLIANT' END,
       jsonb_build_object('schemaVersion', instance.configuration_schema_version),
       jsonb_build_object('schemaVersion', instance.applied_schema_version),
       'flyway-v17-baseline'
  FROM prv_tenant_service_instances instance
 WHERE instance.lifecycle_state <> 'RETIRED';

INSERT INTO prv_governance_evaluations (
    control_key, target_type, target_id, provider_tenant_id,
    evaluation_result, expected_snapshot, observed_snapshot, evaluator)
SELECT 'SERVICE_INSTANCE_READY',
       'SERVICE_INSTANCE',
       instance.tenant_service_instance_id::text,
       instance.provider_tenant_id,
       CASE WHEN instance.lifecycle_state = 'READY'
            THEN 'COMPLIANT' ELSE 'NON_COMPLIANT' END,
       '{"lifecycleState":"READY"}'::jsonb,
       jsonb_build_object('lifecycleState', instance.lifecycle_state),
       'flyway-v17-baseline'
  FROM prv_tenant_service_instances instance
 WHERE instance.lifecycle_state <> 'RETIRED';

INSERT INTO prv_governance_evaluations (
    control_key, target_type, target_id, provider_tenant_id,
    evaluation_result, expected_snapshot, observed_snapshot, evaluator)
SELECT 'PRIMARY_DOMAIN_VERIFIED',
       'TENANT',
       tenant.provider_tenant_id::text,
       tenant.provider_tenant_id,
       CASE WHEN EXISTS (
           SELECT 1
             FROM prv_tenant_domains domain
            WHERE domain.provider_tenant_id = tenant.provider_tenant_id
              AND domain.primary_domain = TRUE
              AND domain.verification_state = 'VERIFIED'
       ) THEN 'COMPLIANT' ELSE 'NON_COMPLIANT' END,
       '{"verifiedPrimaryDomain":true}'::jsonb,
       jsonb_build_object('verifiedPrimaryDomain', EXISTS (
           SELECT 1
             FROM prv_tenant_domains domain
            WHERE domain.provider_tenant_id = tenant.provider_tenant_id
              AND domain.primary_domain = TRUE
              AND domain.verification_state = 'VERIFIED'
       )),
       'flyway-v17-baseline'
  FROM prv_tenants tenant
 WHERE tenant.lifecycle_state = 'ACTIVE';

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('RELIABILITY_READ', 'Read reliability controls', 'L1',
     'View SLOs, error budgets, governance drift, and planned maintenance'),
    ('MAINTENANCE_WRITE', 'Manage planned maintenance', 'L3',
     'Schedule customer-impacting maintenance windows and notices')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'RELIABILITY_READ'),
    ('PROVIDER_ADMIN', 'MAINTENANCE_WRITE'),
    ('PROVIDER_OPERATOR', 'RELIABILITY_READ'),
    ('PROVIDER_OPERATOR', 'MAINTENANCE_WRITE'),
    ('PROVIDER_SUPPORT', 'RELIABILITY_READ'),
    ('PROVIDER_AUDITOR', 'RELIABILITY_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;
