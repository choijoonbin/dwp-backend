-- Bind every governed decision to the immutable validation evidence that
-- justified it. Legacy workflow rows remain explicit rather than pretending
-- that evidence existed before this control was introduced.

ALTER TABLE ppl_organization_scenario_changes
    ADD COLUMN payload_schema_version INTEGER NOT NULL DEFAULT 1;

UPDATE ppl_organization_scenario_changes change
   SET payload_schema_version = catalog.payload_schema_version
  FROM ppl_organization_change_type_catalog catalog
 WHERE catalog.change_type = change.change_type;

ALTER TABLE ppl_organization_scenario_changes
    ADD CONSTRAINT ck_ppl_org_scenario_change_payload_version
        CHECK (payload_schema_version > 0);

ALTER TABLE ppl_organization_scenario_validation_runs
    DROP CONSTRAINT ck_ppl_org_scenario_validation_trigger;

ALTER TABLE ppl_organization_scenario_validation_runs
    ADD CONSTRAINT ck_ppl_org_scenario_validation_trigger
        CHECK (trigger_type IN ('MANUAL', 'SUBMIT', 'APPROVE', 'REJECT', 'PUBLISH')),
    ADD CONSTRAINT uk_ppl_org_scenario_validation_tenant
        UNIQUE (
            tenant_id,
            organization_scenario_id,
            organization_scenario_validation_run_id);

ALTER TABLE ppl_organization_scenario_approvals
    ADD COLUMN request_validation_run_id UUID,
    ADD COLUMN decision_validation_run_id UUID,
    ADD COLUMN evidence_binding_state VARCHAR(24) NOT NULL DEFAULT 'BOUND';

UPDATE ppl_organization_scenario_approvals
   SET evidence_binding_state = 'LEGACY_UNBOUND';

ALTER TABLE ppl_organization_scenario_approvals
    ADD CONSTRAINT fk_ppl_org_scenario_approval_request_evidence
        FOREIGN KEY (
            tenant_id,
            organization_scenario_id,
            request_validation_run_id)
        REFERENCES ppl_organization_scenario_validation_runs (
            tenant_id,
            organization_scenario_id,
            organization_scenario_validation_run_id),
    ADD CONSTRAINT fk_ppl_org_scenario_approval_decision_evidence
        FOREIGN KEY (
            tenant_id,
            organization_scenario_id,
            decision_validation_run_id)
        REFERENCES ppl_organization_scenario_validation_runs (
            tenant_id,
            organization_scenario_id,
            organization_scenario_validation_run_id),
    ADD CONSTRAINT ck_ppl_org_scenario_approval_evidence_state
        CHECK (evidence_binding_state IN ('BOUND', 'LEGACY_UNBOUND')),
    ADD CONSTRAINT ck_ppl_org_scenario_approval_request_evidence
        CHECK (evidence_binding_state = 'LEGACY_UNBOUND'
            OR request_validation_run_id IS NOT NULL),
    ADD CONSTRAINT ck_ppl_org_scenario_approval_decision_evidence
        CHECK (lifecycle_state NOT IN ('APPROVED', 'REJECTED')
            OR evidence_binding_state = 'LEGACY_UNBOUND'
            OR decision_validation_run_id IS NOT NULL);

ALTER TABLE ppl_organization_scenarios
    ADD COLUMN publication_validation_run_id UUID,
    ADD COLUMN publication_evidence_state VARCHAR(24) NOT NULL DEFAULT 'BOUND';

UPDATE ppl_organization_scenarios
   SET publication_evidence_state = 'LEGACY_UNBOUND'
 WHERE lifecycle_state = 'PUBLISHED';

ALTER TABLE ppl_organization_scenarios
    ADD CONSTRAINT fk_ppl_org_scenario_publication_evidence
        FOREIGN KEY (
            tenant_id,
            organization_scenario_id,
            publication_validation_run_id)
        REFERENCES ppl_organization_scenario_validation_runs (
            tenant_id,
            organization_scenario_id,
            organization_scenario_validation_run_id),
    ADD CONSTRAINT ck_ppl_org_scenario_publication_evidence_state
        CHECK (publication_evidence_state IN ('BOUND', 'LEGACY_UNBOUND')),
    ADD CONSTRAINT ck_ppl_org_scenario_publication_evidence
        CHECK (lifecycle_state <> 'PUBLISHED'
            OR publication_evidence_state = 'LEGACY_UNBOUND'
            OR publication_validation_run_id IS NOT NULL);

CREATE INDEX idx_ppl_org_scenario_approval_request_evidence
    ON ppl_organization_scenario_approvals (
        tenant_id, request_validation_run_id)
    WHERE request_validation_run_id IS NOT NULL;

CREATE INDEX idx_ppl_org_scenario_approval_decision_evidence
    ON ppl_organization_scenario_approvals (
        tenant_id, decision_validation_run_id)
    WHERE decision_validation_run_id IS NOT NULL;

CREATE INDEX idx_ppl_org_scenario_publication_evidence
    ON ppl_organization_scenarios (
        tenant_id, publication_validation_run_id)
    WHERE publication_validation_run_id IS NOT NULL;

COMMENT ON COLUMN ppl_organization_scenario_changes.payload_schema_version IS
    'Immutable payload contract version used to interpret before/after JSON snapshots.';
COMMENT ON COLUMN ppl_organization_scenario_approvals.request_validation_run_id IS
    'Immutable decision pack used when the approval request was submitted.';
COMMENT ON COLUMN ppl_organization_scenario_approvals.decision_validation_run_id IS
    'Immutable decision pack used by the independent approver or rejector.';
COMMENT ON COLUMN ppl_organization_scenarios.publication_validation_run_id IS
    'Immutable decision pack used immediately before publishing the effective change.';
