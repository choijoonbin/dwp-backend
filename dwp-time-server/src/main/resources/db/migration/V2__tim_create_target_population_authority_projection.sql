-- TIM owner-side projection for stable target populations and People assignment membership.
-- These are integration-owned projections. The runtime role can read them but cannot provision,
-- widen, revoke, or rewrite the authoritative scope data.

CREATE TABLE tim_target_population_projections (
    tenant_id BIGINT NOT NULL,
    population_public_id UUID NOT NULL,
    scope_public_ref VARCHAR(128) NOT NULL,
    projection_revision BIGINT NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    source_digest CHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, population_public_id),
    CONSTRAINT uk_tim_target_population_scope
        UNIQUE (tenant_id, scope_public_ref),
    CONSTRAINT ck_tim_target_population_scope_ref
        CHECK (scope_public_ref = 'population:' || population_public_id::TEXT),
    CONSTRAINT ck_tim_target_population_revision
        CHECK (projection_revision > 0),
    CONSTRAINT ck_tim_target_population_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_tim_target_population_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_tim_target_population_digest
        CHECK (source_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE tim_target_population_actor_grants (
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    gateway_scope_key VARCHAR(38) NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    grant_revision BIGINT NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    source_digest CHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, actor_id, gateway_scope_key),
    CONSTRAINT fk_tim_target_population_actor_grant
        FOREIGN KEY (tenant_id, population_public_id)
        REFERENCES tim_target_population_projections(tenant_id, population_public_id),
    CONSTRAINT ck_tim_target_population_actor
        CHECK (actor_id > 0),
    CONSTRAINT ck_tim_target_population_gateway_scope
        CHECK (gateway_scope_key ~ '^scope-[0-9a-f]{32}$'),
    CONSTRAINT ck_tim_target_population_actor_revisions
        CHECK (population_revision > 0 AND grant_revision > 0),
    CONSTRAINT ck_tim_target_population_actor_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_tim_target_population_actor_period
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_tim_target_population_actor_digest
        CHECK (source_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_tim_target_population_actor_lookup
    ON tim_target_population_actor_grants (
        tenant_id, actor_id, gateway_scope_key, lifecycle_state, valid_from, valid_to);

CREATE TABLE tim_target_population_members (
    tenant_id BIGINT NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    worker_public_id UUID NOT NULL,
    people_assignment_public_id UUID NOT NULL,
    people_assignment_revision BIGINT NOT NULL,
    membership_revision BIGINT NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    source_digest CHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    PRIMARY KEY (
        tenant_id, population_public_id, worker_public_id, people_assignment_public_id),
    CONSTRAINT fk_tim_target_population_member
        FOREIGN KEY (tenant_id, population_public_id)
        REFERENCES tim_target_population_projections(tenant_id, population_public_id),
    CONSTRAINT uk_tim_target_population_assignment
        UNIQUE (tenant_id, population_public_id, people_assignment_public_id),
    CONSTRAINT ck_tim_target_population_member_revisions
        CHECK (population_revision > 0 AND people_assignment_revision >= 0
            AND membership_revision > 0),
    CONSTRAINT ck_tim_target_population_member_state
        CHECK (lifecycle_state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_tim_target_population_member_period
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_tim_target_population_member_digest
        CHECK (source_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_tim_target_population_member_lookup
    ON tim_target_population_members (
        tenant_id, population_public_id, people_assignment_public_id,
        people_assignment_revision, lifecycle_state);

CREATE TABLE tim_work_plan_target_evidence (
    tenant_id BIGINT NOT NULL,
    work_plan_assignment_id BIGINT NOT NULL,
    population_public_id UUID NOT NULL,
    population_revision BIGINT NOT NULL,
    membership_revision BIGINT NOT NULL,
    people_assignment_revision BIGINT NOT NULL,
    author_actor_id BIGINT NOT NULL,
    author_gateway_scope_key VARCHAR(38) NOT NULL,
    author_grant_revision BIGINT NOT NULL,
    population_digest CHAR(64) NOT NULL,
    membership_digest CHAR(64) NOT NULL,
    grant_digest CHAR(64) NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, work_plan_assignment_id),
    CONSTRAINT fk_tim_work_plan_target_assignment
        FOREIGN KEY (tenant_id, work_plan_assignment_id)
        REFERENCES tim_work_plan_assignments(tenant_id, work_plan_assignment_id),
    CONSTRAINT fk_tim_work_plan_target_population
        FOREIGN KEY (tenant_id, population_public_id)
        REFERENCES tim_target_population_projections(tenant_id, population_public_id),
    CONSTRAINT ck_tim_work_plan_target_revisions
        CHECK (population_revision > 0 AND membership_revision > 0
            AND people_assignment_revision >= 0 AND author_grant_revision > 0),
    CONSTRAINT ck_tim_work_plan_target_actor
        CHECK (author_actor_id > 0),
    CONSTRAINT ck_tim_work_plan_target_gateway_scope
        CHECK (author_gateway_scope_key ~ '^scope-[0-9a-f]{32}$'),
    CONSTRAINT ck_tim_work_plan_target_digests
        CHECK (population_digest ~ '^[0-9a-f]{64}$'
            AND membership_digest ~ '^[0-9a-f]{64}$'
            AND grant_digest ~ '^[0-9a-f]{64}$')
);

CREATE OR REPLACE FUNCTION tim_reject_target_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
BEGIN
    RAISE EXCEPTION 'work-plan target evidence is immutable';
END;
$function$;

CREATE TRIGGER trg_tim_work_plan_target_evidence_immutable
    BEFORE UPDATE OR DELETE ON tim_work_plan_target_evidence
    FOR EACH ROW EXECUTE FUNCTION tim_reject_target_evidence_mutation();

DO $block$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tim_target_population_projections',
        'tim_target_population_actor_grants',
        'tim_target_population_members',
        'tim_work_plan_target_evidence'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON %I USING '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT) '
            || 'WITH CHECK '
            || '(tenant_id = NULLIF(current_setting(''dwp.tenant_id'', true), '''')::BIGINT)',
            table_name || '_tenant_isolation', table_name
        );
    END LOOP;
END;
$block$;

REVOKE ALL ON TABLE
    tim_target_population_projections,
    tim_target_population_actor_grants,
    tim_target_population_members,
    tim_work_plan_target_evidence
FROM PUBLIC;

GRANT SELECT ON TABLE
    tim_target_population_projections,
    tim_target_population_actor_grants,
    tim_target_population_members
TO "${timeRuntimeRole}";

GRANT SELECT, INSERT ON TABLE tim_work_plan_target_evidence
TO "${timeRuntimeRole}";

REVOKE ALL ON FUNCTION tim_reject_target_evidence_mutation()
FROM PUBLIC;
