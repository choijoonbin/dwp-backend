INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('FEATURE_ROLLOUT_READ', 'Read feature rollout control plane', 'L2',
     'Inspect feature definitions, rollout stages, evaluations, and history'),
    ('FEATURE_ROLLOUT_WRITE', 'Operate feature rollouts', 'L3',
     'Create, submit, activate, pause, advance, and roll back feature rollouts'),
    ('FEATURE_ROLLOUT_APPROVE', 'Approve feature rollouts', 'L3',
     'Approve or reject independently submitted feature rollout revisions')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_roles (role_code, display_name, description)
VALUES (
    'PROVIDER_RELEASE_APPROVER',
    'Provider release approver',
    'Independent approval for high-risk feature rollout revisions')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE';

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'FEATURE_ROLLOUT_READ'),
    ('PROVIDER_ADMIN', 'FEATURE_ROLLOUT_WRITE'),
    ('PROVIDER_ADMIN', 'FEATURE_ROLLOUT_APPROVE'),
    ('PROVIDER_OPERATOR', 'FEATURE_ROLLOUT_READ'),
    ('PROVIDER_OPERATOR', 'FEATURE_ROLLOUT_WRITE'),
    ('PROVIDER_AUDITOR', 'FEATURE_ROLLOUT_READ'),
    ('PROVIDER_RELEASE_APPROVER', 'FEATURE_ROLLOUT_READ'),
    ('PROVIDER_RELEASE_APPROVER', 'FEATURE_ROLLOUT_APPROVE')
ON CONFLICT (role_code, permission_code) DO NOTHING;

CREATE TABLE prv_feature_flags (
    feature_flag_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_key VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(240) NOT NULL,
    description VARCHAR(1200) NOT NULL,
    owner_service VARCHAR(120) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    default_value JSONB NOT NULL,
    configuration_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_tier VARCHAR(10) NOT NULL DEFAULT 'L2',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_prv_feature_flags_key
        CHECK (feature_key ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*){1,7}$'),
    CONSTRAINT ck_prv_feature_flags_type
        CHECK (value_type IN ('BOOLEAN', 'STRING', 'NUMBER', 'JSON')),
    CONSTRAINT ck_prv_feature_flags_risk CHECK (risk_tier IN ('L1', 'L2', 'L3')),
    CONSTRAINT ck_prv_feature_flags_state
        CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED', 'RETIRED'))
);

CREATE TABLE prv_feature_rollout_revisions (
    rollout_revision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_flag_id UUID NOT NULL REFERENCES prv_feature_flags(feature_flag_id),
    revision_number INTEGER NOT NULL,
    name VARCHAR(240) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    rollout_value JSONB NOT NULL,
    targeting JSONB NOT NULL DEFAULT '{}'::jsonb,
    strategy VARCHAR(24) NOT NULL,
    current_stage_order INTEGER,
    previous_revision_id UUID REFERENCES prv_feature_rollout_revisions(rollout_revision_id),
    rollback_of_revision_id UUID REFERENCES prv_feature_rollout_revisions(rollout_revision_id),
    justification VARCHAR(1200) NOT NULL,
    requested_by BIGINT NOT NULL,
    approved_by BIGINT,
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    paused_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_feature_rollout_revision
        UNIQUE (feature_flag_id, revision_number),
    CONSTRAINT ck_prv_feature_rollout_state CHECK (lifecycle_state IN (
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'PAUSED',
        'COMPLETED', 'REJECTED', 'ROLLED_BACK', 'CANCELLED')),
    CONSTRAINT ck_prv_feature_rollout_strategy
        CHECK (strategy IN ('RING', 'PERCENTAGE', 'ALL_AT_ONCE')),
    CONSTRAINT ck_prv_feature_rollout_approval_separation
        CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CONSTRAINT ck_prv_feature_rollout_revision_number CHECK (revision_number > 0)
);

CREATE TABLE prv_feature_rollout_stages (
    rollout_stage_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rollout_revision_id UUID NOT NULL
        REFERENCES prv_feature_rollout_revisions(rollout_revision_id) ON DELETE CASCADE,
    stage_order INTEGER NOT NULL,
    stage_name VARCHAR(160) NOT NULL,
    exposure_percentage NUMERIC(5,2) NOT NULL,
    minimum_observation_minutes INTEGER NOT NULL DEFAULT 0,
    health_gate JSONB NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_prv_feature_rollout_stage
        UNIQUE (rollout_revision_id, stage_order),
    CONSTRAINT ck_prv_feature_rollout_stage_order CHECK (stage_order > 0),
    CONSTRAINT ck_prv_feature_rollout_exposure
        CHECK (exposure_percentage > 0 AND exposure_percentage <= 100),
    CONSTRAINT ck_prv_feature_rollout_observation
        CHECK (minimum_observation_minutes >= 0),
    CONSTRAINT ck_prv_feature_rollout_stage_state
        CHECK (lifecycle_state IN ('PENDING', 'ACTIVE', 'COMPLETED', 'SKIPPED'))
);

CREATE TABLE prv_feature_rollout_approvals (
    rollout_approval_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rollout_revision_id UUID NOT NULL
        REFERENCES prv_feature_rollout_revisions(rollout_revision_id),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by BIGINT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by BIGINT,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(1200),
    CONSTRAINT ck_prv_feature_rollout_approval_state
        CHECK (lifecycle_state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_prv_feature_rollout_approval_separation
        CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

CREATE UNIQUE INDEX uk_prv_feature_rollout_pending_approval
    ON prv_feature_rollout_approvals(rollout_revision_id)
    WHERE lifecycle_state = 'PENDING';

CREATE TABLE prv_feature_evaluation_audit (
    feature_evaluation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_flag_id UUID NOT NULL REFERENCES prv_feature_flags(feature_flag_id),
    rollout_revision_id UUID REFERENCES prv_feature_rollout_revisions(rollout_revision_id),
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    reason_code VARCHAR(40) NOT NULL,
    exposure_percentage NUMERIC(5,2) NOT NULL,
    variant_hash CHAR(64) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_feature_evaluation_reason CHECK (reason_code IN (
        'DEFAULT', 'TARGET_MISS', 'PERCENTAGE_EXCLUDED', 'ROLLOUT_MATCH')),
    CONSTRAINT ck_prv_feature_evaluation_exposure
        CHECK (exposure_percentage >= 0 AND exposure_percentage <= 100)
);

CREATE INDEX idx_prv_feature_rollout_feature_state
    ON prv_feature_rollout_revisions(feature_flag_id, lifecycle_state, activated_at DESC);
CREATE INDEX idx_prv_feature_rollout_approval_queue
    ON prv_feature_rollout_approvals(lifecycle_state, requested_at);
CREATE INDEX idx_prv_feature_evaluation_tenant_time
    ON prv_feature_evaluation_audit(provider_tenant_id, evaluated_at DESC);

COMMENT ON TABLE prv_feature_rollout_revisions IS
    'Immutable-oriented provider feature rollout revisions. External deployment execution remains gated by D-13.';
COMMENT ON COLUMN prv_feature_flags.configuration_schema IS
    'Typed configuration schema. Secret values must be referenced by secret:// identifiers and are never stored inline.';
