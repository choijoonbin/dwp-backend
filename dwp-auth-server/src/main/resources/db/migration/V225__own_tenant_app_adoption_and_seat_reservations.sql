-- Tenant-owned application adoption is deliberately separate from catalog registration,
-- administrator presets, runtime deployment, and external SaaS licensing.  An ENABLED
-- row means that the Auth control plane accepted the tenant adoption after independent
-- review and activation.  It is not evidence that an external service was provisioned.

CREATE TABLE com_tenant_app_installations (
    installation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    product_key VARCHAR(80) NOT NULL,
    app_resource_key VARCHAR(255) NOT NULL,
    installation_kind VARCHAR(24) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    external_executor_state VARCHAR(24) NOT NULL,
    seat_capacity INTEGER,
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    submitted_at TIMESTAMPTZ,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    activated_by BIGINT,
    activated_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_tenant_app_installation UNIQUE (tenant_id, product_key),
    CONSTRAINT uk_tenant_app_installation_tenant_id UNIQUE (tenant_id, installation_id),
    CONSTRAINT ck_tenant_app_installation_product CHECK (
        product_key = LOWER(BTRIM(product_key))
        AND product_key ~ '^[a-z][a-z0-9-]{1,79}$'),
    CONSTRAINT ck_tenant_app_installation_resource CHECK (
        app_resource_key = UPPER(BTRIM(app_resource_key))
        AND app_resource_key ~ '^APP\.[A-Z][A-Z0-9_.-]{1,250}$'),
    CONSTRAINT ck_tenant_app_installation_kind CHECK (
        installation_kind IN ('INTERNAL_AUTH_CONTROLLED', 'EXTERNAL_SERVICE')),
    CONSTRAINT ck_tenant_app_installation_state CHECK (
        lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'ENABLED', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_tenant_app_installation_executor CHECK (
        (installation_kind = 'INTERNAL_AUTH_CONTROLLED'
            AND external_executor_state = 'NOT_REQUIRED')
        OR (installation_kind = 'EXTERNAL_SERVICE'
            AND external_executor_state = 'UNAVAILABLE')),
    CONSTRAINT ck_tenant_app_installation_capacity CHECK (
        seat_capacity IS NULL OR seat_capacity > 0),
    CONSTRAINT ck_tenant_app_installation_justification CHECK (
        length(BTRIM(justification)) BETWEEN 10 AND 1000),
    CONSTRAINT ck_tenant_app_installation_review CHECK (
        (lifecycle_state IN ('APPROVED', 'ENABLED')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by <> requested_by)
        OR lifecycle_state NOT IN ('APPROVED', 'ENABLED')),
    CONSTRAINT ck_tenant_app_installation_activation CHECK (
        (lifecycle_state = 'ENABLED'
            AND installation_kind = 'INTERNAL_AUTH_CONTROLLED'
            AND activated_by IS NOT NULL AND activated_at IS NOT NULL
            AND activation_receipt_id IS NOT NULL
            AND activated_by <> requested_by AND activated_by <> approved_by)
        OR lifecycle_state <> 'ENABLED'),
    CONSTRAINT ck_tenant_app_installation_version CHECK (version >= 0)
);

CREATE INDEX idx_tenant_app_installation_queue
    ON com_tenant_app_installations (tenant_id, lifecycle_state, updated_at DESC);

CREATE TABLE com_tenant_app_workforce_assignments (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    installation_id UUID NOT NULL REFERENCES com_tenant_app_installations(installation_id),
    principal_type VARCHAR(16) NOT NULL,
    principal_ref VARCHAR(160) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL',
    seat_quantity INTEGER NOT NULL DEFAULT 1,
    source_type VARCHAR(24) NOT NULL DEFAULT 'TENANT_DIRECT',
    external_settlement_state VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    valid_from TIMESTAMPTZ,
    valid_to TIMESTAMPTZ,
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    approved_by BIGINT,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    activated_by BIGINT,
    activated_at TIMESTAMPTZ,
    activation_receipt_id UUID,
    revoked_by BIGINT,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT fk_tenant_app_assignment_installation
        FOREIGN KEY (tenant_id, installation_id)
        REFERENCES com_tenant_app_installations(tenant_id, installation_id),
    CONSTRAINT ck_tenant_app_assignment_principal CHECK (
        principal_type = 'USER' AND principal_ref ~ '^[0-9]+$'),
    CONSTRAINT ck_tenant_app_assignment_state CHECK (
        lifecycle_state IN (
            'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DENIED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_tenant_app_assignment_source CHECK (
        source_type IN ('TENANT_DIRECT', 'INTERNAL_GROUP_PROJECTION')),
    CONSTRAINT ck_tenant_app_assignment_external_state CHECK (
        external_settlement_state IN ('NOT_REQUIRED', 'UNAVAILABLE')),
    CONSTRAINT ck_tenant_app_assignment_seats CHECK (seat_quantity = 1),
    CONSTRAINT ck_tenant_app_assignment_window CHECK (
        valid_to IS NULL OR valid_to > COALESCE(valid_from, created_at)),
    CONSTRAINT ck_tenant_app_assignment_justification CHECK (
        length(BTRIM(justification)) BETWEEN 10 AND 1000),
    CONSTRAINT ck_tenant_app_assignment_review CHECK (
        (lifecycle_state IN ('APPROVED', 'ACTIVE')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND approved_by <> requested_by
            AND approved_by::text <> principal_ref)
        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE')),
    CONSTRAINT ck_tenant_app_assignment_activation CHECK (
        (lifecycle_state = 'ACTIVE'
            AND activated_by IS NOT NULL AND activated_at IS NOT NULL
            AND activation_receipt_id IS NOT NULL
            AND activated_by <> requested_by AND activated_by <> approved_by
            AND activated_by::text <> principal_ref)
        OR lifecycle_state <> 'ACTIVE'),
    CONSTRAINT ck_tenant_app_assignment_revocation CHECK (
        (lifecycle_state IN ('REVOKED', 'EXPIRED')
            AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL)
        OR lifecycle_state NOT IN ('REVOKED', 'EXPIRED')),
    CONSTRAINT ck_tenant_app_assignment_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_tenant_app_assignment_open
    ON com_tenant_app_workforce_assignments (
        tenant_id, installation_id, principal_type, principal_ref)
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE');
CREATE INDEX idx_tenant_app_assignment_queue
    ON com_tenant_app_workforce_assignments (
        tenant_id, installation_id, lifecycle_state, updated_at DESC);

CREATE TABLE com_tenant_app_adoption_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    aggregate_type VARCHAR(24) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_id BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    resulting_version BIGINT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tenant_app_adoption_event_aggregate CHECK (
        aggregate_type IN ('INSTALLATION', 'WORKFORCE_ASSIGNMENT')),
    CONSTRAINT ck_tenant_app_adoption_event_version CHECK (resulting_version >= 0),
    CONSTRAINT ck_tenant_app_adoption_event_evidence CHECK (
        jsonb_typeof(evidence) = 'object'),
    UNIQUE (aggregate_type, aggregate_id, resulting_version)
);

CREATE OR REPLACE FUNCTION dwp_reject_tenant_app_adoption_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'tenant app adoption events are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_app_adoption_event_immutable
    BEFORE UPDATE OR DELETE ON com_tenant_app_adoption_events
    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_app_adoption_event_mutation();

COMMENT ON TABLE com_tenant_app_installations IS
    'Tenant Auth control-plane adoption owner. ENABLED is not external provisioning evidence.';
COMMENT ON COLUMN com_tenant_app_installations.external_executor_state IS
    'External provisioning stays UNAVAILABLE until a real executor adapter supplies a receipt.';
COMMENT ON TABLE com_tenant_app_workforce_assignments IS
    'Governed internal workforce seat reservations with requester/reviewer/activator lineage.';
COMMENT ON TABLE com_tenant_app_adoption_events IS
    'Immutable command receipts for internal tenant app adoption and seat reservations.';
