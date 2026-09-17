CREATE TABLE prv_tenant_resource_commitments (
    provider_tenant_id UUID NOT NULL REFERENCES prv_tenants(provider_tenant_id),
    resource_key VARCHAR(120) NOT NULL,
    unit VARCHAR(24) NOT NULL,
    quota_limit NUMERIC(24, 6),
    budget_limit NUMERIC(24, 6),
    currency_code CHAR(3),
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    source_system VARCHAR(32) NOT NULL DEFAULT 'INTERNAL_CONTROL_PLANE',
    external_feed_state VARCHAR(24) NOT NULL DEFAULT 'UNAVAILABLE',
    version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_tenant_id, resource_key),
    CONSTRAINT ck_prv_resource_commitment_key
        CHECK (resource_key ~ '^[a-z][a-z0-9._-]{2,119}$'),
    CONSTRAINT ck_prv_resource_commitment_unit
        CHECK (unit IN ('SEAT', 'GIB', 'REQUEST', 'CURRENCY_MINOR', 'COUNT')),
    CONSTRAINT ck_prv_resource_commitment_limits
        CHECK ((quota_limit IS NULL OR quota_limit >= 0)
            AND (budget_limit IS NULL OR budget_limit >= 0)),
    CONSTRAINT ck_prv_resource_commitment_currency
        CHECK ((budget_limit IS NULL AND currency_code IS NULL)
            OR (budget_limit IS NOT NULL AND currency_code ~ '^[A-Z]{3}$')),
    CONSTRAINT ck_prv_resource_commitment_state
        CHECK (lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_prv_resource_commitment_source
        CHECK (source_system = 'INTERNAL_CONTROL_PLANE'),
    CONSTRAINT ck_prv_resource_commitment_external
        CHECK (external_feed_state = 'UNAVAILABLE')
);

CREATE TABLE prv_tenant_resource_ledger (
    ledger_entry_id UUID PRIMARY KEY,
    provider_tenant_id UUID NOT NULL,
    resource_key VARCHAR(120) NOT NULL,
    entry_type VARCHAR(24) NOT NULL,
    amount NUMERIC(24, 6) NOT NULL,
    unit VARCHAR(24) NOT NULL,
    currency_code CHAR(3),
    evidence_ref VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (provider_tenant_id, resource_key)
        REFERENCES prv_tenant_resource_commitments(provider_tenant_id, resource_key),
    CONSTRAINT uk_prv_resource_ledger_idempotency
        UNIQUE (provider_tenant_id, idempotency_key),
    CONSTRAINT ck_prv_resource_ledger_type CHECK (entry_type IN (
        'ALLOCATE', 'RELEASE', 'METER', 'ADJUST',
        'BUDGET_RESERVE', 'BUDGET_RELEASE', 'BUDGET_SPEND')),
    CONSTRAINT ck_prv_resource_ledger_amount CHECK (amount > 0),
    CONSTRAINT ck_prv_resource_ledger_unit
        CHECK (unit IN ('SEAT', 'GIB', 'REQUEST', 'CURRENCY_MINOR', 'COUNT')),
    CONSTRAINT ck_prv_resource_ledger_currency
        CHECK ((entry_type NOT LIKE 'BUDGET_%' AND currency_code IS NULL)
            OR (entry_type LIKE 'BUDGET_%' AND currency_code ~ '^[A-Z]{3}$'))
);

CREATE INDEX idx_prv_resource_ledger_tenant_time
    ON prv_tenant_resource_ledger(provider_tenant_id, resource_key, occurred_at DESC);

CREATE OR REPLACE FUNCTION prv_reject_resource_ledger_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Tenant resource ledger entries are immutable';
END;
$$;

CREATE TRIGGER trg_prv_resource_ledger_immutable
BEFORE UPDATE OR DELETE ON prv_tenant_resource_ledger
FOR EACH ROW EXECUTE FUNCTION prv_reject_resource_ledger_mutation();

