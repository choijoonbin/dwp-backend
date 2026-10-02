-- PAY consumes only People-derived Gateway eligibility scopes. Legacy Auth
-- source keys cannot be upgraded because they do not carry workforce lineage.
DO $block$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pay_legal_entity_scope_projections
         WHERE context_scope_key !~ '^hcm-scope-[0-9a-f]{40}$') THEN
        RAISE EXCEPTION
            'PAY contains legacy/noncanonical scope keys; rebuild the disposable projection feed';
    END IF;
END;
$block$;

ALTER TABLE pay_legal_entity_scope_projections
    DROP CONSTRAINT pay_legal_entity_scope_key_shape;

ALTER TABLE pay_legal_entity_scope_projections
    ALTER COLUMN context_scope_key TYPE VARCHAR(50);

ALTER TABLE pay_legal_entity_scope_projections
    ADD CONSTRAINT pay_legal_entity_scope_key_shape
        CHECK (context_scope_key ~ '^hcm-scope-[0-9a-f]{40}$');
