ALTER TABLE ppl_organization_change_type_catalog
    ADD CONSTRAINT uk_ppl_org_change_type_target
        UNIQUE (change_type, target_kind);

ALTER TABLE ppl_organization_scenario_changes
    ADD CONSTRAINT fk_ppl_org_scenario_change_type_target
        FOREIGN KEY (change_type, target_kind)
        REFERENCES ppl_organization_change_type_catalog(change_type, target_kind);

COMMENT ON CONSTRAINT fk_ppl_org_scenario_change_type_target
    ON ppl_organization_scenario_changes IS
    'Prevents a scenario payload from pairing a registered change type with an incompatible target kind.';
