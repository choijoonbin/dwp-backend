-- Immutable per-command proof that an actor entitlement and the target membership resolved to the
-- same stable population inside the owner mutation transaction.

CREATE TABLE tim_work_regime_command_authority_evidence (
    tenant_id BIGINT NOT NULL,
    receipt_public_id UUID NOT NULL,
    work_regime_public_id UUID NOT NULL,
    actor_id BIGINT NOT NULL,
    gateway_scope_key VARCHAR(38) NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    grant_revision BIGINT NOT NULL,
    population_digest CHAR(64) NOT NULL,
    grant_digest CHAR(64) NOT NULL,
    worker_public_id UUID NOT NULL,
    people_assignment_public_id UUID NOT NULL,
    people_assignment_revision BIGINT NOT NULL,
    membership_revision BIGINT NOT NULL,
    membership_digest CHAR(64) NOT NULL,
    member_effective_from DATE NOT NULL,
    member_effective_to DATE,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, receipt_public_id),
    CONSTRAINT fk_tim_command_authority_receipt
        FOREIGN KEY (tenant_id, receipt_public_id)
        REFERENCES tim_command_receipts(tenant_id, public_id),
    CONSTRAINT fk_tim_command_authority_regime
        FOREIGN KEY (tenant_id, work_regime_public_id)
        REFERENCES tim_work_regime_versions(tenant_id, public_id),
    CONSTRAINT fk_tim_command_authority_population
        FOREIGN KEY (tenant_id, population_public_id)
        REFERENCES tim_target_population_projections(tenant_id, population_public_id),
    CONSTRAINT ck_tim_command_authority_actor CHECK (actor_id > 0),
    CONSTRAINT ck_tim_command_authority_gateway_scope
        CHECK (gateway_scope_key ~ '^scope-[0-9a-f]{32}$'),
    CONSTRAINT ck_tim_command_authority_revisions
        CHECK (population_revision > 0 AND grant_revision > 0
            AND people_assignment_revision >= 0 AND membership_revision > 0),
    CONSTRAINT ck_tim_command_authority_digests
        CHECK (population_digest ~ '^[0-9a-f]{64}$'
            AND grant_digest ~ '^[0-9a-f]{64}$'
            AND membership_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tim_command_authority_member_period
        CHECK (member_effective_to IS NULL OR member_effective_to > member_effective_from)
);

CREATE OR REPLACE FUNCTION tim_reject_command_authority_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'command target authority evidence is immutable';
END;
$function$;

CREATE TRIGGER trg_tim_command_authority_evidence_immutable
    BEFORE UPDATE OR DELETE ON tim_work_regime_command_authority_evidence
    FOR EACH ROW EXECUTE FUNCTION tim_reject_command_authority_evidence_mutation();

ALTER TABLE tim_work_regime_command_authority_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE tim_work_regime_command_authority_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tim_work_regime_command_authority_evidence_tenant_isolation
    ON tim_work_regime_command_authority_evidence
    USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

REVOKE ALL ON TABLE tim_work_regime_command_authority_evidence FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE tim_work_regime_command_authority_evidence
TO "${timeRuntimeRole}";

REVOKE ALL ON FUNCTION tim_reject_command_authority_evidence_mutation()
FROM PUBLIC;
