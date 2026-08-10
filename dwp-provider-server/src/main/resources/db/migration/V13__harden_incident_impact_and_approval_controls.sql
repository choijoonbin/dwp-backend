ALTER TABLE prv_operation_approvals
    ADD CONSTRAINT ck_prv_operation_approvals_separation_of_duties
        CHECK (
            NOT separation_of_duties
            OR decided_by IS NULL
            OR requested_by <> decided_by
        );

CREATE TABLE prv_service_incident_impacts (
    service_incident_impact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_incident_id UUID NOT NULL
        REFERENCES prv_service_incidents(service_incident_id),
    target_type VARCHAR(20) NOT NULL,
    service_key VARCHAR(80) REFERENCES prv_service_catalog(service_key),
    region_key VARCHAR(40) REFERENCES prv_regions(region_key),
    deployment_cell_id UUID REFERENCES prv_deployment_cells(deployment_cell_id),
    provider_tenant_id UUID REFERENCES prv_tenants(provider_tenant_id),
    impact_state VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    first_observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recovered_at TIMESTAMPTZ,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_prv_service_incident_impacts_target_type
        CHECK (target_type IN ('SERVICE', 'REGION', 'CELL', 'TENANT')),
    CONSTRAINT ck_prv_service_incident_impacts_target
        CHECK (
            (target_type = 'SERVICE'
                AND service_key IS NOT NULL
                AND region_key IS NULL
                AND deployment_cell_id IS NULL
                AND provider_tenant_id IS NULL)
            OR
            (target_type = 'REGION'
                AND service_key IS NULL
                AND region_key IS NOT NULL
                AND deployment_cell_id IS NULL
                AND provider_tenant_id IS NULL)
            OR
            (target_type = 'CELL'
                AND service_key IS NULL
                AND region_key IS NULL
                AND deployment_cell_id IS NOT NULL
                AND provider_tenant_id IS NULL)
            OR
            (target_type = 'TENANT'
                AND service_key IS NULL
                AND region_key IS NULL
                AND deployment_cell_id IS NULL
                AND provider_tenant_id IS NOT NULL)
        ),
    CONSTRAINT ck_prv_service_incident_impacts_state
        CHECK (impact_state IN ('POTENTIAL', 'CONFIRMED', 'RECOVERED')),
    CONSTRAINT ck_prv_service_incident_impacts_recovery
        CHECK ((impact_state = 'RECOVERED') = (recovered_at IS NOT NULL)),
    CONSTRAINT ck_prv_service_incident_impacts_attributes
        CHECK (jsonb_typeof(attributes) = 'object')
);

CREATE UNIQUE INDEX uk_prv_service_incident_impacts_service
    ON prv_service_incident_impacts(service_incident_id, service_key)
    WHERE target_type = 'SERVICE';
CREATE UNIQUE INDEX uk_prv_service_incident_impacts_region
    ON prv_service_incident_impacts(service_incident_id, region_key)
    WHERE target_type = 'REGION';
CREATE UNIQUE INDEX uk_prv_service_incident_impacts_cell
    ON prv_service_incident_impacts(service_incident_id, deployment_cell_id)
    WHERE target_type = 'CELL';
CREATE UNIQUE INDEX uk_prv_service_incident_impacts_tenant
    ON prv_service_incident_impacts(service_incident_id, provider_tenant_id)
    WHERE target_type = 'TENANT';
CREATE INDEX idx_prv_service_incident_impacts_target
    ON prv_service_incident_impacts(target_type, impact_state, first_observed_at DESC);

INSERT INTO prv_service_incident_impacts (
    service_incident_id, target_type, service_key, first_observed_at)
SELECT service_incident_id, 'SERVICE', service_key, detected_at
  FROM prv_service_incidents
 WHERE service_key IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO prv_service_incident_impacts (
    service_incident_id, target_type, region_key, first_observed_at)
SELECT service_incident_id, 'REGION', region_key, detected_at
  FROM prv_service_incidents
 WHERE region_key IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO prv_service_incident_impacts (
    service_incident_id, target_type, deployment_cell_id, first_observed_at)
SELECT service_incident_id, 'CELL', deployment_cell_id, detected_at
  FROM prv_service_incidents
 WHERE deployment_cell_id IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO prv_service_incident_impacts (
    service_incident_id, target_type, provider_tenant_id, first_observed_at)
SELECT service_incident_id, 'TENANT', provider_tenant_id, detected_at
  FROM prv_service_incidents
 WHERE provider_tenant_id IS NOT NULL
ON CONFLICT DO NOTHING;