CREATE TABLE prv_product_artifact_manifests (
    artifact_id UUID PRIMARY KEY,
    product_key VARCHAR(120) NOT NULL,
    artifact_version VARCHAR(80) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    manifest_schema_version INTEGER NOT NULL,
    manifest JSONB NOT NULL,
    compatibility_policy JSONB NOT NULL,
    declared_digest CHAR(64),
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    compatibility_state VARCHAR(24) NOT NULL DEFAULT 'NOT_EVALUATED',
    compatibility_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    signature_state VARCHAR(24) NOT NULL DEFAULT 'UNAVAILABLE',
    distribution_state VARCHAR(24) NOT NULL DEFAULT 'UNAVAILABLE',
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    updated_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_prv_product_artifact UNIQUE(product_key, artifact_version),
    CONSTRAINT ck_prv_product_artifact_key
        CHECK (product_key ~ '^[a-z][a-z0-9._-]{2,119}$'),
    CONSTRAINT ck_prv_product_artifact_type
        CHECK (artifact_type IN ('WEB_APP', 'SERVICE', 'WORKER', 'SCHEMA', 'CONFIG_BUNDLE')),
    CONSTRAINT ck_prv_product_artifact_schema CHECK (manifest_schema_version > 0),
    CONSTRAINT ck_prv_product_artifact_digest
        CHECK (declared_digest IS NULL OR declared_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_prv_product_artifact_lifecycle
        CHECK (lifecycle_state IN ('DRAFT', 'REVIEW_REQUIRED', 'APPROVED', 'RETIRED')),
    CONSTRAINT ck_prv_product_artifact_compatibility
        CHECK (compatibility_state IN ('NOT_EVALUATED', 'COMPATIBLE', 'REVIEW_REQUIRED', 'BLOCKED')),
    CONSTRAINT ck_prv_product_artifact_external_boundaries
        CHECK (signature_state = 'UNAVAILABLE' AND distribution_state = 'UNAVAILABLE')
);

CREATE TABLE prv_artifact_rollout_plans (
    rollout_plan_id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL REFERENCES prv_product_artifact_manifests(artifact_id),
    name VARCHAR(200) NOT NULL,
    target_scope JSONB NOT NULL,
    stages JSONB NOT NULL,
    rollback_plan JSONB,
    rollback_feasibility VARCHAR(24) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    executor_state VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    reason VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    approved_by BIGINT REFERENCES prv_operators(provider_operator_id),
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    decision_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_artifact_rollout_rollback
        CHECK (rollback_feasibility IN ('DECLARED', 'NOT_DECLARED', 'UNAVAILABLE')),
    CONSTRAINT ck_prv_artifact_rollout_state
        CHECK (lifecycle_state IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'READY', 'BLOCKED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_prv_artifact_rollout_executor CHECK (executor_state = 'UNAVAILABLE'),
    CONSTRAINT ck_prv_artifact_rollout_decision CHECK (
        (lifecycle_state IN ('APPROVED', 'READY', 'REJECTED')
            AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND decision_reason IS NOT NULL)
        OR lifecycle_state NOT IN ('APPROVED', 'READY', 'REJECTED'))
);

CREATE INDEX idx_prv_artifact_rollout_product
    ON prv_product_artifact_manifests(product_key, created_at DESC);
CREATE INDEX idx_prv_artifact_rollout_queue
    ON prv_artifact_rollout_plans(lifecycle_state, updated_at DESC);

CREATE TABLE prv_artifact_rollout_evidence (
    evidence_id UUID PRIMARY KEY,
    rollout_plan_id UUID NOT NULL REFERENCES prv_artifact_rollout_plans(rollout_plan_id),
    evidence_type VARCHAR(32) NOT NULL,
    evidence_state VARCHAR(24) NOT NULL,
    evidence JSONB NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'INTERNAL_CONTROL_PLANE',
    recorded_by BIGINT NOT NULL REFERENCES prv_operators(provider_operator_id),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_artifact_evidence_type
        CHECK (evidence_type IN ('COMPATIBILITY', 'PRE_FLIGHT', 'OBSERVATION', 'ROLLBACK_FEASIBILITY', 'MANUAL_RECEIPT')),
    CONSTRAINT ck_prv_artifact_evidence_state
        CHECK (evidence_state IN ('PASSED', 'FAILED', 'INCONCLUSIVE', 'NOT_DISPATCHED')),
    CONSTRAINT ck_prv_artifact_evidence_source CHECK (source = 'INTERNAL_CONTROL_PLANE')
);

CREATE OR REPLACE FUNCTION prv_reject_artifact_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Artifact rollout evidence is immutable';
END;
$$;

CREATE TRIGGER trg_prv_artifact_evidence_immutable
BEFORE UPDATE OR DELETE ON prv_artifact_rollout_evidence
FOR EACH ROW EXECUTE FUNCTION prv_reject_artifact_evidence_mutation();

COMMENT ON TABLE prv_tenant_resource_ledger IS
    'Immutable internal allocation, metering, and budget evidence. Entries do not assert external SaaS usage, invoice, or billing facts.';
COMMENT ON TABLE prv_product_artifact_manifests IS
    'Product-agnostic artifact declarations. Registry signing and package distribution remain unavailable without an external owner adapter.';
COMMENT ON TABLE prv_artifact_rollout_plans IS
    'Governed rollout plans that may become internally ready. External execution remains unavailable and no deployment success is inferred.';
