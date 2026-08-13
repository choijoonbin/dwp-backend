INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('DATA_GOVERNANCE_WRITE', 'Author data governance policy', 'L3',
     'Create and submit versioned classification, retention, residency, and access policies'),
    ('DATA_GOVERNANCE_APPROVE', 'Approve data governance policy', 'L3',
     'Independently approve or reject data governance policy revisions')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_roles (role_code, display_name, description)
VALUES (
    'PROVIDER_DATA_APPROVER',
    'Provider data policy approver',
    'Independent approval for data governance policy revisions')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE';

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'DATA_GOVERNANCE_WRITE'),
    ('PROVIDER_ADMIN', 'DATA_GOVERNANCE_APPROVE'),
    ('PROVIDER_OPERATOR', 'DATA_GOVERNANCE_WRITE'),
    ('PROVIDER_DATA_APPROVER', 'DATA_GOVERNANCE_READ'),
    ('PROVIDER_DATA_APPROVER', 'DATA_GOVERNANCE_APPROVE')
ON CONFLICT (role_code, permission_code) DO NOTHING;

CREATE TABLE prv_data_policies (
    data_policy_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_key VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(240) NOT NULL,
    description VARCHAR(1200) NOT NULL,
    policy_type VARCHAR(30) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_ref VARCHAR(320),
    owner_service VARCHAR(120) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_prv_data_policies_key
        CHECK (policy_key ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*){1,7}$'),
    CONSTRAINT ck_prv_data_policies_type CHECK (policy_type IN (
        'CLASSIFICATION', 'MINIMIZATION', 'RESIDENCY', 'RETENTION',
        'DELETION', 'LEGAL_HOLD', 'RESTRICTED_FIELD', 'TENANT_RLS')),
    CONSTRAINT ck_prv_data_policies_scope
        CHECK (scope_type IN ('GLOBAL', 'DATABASE', 'ASSET')),
    CONSTRAINT ck_prv_data_policies_scope_ref
        CHECK ((scope_type = 'GLOBAL' AND scope_ref IS NULL)
            OR (scope_type <> 'GLOBAL' AND scope_ref IS NOT NULL)),
    CONSTRAINT ck_prv_data_policies_state
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

CREATE TABLE prv_data_policy_revisions (
    data_policy_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_policy_id UUID NOT NULL REFERENCES prv_data_policies(data_policy_id),
    revision_number INTEGER NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    policy_rule JSONB NOT NULL,
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    justification VARCHAR(1200) NOT NULL,
    previous_revision_id UUID REFERENCES prv_data_policy_revisions(data_policy_revision_id),
    rollback_of_revision_id UUID REFERENCES prv_data_policy_revisions(data_policy_revision_id),
    impact_snapshot JSONB,
    impact_hash CHAR(64),
    impact_previewed_at TIMESTAMPTZ,
    requested_by BIGINT NOT NULL,
    approved_by BIGINT,
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_data_policy_revision
        UNIQUE (data_policy_id, revision_number),
    CONSTRAINT ck_prv_data_policy_revision_state CHECK (lifecycle_state IN (
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'REJECTED',
        'SUPERSEDED', 'ROLLED_BACK', 'CANCELLED')),
    CONSTRAINT ck_prv_data_policy_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_prv_data_policy_revision_validity
        CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_prv_data_policy_revision_approval_separation
        CHECK (approved_by IS NULL OR approved_by <> requested_by)
);

CREATE UNIQUE INDEX uk_prv_data_policy_active_revision
    ON prv_data_policy_revisions(data_policy_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE prv_data_policy_approvals (
    data_policy_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_policy_revision_id UUID NOT NULL
        REFERENCES prv_data_policy_revisions(data_policy_revision_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by BIGINT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1200),
    CONSTRAINT ck_prv_data_policy_approval_state
        CHECK (lifecycle_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_prv_data_policy_approval_separation
        CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

CREATE UNIQUE INDEX uk_prv_data_policy_pending_approval
    ON prv_data_policy_approvals(data_policy_revision_id)
    WHERE lifecycle_state = 'PENDING';

CREATE INDEX idx_prv_data_policy_scope
    ON prv_data_policies(policy_type, scope_type, scope_ref, lifecycle_state);
CREATE INDEX idx_prv_data_policy_revision_state
    ON prv_data_policy_revisions(lifecycle_state, submitted_at, published_at DESC);

COMMENT ON TABLE prv_data_policy_revisions IS
    'Versioned data governance rules with immutable impact evidence and independent approval.';
