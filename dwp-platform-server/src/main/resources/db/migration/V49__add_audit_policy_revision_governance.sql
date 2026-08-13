ALTER TABLE sys_audit_retention_policies
    ADD COLUMN active_revision_id UUID,
    ADD COLUMN active_revision_number BIGINT NOT NULL DEFAULT 0;

CREATE TABLE sys_audit_policy_revisions (
    audit_policy_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    revision_number BIGINT NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    standard_retention_days INTEGER NOT NULL,
    extended_retention_days INTEGER NOT NULL,
    export_limit_rows INTEGER NOT NULL,
    require_export_reason BOOLEAN NOT NULL,
    integrity_enabled BOOLEAN NOT NULL,
    high_risk_threshold SMALLINT NOT NULL,
    baseline_revision_id UUID REFERENCES sys_audit_policy_revisions(audit_policy_revision_id),
    rollback_of_revision_id UUID REFERENCES sys_audit_policy_revisions(audit_policy_revision_id),
    incident_case_id UUID REFERENCES sys_audit_cases(case_id),
    change_reason VARCHAR(1000) NOT NULL,
    diff_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_sha256 CHAR(64) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_by VARCHAR(160),
    submitted_at TIMESTAMPTZ,
    published_by VARCHAR(160),
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sys_audit_policy_revision_number UNIQUE (tenant_id, revision_number),
    CONSTRAINT ck_sys_audit_policy_revision_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED',
            'PUBLISHED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT ck_sys_audit_policy_revision_standard
        CHECK (standard_retention_days BETWEEN 90 AND 3650),
    CONSTRAINT ck_sys_audit_policy_revision_extended
        CHECK (extended_retention_days BETWEEN 365 AND 3650),
    CONSTRAINT ck_sys_audit_policy_revision_order
        CHECK (extended_retention_days >= standard_retention_days),
    CONSTRAINT ck_sys_audit_policy_revision_export
        CHECK (export_limit_rows BETWEEN 100 AND 500000),
    CONSTRAINT ck_sys_audit_policy_revision_risk
        CHECK (high_risk_threshold BETWEEN 50 AND 100),
    CONSTRAINT ck_sys_audit_policy_revision_diff
        CHECK (jsonb_typeof(diff_data) = 'object'),
    CONSTRAINT ck_sys_audit_policy_revision_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

ALTER TABLE sys_audit_retention_policies
    ADD CONSTRAINT fk_sys_audit_policy_active_revision
        FOREIGN KEY (active_revision_id)
        REFERENCES sys_audit_policy_revisions(audit_policy_revision_id);

CREATE TABLE sys_audit_policy_approvals (
    audit_policy_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_policy_revision_id UUID NOT NULL
        REFERENCES sys_audit_policy_revisions(audit_policy_revision_id),
    tenant_id BIGINT NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by VARCHAR(160) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    decided_by VARCHAR(160),
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sys_audit_policy_revision_approval UNIQUE (audit_policy_revision_id),
    CONSTRAINT ck_sys_audit_policy_approval_state
        CHECK (lifecycle_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_sys_audit_policy_approval_expiry CHECK (expires_at > requested_at),
    CONSTRAINT ck_sys_audit_policy_approval_sod CHECK (
        decided_by IS NULL OR decided_by <> requested_by)
);

CREATE INDEX idx_sys_audit_policy_revision_queue
    ON sys_audit_policy_revisions(tenant_id, lifecycle_state, created_at DESC);

CREATE INDEX idx_sys_audit_policy_approval_queue
    ON sys_audit_policy_approvals(tenant_id, lifecycle_state, expires_at);

COMMENT ON TABLE sys_audit_policy_revisions IS
    'Immutable policy snapshots and review evidence for governed audit retention changes.';
COMMENT ON COLUMN sys_audit_policy_revisions.rollback_of_revision_id IS
    'Published revision whose snapshot is being restored through the normal approval flow.';
COMMENT ON COLUMN sys_audit_policy_revisions.incident_case_id IS
    'Optional audit investigation that justifies an emergency or corrective policy change.';
