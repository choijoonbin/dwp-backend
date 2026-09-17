ALTER TABLE wp_experience_facility_requests
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN assigned_to VARCHAR(160),
    ADD COLUMN service_provider VARCHAR(160),
    ADD COLUMN external_work_order_reference VARCHAR(160),
    ADD COLUMN sla_due_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_wp_facility_request_priority
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),
    ADD CONSTRAINT ck_wp_facility_request_assigned_to
        CHECK (assigned_to IS NULL OR length(trim(assigned_to)) BETWEEN 1 AND 160),
    ADD CONSTRAINT ck_wp_facility_request_service_provider
        CHECK (service_provider IS NULL OR length(trim(service_provider)) BETWEEN 1 AND 160),
    ADD CONSTRAINT ck_wp_facility_request_work_order_reference
        CHECK (external_work_order_reference IS NULL
            OR length(trim(external_work_order_reference)) BETWEEN 1 AND 160);

CREATE INDEX idx_wp_facility_request_active_sla
    ON wp_experience_facility_requests (tenant_id, sla_due_at, request_id)
    WHERE request_status IN ('OPEN', 'IN_PROGRESS') AND sla_due_at IS NOT NULL;

COMMENT ON COLUMN wp_experience_facility_requests.external_work_order_reference IS
    'Opaque provider-owned work-order reference. Presence does not prove connector health.';
COMMENT ON COLUMN wp_experience_facility_requests.sla_due_at IS
    'Operator-owned due instant used for triage; it is independent from provider verification.';
