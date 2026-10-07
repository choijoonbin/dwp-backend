-- TIM consumes only People-derived Gateway eligibility scopes. Legacy Auth
-- source keys cannot be upgraded because they do not carry workforce lineage.
DO $block$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tim_target_population_actor_grants
         WHERE gateway_scope_key !~ '^hcm-scope-[0-9a-f]{40}$'
        UNION ALL
        SELECT 1 FROM tim_work_plan_target_evidence
         WHERE author_gateway_scope_key !~ '^hcm-scope-[0-9a-f]{40}$'
        UNION ALL
        SELECT 1 FROM tim_work_regime_command_authority_evidence
         WHERE gateway_scope_key !~ '^hcm-scope-[0-9a-f]{40}$') THEN
        RAISE EXCEPTION
            'TIM contains legacy/noncanonical scope keys; rebuild the disposable projection feed';
    END IF;
END;
$block$;

ALTER TABLE tim_target_population_actor_grants
    DROP CONSTRAINT ck_tim_target_population_gateway_scope;
ALTER TABLE tim_work_plan_target_evidence
    DROP CONSTRAINT ck_tim_work_plan_target_gateway_scope;
ALTER TABLE tim_work_regime_command_authority_evidence
    DROP CONSTRAINT ck_tim_command_authority_gateway_scope;

ALTER TABLE tim_target_population_actor_grants
    ALTER COLUMN gateway_scope_key TYPE VARCHAR(50);
ALTER TABLE tim_work_plan_target_evidence
    ALTER COLUMN author_gateway_scope_key TYPE VARCHAR(50);
ALTER TABLE tim_work_regime_command_authority_evidence
    ALTER COLUMN gateway_scope_key TYPE VARCHAR(50);

ALTER TABLE tim_target_population_actor_grants
    ADD CONSTRAINT ck_tim_target_population_gateway_scope
        CHECK (gateway_scope_key ~ '^hcm-scope-[0-9a-f]{40}$');
ALTER TABLE tim_work_plan_target_evidence
    ADD CONSTRAINT ck_tim_work_plan_target_gateway_scope
        CHECK (author_gateway_scope_key ~ '^hcm-scope-[0-9a-f]{40}$');
ALTER TABLE tim_work_regime_command_authority_evidence
    ADD CONSTRAINT ck_tim_command_authority_gateway_scope
        CHECK (gateway_scope_key ~ '^hcm-scope-[0-9a-f]{40}$');
