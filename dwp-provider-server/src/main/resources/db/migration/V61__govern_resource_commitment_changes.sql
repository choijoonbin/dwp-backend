-- Resource commitments are internal enforcement records, but their changes are still
-- high-impact customer controls.  Route them through an independently approved,
-- version-bound proposal.  Commercial changes must point at a published commercial
-- renewal for the same tenant organization.  Temporary overrides are time bounded and
-- are projected at read time, so expiry never depends on a deployment executor.

CREATE TABLE prv_resource_commitment_changes (
    change_request_id UUID PRIMARY KEY,
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    resource_key VARCHAR(120) NOT NULL,
    change_kind VARCHAR(32) NOT NULL,
    baseline_commitment_version BIGINT,
    baseline_definition JSONB,
    proposed_definition JSONB NOT NULL,
    commercial_renewal_revision_id UUID
        REFERENCES prv_subscription_renewal_revisions(renewal_revision_id),
    override_expires_at TIMESTAMPTZ,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL',
    reservation_state VARCHAR(24) NOT NULL DEFAULT 'UNAVAILABLE',
    justification VARCHAR(1000) NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    decision_due_at TIMESTAMPTZ NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by BIGINT REFERENCES prv_operators(provider_operator_id),
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    published_by BIGINT REFERENCES prv_operators(provider_operator_id),
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_resource_commitment_change_request
        UNIQUE (requested_by, request_key),
    CONSTRAINT ck_prv_resource_commitment_change_key
        CHECK (resource_key ~ '^[a-z][a-z0-9._-]{2,119}$'),
    CONSTRAINT ck_prv_resource_commitment_change_kind
        CHECK (change_kind IN ('CONTRACT_CHANGE', 'TEMPORARY_OVERRIDE')),
    CONSTRAINT ck_prv_resource_commitment_change_baseline
        CHECK ((baseline_commitment_version IS NULL AND baseline_definition IS NULL)
            OR (baseline_commitment_version IS NOT NULL
                AND baseline_commitment_version >= 0
                AND jsonb_typeof(baseline_definition) = 'object')),
    CONSTRAINT ck_prv_resource_commitment_change_proposed
        CHECK (jsonb_typeof(proposed_definition) = 'object'
            AND proposed_definition ?& ARRAY[
                'unit', 'quotaLimit', 'budgetLimit', 'currencyCode',
                'controlPeriodStartsAt', 'controlPeriodEndsAt', 'controlMode', 'lifecycleState']),
    CONSTRAINT ck_prv_resource_commitment_change_evidence
        CHECK ((change_kind = 'CONTRACT_CHANGE'
                    AND commercial_renewal_revision_id IS NOT NULL
                    AND override_expires_at IS NULL)
            OR (change_kind = 'TEMPORARY_OVERRIDE'
                    AND commercial_renewal_revision_id IS NULL
                    AND override_expires_at IS NOT NULL
                    AND baseline_commitment_version IS NOT NULL)),
    CONSTRAINT ck_prv_resource_commitment_change_state
        CHECK (lifecycle_state IN (
            'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'PUBLISHED', 'EXPIRED')),
    CONSTRAINT ck_prv_resource_commitment_change_reservation
        CHECK (reservation_state = 'UNAVAILABLE'),
    CONSTRAINT ck_prv_resource_commitment_change_independent_approval
        CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_prv_resource_commitment_change_decision
        CHECK ((lifecycle_state IN ('APPROVED', 'REJECTED', 'PUBLISHED')
                    AND decided_by IS NOT NULL
                    AND decided_at IS NOT NULL
                    AND decision_reason IS NOT NULL)
            OR lifecycle_state NOT IN ('APPROVED', 'REJECTED', 'PUBLISHED')),
    CONSTRAINT ck_prv_resource_commitment_change_publish
        CHECK ((lifecycle_state = 'PUBLISHED'
                    AND published_by IS NOT NULL
                    AND published_at IS NOT NULL)
            OR lifecycle_state <> 'PUBLISHED')
);

CREATE UNIQUE INDEX uq_prv_resource_commitment_change_open
    ON prv_resource_commitment_changes(provider_tenant_id, resource_key)
    WHERE lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED');

CREATE INDEX idx_prv_resource_commitment_change_queue
    ON prv_resource_commitment_changes(lifecycle_state, decision_due_at, updated_at DESC);

CREATE INDEX idx_prv_resource_commitment_change_override
    ON prv_resource_commitment_changes(
        provider_tenant_id, resource_key, override_expires_at DESC)
    WHERE change_kind = 'TEMPORARY_OVERRIDE' AND lifecycle_state = 'PUBLISHED';

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('RESOURCE_GOVERNANCE_APPROVE', 'Approve resource commitment changes', 'L3',
     'Independently approve contract-backed commitment changes or time-bounded internal overrides')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'RESOURCE_GOVERNANCE_APPROVE'),
    ('PROVIDER_CHANGE_APPROVER', 'RESOURCE_GOVERNANCE_APPROVE'),
    ('PROVIDER_CHANGE_APPROVER', 'RESOURCE_GOVERNANCE_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;

COMMENT ON TABLE prv_resource_commitment_changes IS
    'Independently approved resource-control proposals. Commercial evidence must already be published; vendor reservation, billing, and invoice execution remain unavailable.';
COMMENT ON COLUMN prv_resource_commitment_changes.reservation_state IS
    'Always UNAVAILABLE until a commercial reservation owner adapter exists.';
