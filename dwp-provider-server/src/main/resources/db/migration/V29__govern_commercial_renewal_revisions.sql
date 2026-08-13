CREATE TABLE prv_subscription_renewal_revisions (
    renewal_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_subscription_id UUID NOT NULL
        REFERENCES prv_organization_subscriptions(organization_subscription_id),
    revision_number INTEGER NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL',
    baseline_subscription_version BIGINT NOT NULL,
    baseline_service_plan_id UUID NOT NULL REFERENCES prv_service_plans(service_plan_id),
    baseline_ends_at TIMESTAMPTZ,
    baseline_contract_reference VARCHAR(160),
    target_service_plan_id UUID NOT NULL REFERENCES prv_service_plans(service_plan_id),
    proposed_ends_at TIMESTAMPTZ,
    proposed_contract_reference VARCHAR(160),
    reason VARCHAR(1000) NOT NULL,
    added_entitlements VARCHAR(120)[] NOT NULL DEFAULT ARRAY[]::varchar[],
    removed_entitlements VARCHAR(120)[] NOT NULL DEFAULT ARRAY[]::varchar[],
    impacted_tenants BIGINT NOT NULL DEFAULT 0,
    current_entitlement_count BIGINT NOT NULL,
    projected_entitlement_count BIGINT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    decision_due_at TIMESTAMPTZ NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by BIGINT REFERENCES prv_operators(provider_operator_id),
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    published_by BIGINT REFERENCES prv_operators(provider_operator_id),
    published_at TIMESTAMPTZ,
    execution_state VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    notification_state VARCHAR(40) NOT NULL DEFAULT 'DISABLED_PENDING_CONTRACT',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_subscription_renewal_revision
        UNIQUE (organization_subscription_id, revision_number),
    CONSTRAINT uk_prv_subscription_renewal_request_key
        UNIQUE (requested_by, request_key),
    CONSTRAINT ck_prv_subscription_renewal_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_prv_subscription_renewal_baseline CHECK (baseline_subscription_version >= 0),
    CONSTRAINT ck_prv_subscription_renewal_hashes CHECK (
        content_sha256 ~ '^[0-9a-f]{64}$' AND request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_prv_subscription_renewal_state CHECK (
        lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED', 'PUBLISHED')
    ),
    CONSTRAINT ck_prv_subscription_renewal_impact CHECK (
        impacted_tenants >= 0
        AND current_entitlement_count >= 0
        AND projected_entitlement_count >= 0
    ),
    CONSTRAINT ck_prv_subscription_renewal_decision CHECK (
        (lifecycle_state IN ('APPROVED', 'REJECTED', 'PUBLISHED')
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED', 'REJECTED', 'PUBLISHED')
    ),
    CONSTRAINT ck_prv_subscription_renewal_publish CHECK (
        (lifecycle_state = 'PUBLISHED' AND published_by IS NOT NULL AND published_at IS NOT NULL)
        OR lifecycle_state <> 'PUBLISHED'
    ),
    CONSTRAINT ck_prv_subscription_renewal_execution CHECK (
        execution_state IN ('NOT_STARTED', 'NOT_REQUIRED', 'MANUAL_ACTION_REQUIRED', 'COMPLETED')
    ),
    CONSTRAINT ck_prv_subscription_renewal_notification CHECK (
        notification_state IN ('DISABLED_PENDING_CONTRACT', 'NOT_REQUIRED', 'QUEUED', 'SENT', 'FAILED')
    )
);

CREATE INDEX idx_prv_subscription_renewal_queue
    ON prv_subscription_renewal_revisions(lifecycle_state, decision_due_at, requested_at DESC);
CREATE INDEX idx_prv_subscription_renewal_subscription
    ON prv_subscription_renewal_revisions(organization_subscription_id, revision_number DESC);

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('COMMERCIAL_WRITE', 'Request commercial renewal', 'L2',
        'Preview and submit versioned customer subscription renewal proposals'),
    ('COMMERCIAL_APPROVE', 'Approve commercial renewal', 'L3',
        'Independently approve or reject customer subscription renewal proposals')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'COMMERCIAL_WRITE'),
    ('PROVIDER_ADMIN', 'COMMERCIAL_APPROVE'),
    ('PROVIDER_OPERATOR', 'COMMERCIAL_WRITE')
ON CONFLICT (role_code, permission_code) DO NOTHING;

COMMENT ON TABLE prv_subscription_renewal_revisions IS
    'Versioned, independently approved renewal proposals. Publishing updates commercial metadata only; entitlement execution and customer notifications remain fail-closed until an adapter contract is approved.';
