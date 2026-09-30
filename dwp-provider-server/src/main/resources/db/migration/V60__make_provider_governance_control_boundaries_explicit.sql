-- V58 intentionally established only an internal source of record.  Make the
-- control period and enforcement boundary explicit before using those records
-- to make an operator decision.  No column below represents a vendor meter,
-- invoice, registry signature, package distribution, or deployment receipt.

ALTER TABLE prv_tenant_resource_commitments
    ADD COLUMN control_period_start TIMESTAMPTZ,
    ADD COLUMN control_period_end TIMESTAMPTZ,
    ADD COLUMN control_mode VARCHAR(24) NOT NULL DEFAULT 'SOFT_ALERT',
    ADD COLUMN control_scope VARCHAR(40) NOT NULL DEFAULT 'INTERNAL_LEDGER_ONLY',
    ADD CONSTRAINT ck_prv_resource_commitment_period
        CHECK ((control_period_start IS NULL AND control_period_end IS NULL)
            OR (control_period_start IS NOT NULL
                AND control_period_end IS NOT NULL
                AND control_period_start < control_period_end)),
    ADD CONSTRAINT ck_prv_resource_commitment_mode
        CHECK (control_mode IN ('SOFT_ALERT', 'HARD_BLOCK')),
    ADD CONSTRAINT ck_prv_resource_commitment_scope
        CHECK (control_scope = 'INTERNAL_LEDGER_ONLY');

-- Each immutable row keeps the period that was in force when it was recorded.
-- This prevents a later period change from silently relabelling historical
-- internal evidence as current-period evidence.  Existing V58 rows remain
-- explicitly legacy/unconfigured rather than being assigned a fabricated date.
ALTER TABLE prv_tenant_resource_ledger
    ADD COLUMN control_period_start TIMESTAMPTZ,
    ADD COLUMN control_period_end TIMESTAMPTZ,
    ADD CONSTRAINT ck_prv_resource_ledger_period
        CHECK ((control_period_start IS NULL AND control_period_end IS NULL)
            OR (control_period_start IS NOT NULL
                AND control_period_end IS NOT NULL
                AND control_period_start < control_period_end));

CREATE INDEX idx_prv_resource_ledger_control_period
    ON prv_tenant_resource_ledger(
        provider_tenant_id, resource_key, control_period_start, control_period_end, recorded_at DESC);

-- A retire or purge request is an internal handoff record, never an execution
-- result.  The legal-hold owner is consulted conservatively for global holds;
-- tenant-scoped hold clearance still requires that owner's evidence.
CREATE TABLE prv_tenant_lifecycle_requests (
    lifecycle_request_id UUID PRIMARY KEY,
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    requested_action VARCHAR(24) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    hold_evaluation_state VARCHAR(40) NOT NULL,
    hold_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    execution_state VARCHAR(40) NOT NULL DEFAULT 'OWNER_HANDOFF_REQUIRED',
    justification VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    submitted_by BIGINT REFERENCES prv_operators(provider_operator_id),
    approved_by BIGINT REFERENCES prv_operators(provider_operator_id),
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_tenant_lifecycle_request_action
        CHECK (requested_action IN ('RETIRE', 'PURGE')),
    CONSTRAINT ck_prv_tenant_lifecycle_request_state
        CHECK (lifecycle_state IN (
            'DRAFT', 'PENDING_APPROVAL', 'APPROVED_FOR_HANDOFF',
            'REJECTED', 'BLOCKED_BY_HOLD', 'CANCELLED')),
    CONSTRAINT ck_prv_tenant_lifecycle_request_hold
        CHECK (hold_evaluation_state IN (
            'OWNER_VERIFICATION_REQUIRED', 'ACTIVE_GLOBAL_LEGAL_HOLD')),
    CONSTRAINT ck_prv_tenant_lifecycle_request_hold_evidence
        CHECK (jsonb_typeof(hold_evidence) = 'array'),
    CONSTRAINT ck_prv_tenant_lifecycle_request_execution
        CHECK (execution_state = 'OWNER_HANDOFF_REQUIRED'),
    CONSTRAINT ck_prv_tenant_lifecycle_request_independent_approval
        CHECK (approved_by IS NULL
            OR (approved_by <> requested_by AND approved_by <> submitted_by)),
    CONSTRAINT ck_prv_tenant_lifecycle_request_approval
        CHECK ((lifecycle_state = 'APPROVED_FOR_HANDOFF'
                    AND approved_by IS NOT NULL
                    AND approved_at IS NOT NULL
                    AND decision_reason IS NOT NULL)
            OR lifecycle_state <> 'APPROVED_FOR_HANDOFF')
);

CREATE UNIQUE INDEX uq_prv_tenant_lifecycle_open_request
    ON prv_tenant_lifecycle_requests(provider_tenant_id, requested_action)
    WHERE lifecycle_state IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED_FOR_HANDOFF', 'BLOCKED_BY_HOLD');

