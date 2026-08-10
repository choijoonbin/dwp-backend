-- Scenario alternatives must retain their origin so planners and auditors can
-- reconstruct how a future-state option was derived.
ALTER TABLE ppl_organization_scenarios
    ADD COLUMN source_scenario_id UUID,
    ADD CONSTRAINT fk_ppl_org_scenario_source
        FOREIGN KEY (tenant_id, source_scenario_id)
        REFERENCES ppl_organization_scenarios (
            tenant_id, organization_scenario_id),
    ADD CONSTRAINT ck_ppl_org_scenario_source_not_self
        CHECK (source_scenario_id IS NULL
            OR source_scenario_id <> organization_scenario_id);

CREATE INDEX idx_ppl_org_scenario_source
    ON ppl_organization_scenarios (tenant_id, source_scenario_id)
    WHERE source_scenario_id IS NOT NULL;

COMMENT ON COLUMN ppl_organization_scenarios.source_scenario_id IS
    'Immediate source scenario when this draft was cloned as an alternative.';
