ALTER TABLE spc_memberships
    ADD CONSTRAINT uk_spc_membership_scope UNIQUE (tenant_id, membership_id);

CREATE TABLE spc_policy_evaluations (
    evaluation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    space_id UUID,
    policy_type VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_ref VARCHAR(200) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    enforcement_mode VARCHAR(24) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    evaluator_type VARCHAR(20) NOT NULL,
    evaluator_ref VARCHAR(200),
    correlation_id VARCHAR(120),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_spc_policy_evaluation_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT ck_spc_policy_evaluation_type CHECK (
        policy_type IN ('SPACE_CREATION', 'SPACE_ACCESS', 'CONTENT_PUBLICATION',
                        'MEMBERSHIP_CHANGE', 'LIFECYCLE')),
    CONSTRAINT ck_spc_policy_evaluation_subject CHECK (
        subject_type IN ('SPACE_REQUEST', 'ACCESS_REQUEST', 'CONTENT',
                         'MEMBERSHIP', 'SPACE')),
    CONSTRAINT ck_spc_policy_evaluation_decision CHECK (
        decision IN ('ALLOW', 'DENY', 'REVIEW', 'BLOCK')),
    CONSTRAINT ck_spc_policy_evaluation_enforcement CHECK (
        enforcement_mode IN ('AUTO', 'POLICY', 'APPROVAL',
                             'OPEN_PUBLISH', 'OWNER_REVIEW', 'ADMIN_REVIEW',
                             'COMPLIANCE_REVIEW', 'SYSTEM')),
    CONSTRAINT ck_spc_policy_evaluation_risk CHECK (
        risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_spc_policy_evaluation_actor CHECK (
        evaluator_type IN ('USER', 'POLICY', 'SYSTEM', 'AGENT')),
    CONSTRAINT ck_spc_policy_evaluation_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE TABLE spc_entitlement_sync_items (
    sync_item_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    space_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    principal_type VARCHAR(20) NOT NULL,
    principal_ref VARCHAR(200) NOT NULL,
    resource_key VARCHAR(255) NOT NULL,
    resource_name VARCHAR(200) NOT NULL,
    permission_code VARCHAR(50) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    valid_until TIMESTAMPTZ,
    desired_state VARCHAR(20) NOT NULL,
    delivery_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    source_ref VARCHAR(160) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(120),
    locked_until TIMESTAMPTZ,
    external_grant_id VARCHAR(120),
    external_state VARCHAR(30),
    last_error VARCHAR(1000),
    last_attempt_at TIMESTAMPTZ,
    synchronized_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_spc_entitlement_membership_permission
        UNIQUE (tenant_id, membership_id, permission_code),
    CONSTRAINT uk_spc_entitlement_source UNIQUE (tenant_id, source_ref),
    CONSTRAINT fk_spc_entitlement_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT fk_spc_entitlement_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES spc_memberships(tenant_id, membership_id),
    CONSTRAINT ck_spc_entitlement_principal CHECK (principal_type IN ('USER', 'GROUP')),
    CONSTRAINT ck_spc_entitlement_permission CHECK (
        permission_code IN ('VIEW', 'CREATE', 'UPDATE', 'APPROVE', 'MANAGE')),
    CONSTRAINT ck_spc_entitlement_desired_state CHECK (
        desired_state IN ('GRANTED', 'REVOKED')),
    CONSTRAINT ck_spc_entitlement_delivery_state CHECK (
        delivery_state IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'RETRY', 'DEAD')),
    CONSTRAINT ck_spc_entitlement_attempts CHECK (attempt_count >= 0)
);

CREATE TABLE spc_reconciliation_runs (
    run_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    trigger_type VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    planned_count INTEGER NOT NULL DEFAULT 0,
    expired_count INTEGER NOT NULL DEFAULT 0,
    finding_count INTEGER NOT NULL DEFAULT 0,
    requested_by BIGINT,
    correlation_id VARCHAR(120),
    summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_spc_reconciliation_trigger CHECK (
        trigger_type IN ('SCHEDULED', 'MANUAL', 'RECOVERY')),
    CONSTRAINT ck_spc_reconciliation_state CHECK (
        lifecycle_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_spc_reconciliation_counts CHECK (
        planned_count >= 0 AND expired_count >= 0 AND finding_count >= 0),
    CONSTRAINT ck_spc_reconciliation_summary CHECK (jsonb_typeof(summary) = 'object')
);

CREATE TABLE spc_reconciliation_findings (
    finding_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES spc_tenants(tenant_id),
    space_id UUID,
    membership_id UUID,
    fingerprint CHAR(64) NOT NULL,
    finding_type VARCHAR(32) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    target_type VARCHAR(32) NOT NULL,
    target_ref VARCHAR(200) NOT NULL,
    title VARCHAR(300) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by BIGINT,
    resolution_note VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_spc_reconciliation_fingerprint UNIQUE (tenant_id, fingerprint),
    CONSTRAINT fk_spc_reconciliation_space FOREIGN KEY (tenant_id, space_id)
        REFERENCES spc_spaces(tenant_id, space_id),
    CONSTRAINT fk_spc_reconciliation_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES spc_memberships(tenant_id, membership_id),
    CONSTRAINT ck_spc_reconciliation_type CHECK (
        finding_type IN ('OWNERLESS_SPACE', 'ENTITLEMENT_DELIVERY',
                         'EXPIRED_MEMBERSHIP', 'LIFECYCLE_REVIEW')),
    CONSTRAINT ck_spc_reconciliation_severity CHECK (
        severity IN ('INFO', 'WARNING', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_spc_reconciliation_finding_state CHECK (
        lifecycle_state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_spc_reconciliation_target CHECK (
        target_type IN ('SPACE', 'MEMBERSHIP', 'SYNC_ITEM', 'LIFECYCLE_REVIEW')),
    CONSTRAINT ck_spc_reconciliation_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE INDEX idx_spc_policy_evaluation_subject
    ON spc_policy_evaluations (tenant_id, subject_type, subject_ref, evaluated_at DESC);
CREATE INDEX idx_spc_entitlement_delivery
    ON spc_entitlement_sync_items (delivery_state, next_attempt_at, created_at)
    WHERE delivery_state IN ('PENDING', 'RETRY', 'IN_PROGRESS', 'DEAD');
CREATE INDEX idx_spc_entitlement_space
    ON spc_entitlement_sync_items (tenant_id, space_id, desired_state, delivery_state);
CREATE INDEX idx_spc_reconciliation_run_history
    ON spc_reconciliation_runs (tenant_id, started_at DESC);
CREATE INDEX idx_spc_reconciliation_open
    ON spc_reconciliation_findings (tenant_id, lifecycle_state, severity, last_detected_at DESC)
    WHERE lifecycle_state <> 'RESOLVED';
CREATE UNIQUE INDEX uk_spc_lifecycle_open_control
    ON spc_lifecycle_reviews (tenant_id, space_id, review_type)
    WHERE status IN ('OPEN', 'OVERDUE');

COMMENT ON TABLE spc_entitlement_sync_items IS
    'Durable desired-state delivery queue from Space memberships to the central identity governance plane.';
COMMENT ON TABLE spc_reconciliation_findings IS
    'Idempotent operational findings for owner, lifecycle, and entitlement drift.';
COMMENT ON TABLE spc_policy_evaluations IS
    'Immutable policy decision evidence captured before governed Space state transitions.';