CREATE INDEX idx_prv_tenant_lifecycle_request_tenant
    ON prv_tenant_lifecycle_requests(provider_tenant_id, updated_at DESC);

INSERT INTO prv_operator_permission_catalog (
    permission_code, display_name, risk_tier, description)
VALUES
    ('TENANT_LIFECYCLE_GOVERNANCE_APPROVE', 'Approve tenant retirement or purge handoff', 'L3',
     'Independently approve an internal retirement or purge owner-handoff request; cannot execute deletion')
ON CONFLICT (permission_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    risk_tier = EXCLUDED.risk_tier,
    description = EXCLUDED.description,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO prv_operator_role_permissions (role_code, permission_code)
VALUES
    ('PROVIDER_ADMIN', 'TENANT_LIFECYCLE_GOVERNANCE_APPROVE'),
    ('PROVIDER_CHANGE_APPROVER', 'TENANT_LIFECYCLE_GOVERNANCE_APPROVE'),
    ('PROVIDER_RELEASE_APPROVER', 'TENANT_LIFECYCLE_GOVERNANCE_APPROVE')
ON CONFLICT (role_code, permission_code) DO NOTHING;

COMMENT ON TABLE prv_tenant_lifecycle_requests IS
    'Internal retire/purge handoff requests. Approval is not tenant retirement, data deletion, or a legal-hold clearance; execution remains with the owner adapter.';

-- The JSONB payload remains portable, while these constraints prevent the
-- compatibility, target, stage, and recovery contracts from degrading back to
-- opaque blobs.  Runtime validation checks the nested fields and allowed values.
ALTER TABLE prv_product_artifact_manifests
    ADD CONSTRAINT ck_prv_artifact_compatibility_policy_shape CHECK (
        jsonb_typeof(compatibility_policy) = 'object'
        AND compatibility_policy ?& ARRAY['schema', 'clients', 'dependencies', 'capabilities', 'rollback']
        AND jsonb_typeof(compatibility_policy -> 'schema') = 'object'
        AND jsonb_typeof(compatibility_policy -> 'clients') = 'array'
        AND jsonb_typeof(compatibility_policy -> 'dependencies') = 'array'
        AND jsonb_typeof(compatibility_policy -> 'capabilities') = 'object'
        AND jsonb_typeof(compatibility_policy -> 'rollback') = 'object'),
    ADD CONSTRAINT ck_prv_artifact_compatibility_evidence_shape CHECK (
        compatibility_evidence = '{}'::jsonb
        OR (jsonb_typeof(compatibility_evidence) = 'object'
            AND compatibility_evidence ?& ARRAY['schema', 'clients', 'dependencies', 'capabilities', 'rollbackReadiness']
            AND jsonb_typeof(compatibility_evidence -> 'schema') = 'object'
            AND jsonb_typeof(compatibility_evidence -> 'clients') = 'array'
            AND jsonb_typeof(compatibility_evidence -> 'dependencies') = 'array'
            AND jsonb_typeof(compatibility_evidence -> 'capabilities') = 'object'
            AND jsonb_typeof(compatibility_evidence -> 'rollbackReadiness') = 'object'));

ALTER TABLE prv_artifact_rollout_plans
    ADD CONSTRAINT ck_prv_artifact_rollout_target_shape CHECK (
        jsonb_typeof(target_scope) = 'object'
        AND target_scope ?& ARRAY['environmentKey', 'tenantKeys', 'cohortKeys', 'targetPercentage']
        AND jsonb_typeof(target_scope -> 'tenantKeys') = 'array'
        AND jsonb_typeof(target_scope -> 'cohortKeys') = 'array'
        AND jsonb_typeof(target_scope -> 'targetPercentage') = 'number'),
    ADD CONSTRAINT ck_prv_artifact_rollout_stages_shape CHECK (
        jsonb_typeof(stages) = 'array' AND jsonb_array_length(stages) > 0),
    ADD CONSTRAINT ck_prv_artifact_rollout_recovery_shape CHECK (
        (rollback_feasibility = 'DECLARED'
            AND jsonb_typeof(rollback_plan) = 'object'
            AND jsonb_typeof(rollback_plan -> 'validationChecks') = 'array'
            AND jsonb_typeof(rollback_plan -> 'manualSteps') = 'array')
        OR (rollback_feasibility IN ('NOT_DECLARED', 'UNAVAILABLE')
            AND rollback_plan IS NULL));

COMMENT ON COLUMN prv_product_artifact_manifests.compatibility_policy IS
    'Typed internal compatibility declaration: schema, clients, dependencies, capability rules, and rollback requirement.';
COMMENT ON COLUMN prv_product_artifact_manifests.compatibility_evidence IS
    'Typed internal assessment evidence. It is not a registry signature, package receipt, or runtime deployment observation.';
