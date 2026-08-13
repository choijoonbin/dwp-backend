CREATE TABLE prv_support_access_requests (
    support_access_request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    requester_operator_id BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL',
    access_mode VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    justification VARCHAR(1000) NOT NULL,
    duration_minutes INTEGER NOT NULL,
    approval_reference VARCHAR(160),
    customer_approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    risk_tier VARCHAR(10) NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    decision_due_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by BIGINT REFERENCES prv_operators(provider_operator_id),
    decision_reason VARCHAR(1000),
    activated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancelled_by BIGINT REFERENCES prv_operators(provider_operator_id),
    cancellation_reason VARCHAR(1000),
    post_review_state VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
    post_reviewed_at TIMESTAMPTZ,
    post_reviewed_by BIGINT REFERENCES prv_operators(provider_operator_id),
    post_review_summary VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT uk_prv_support_access_request_key UNIQUE (requester_operator_id, request_key),
    CONSTRAINT ck_prv_support_access_request_state CHECK (
        lifecycle_state IN (
            'PENDING_APPROVAL', 'APPROVED', 'DENIED', 'CANCELLED',
            'EXPIRED', 'ACTIVATED', 'COMPLETED', 'REVIEWED'
        )
    ),
    CONSTRAINT ck_prv_support_access_request_mode
        CHECK (access_mode IN ('STANDARD', 'BREAK_GLASS')),
    CONSTRAINT ck_prv_support_access_duration CHECK (duration_minutes BETWEEN 5 AND 60),
    CONSTRAINT ck_prv_support_access_risk CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_prv_support_access_customer_approval CHECK (
        NOT customer_approval_required
        OR (approval_reference IS NOT NULL AND LENGTH(BTRIM(approval_reference)) > 0)
    ),
    CONSTRAINT ck_prv_support_access_decision CHECK (
        (lifecycle_state IN ('APPROVED', 'DENIED', 'ACTIVATED', 'COMPLETED', 'REVIEWED')
            AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND decision_reason IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED', 'DENIED', 'ACTIVATED', 'COMPLETED', 'REVIEWED')
    ),
    CONSTRAINT ck_prv_support_access_activation CHECK (
        (lifecycle_state IN ('ACTIVATED', 'COMPLETED', 'REVIEWED') AND activated_at IS NOT NULL)
        OR lifecycle_state NOT IN ('ACTIVATED', 'COMPLETED', 'REVIEWED')
    ),
    CONSTRAINT ck_prv_support_access_completion CHECK (
        (lifecycle_state IN ('COMPLETED', 'REVIEWED') AND completed_at IS NOT NULL
            AND post_review_state IN ('PENDING', 'COMPLETED'))
        OR lifecycle_state NOT IN ('COMPLETED', 'REVIEWED')
    ),
    CONSTRAINT ck_prv_support_access_cancellation CHECK (
        (lifecycle_state = 'CANCELLED' AND cancelled_at IS NOT NULL
            AND cancelled_by IS NOT NULL AND cancellation_reason IS NOT NULL)
        OR lifecycle_state <> 'CANCELLED'
    ),
    CONSTRAINT ck_prv_support_access_post_review CHECK (
        (post_review_state = 'COMPLETED' AND post_reviewed_at IS NOT NULL
            AND post_reviewed_by IS NOT NULL AND post_review_summary IS NOT NULL)
        OR post_review_state <> 'COMPLETED'
    ),
    CONSTRAINT ck_prv_support_access_review_state
        CHECK (post_review_state IN ('NOT_REQUIRED', 'PENDING', 'COMPLETED'))
);

CREATE TABLE prv_support_access_request_scopes (
    support_access_request_id UUID NOT NULL
        REFERENCES prv_support_access_requests(support_access_request_id) ON DELETE CASCADE,
    scope_code VARCHAR(80) NOT NULL REFERENCES prv_support_scope_catalog(scope_code),
    PRIMARY KEY (support_access_request_id, scope_code)
);

ALTER TABLE prv_support_sessions
    ADD COLUMN support_access_request_id UUID
        REFERENCES prv_support_access_requests(support_access_request_id),
    ADD CONSTRAINT uk_prv_support_sessions_request UNIQUE (support_access_request_id);

CREATE INDEX idx_prv_support_access_requests_queue
    ON prv_support_access_requests(lifecycle_state, decision_due_at, created_at DESC);
CREATE INDEX idx_prv_support_access_requests_tenant
    ON prv_support_access_requests(provider_tenant_id, created_at DESC);
CREATE INDEX idx_prv_support_access_requests_requester
    ON prv_support_access_requests(requester_operator_id, created_at DESC);

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('SUPPORT_ACCESS_REVIEW', 'Review support access', 'L3',
        'Independently approve or reject time-bound customer support access'),
    ('SUPPORT_POST_REVIEW', 'Review completed support access', 'L2',
        'Complete the post-access review for ended customer support sessions')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'SUPPORT_ACCESS_REVIEW'),
    ('PROVIDER_ADMIN', 'SUPPORT_POST_REVIEW'),
    ('PROVIDER_AUDITOR', 'SUPPORT_POST_REVIEW')
ON CONFLICT (role_code, permission_code) DO NOTHING;

COMMENT ON TABLE prv_support_access_requests IS
    'Immutable-purpose request ledger for standard provider access to a customer tenant.';
COMMENT ON COLUMN prv_support_access_requests.request_key IS
    'Caller-generated idempotency key scoped to the requesting operator.';
COMMENT ON COLUMN prv_support_access_requests.request_fingerprint IS
    'SHA-256 of the security-relevant request fields; prevents idempotency-key reuse.';
