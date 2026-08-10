-- Position plans are financial and structural records, so their identifiers and
-- monetary pairs must remain valid even when written outside the REST service.
ALTER TABLE ppl_positions
    ADD CONSTRAINT ck_ppl_positions_key_format
        CHECK (position_key = UPPER(BTRIM(position_key))
            AND position_key ~ '^[A-Z0-9][A-Z0-9._-]{2,99}$'),
    ADD CONSTRAINT ck_ppl_positions_cost_pair
        CHECK ((annual_cost_amount IS NULL) = (cost_currency IS NULL)),
    ADD CONSTRAINT ck_ppl_positions_currency
        CHECK (cost_currency IS NULL OR cost_currency ~ '^[A-Z]{3}$');

ALTER TABLE ppl_organization_scenario_changes
    ADD CONSTRAINT ck_ppl_org_scenario_changes_currency
        CHECK (cost_currency IS NULL OR cost_currency ~ '^[A-Z]{3}$');

CREATE UNIQUE INDEX uk_ppl_org_scenario_change_target_type
    ON ppl_organization_scenario_changes (
        tenant_id, organization_scenario_id, change_type, target_reference)
    WHERE change_type IN (
        'MOVE_ORGANIZATION', 'MOVE_POSITION', 'CREATE_POSITION', 'CLOSE_POSITION');

COMMENT ON INDEX uk_ppl_org_scenario_change_target_type IS
    'Prevents duplicate executable operations for one target inside an organization scenario.';
