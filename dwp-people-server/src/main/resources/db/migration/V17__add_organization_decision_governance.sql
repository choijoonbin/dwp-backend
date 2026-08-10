-- Governed workforce design must preserve the exact evidence used for a decision.
-- Validation runs are append-only so an approval can be reconstructed later.

INSERT INTO ppl_organization_change_type_catalog (
    change_type, display_name, target_kind, risk_tier, requires_approval)
VALUES ('MOVE_POSITION', 'Move position', 'POSITION', 'L3', TRUE)
ON CONFLICT (change_type) DO NOTHING;

ALTER TABLE ppl_position_relationships
    DROP CONSTRAINT ck_ppl_position_relationship_source_kind;

ALTER TABLE ppl_position_relationships
    ADD CONSTRAINT ck_ppl_position_relationship_source_kind
        CHECK (relationship_source IN ('HRIS', 'POSITION', 'INFERRED', 'SCENARIO'));

CREATE TABLE ppl_organization_scenario_validation_runs (
    organization_scenario_validation_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    organization_scenario_id UUID NOT NULL,
    scenario_version BIGINT NOT NULL,
    trigger_type VARCHAR(24) NOT NULL,
    decision_state VARCHAR(24) NOT NULL,
    readiness_score INTEGER NOT NULL,
    baseline_fingerprint CHAR(64) NOT NULL,
    observed_fingerprint CHAR(64) NOT NULL,
    baseline_current BOOLEAN NOT NULL,
    blocking_issue_count INTEGER NOT NULL DEFAULT 0,
    warning_count INTEGER NOT NULL DEFAULT 0,
    baseline_metrics JSONB NOT NULL,
    proposed_metrics JSONB NOT NULL,
    metric_deltas JSONB NOT NULL,
    decision_checks JSONB NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    evaluated_by BIGINT NOT NULL,
    correlation_id VARCHAR(128),
    CONSTRAINT fk_ppl_org_scenario_validation_scenario
        FOREIGN KEY (tenant_id, organization_scenario_id)
        REFERENCES ppl_organization_scenarios(tenant_id, organization_scenario_id),
    CONSTRAINT ck_ppl_org_scenario_validation_version CHECK (scenario_version >= 0),
    CONSTRAINT ck_ppl_org_scenario_validation_trigger
        CHECK (trigger_type IN ('MANUAL', 'SUBMIT', 'APPROVE', 'PUBLISH')),
    CONSTRAINT ck_ppl_org_scenario_validation_state
        CHECK (decision_state IN ('READY', 'REVIEW_REQUIRED', 'BLOCKED')),
    CONSTRAINT ck_ppl_org_scenario_validation_score
        CHECK (readiness_score BETWEEN 0 AND 100),
    CONSTRAINT ck_ppl_org_scenario_validation_counts
        CHECK (blocking_issue_count >= 0 AND warning_count >= 0),
    CONSTRAINT ck_ppl_org_scenario_validation_fingerprints
        CHECK (baseline_fingerprint ~ '^[0-9a-f]{64}$'
            AND observed_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_org_scenario_validation_json
        CHECK (jsonb_typeof(baseline_metrics) = 'object'
            AND jsonb_typeof(proposed_metrics) = 'object'
            AND jsonb_typeof(metric_deltas) = 'object'
            AND jsonb_typeof(decision_checks) = 'array')
);

CREATE INDEX idx_ppl_org_scenario_validation_history
    ON ppl_organization_scenario_validation_runs (
        tenant_id, organization_scenario_id, evaluated_at DESC);

CREATE INDEX idx_ppl_org_scenario_validation_blocked
    ON ppl_organization_scenario_validation_runs (
        tenant_id, decision_state, evaluated_at DESC)
    WHERE decision_state <> 'READY';

CREATE FUNCTION prevent_ppl_org_scenario_validation_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'organization scenario validation runs are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ppl_org_scenario_validation_append_only
    BEFORE UPDATE OR DELETE ON ppl_organization_scenario_validation_runs
    FOR EACH ROW EXECUTE FUNCTION prevent_ppl_org_scenario_validation_mutation();

COMMENT ON TABLE ppl_organization_scenario_validation_runs IS
    'Immutable decision evidence for organization scenarios, including baseline drift, metrics, policy checks, and readiness.';
