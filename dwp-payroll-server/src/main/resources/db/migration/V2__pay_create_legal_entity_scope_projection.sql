-- Payroll owns the materialized business membership behind opaque Gateway scope keys.
-- Runtime access is deliberately read-only; a trusted projection publisher builds and seals
-- revisions before they can become visible to resolution.

CREATE TABLE pay_legal_entity_scope_projections (
    tenant_id BIGINT NOT NULL,
    projection_id UUID NOT NULL,
    actor_id BIGINT NOT NULL,
    context_scope_key VARCHAR(38) NOT NULL,
    policy_revision VARCHAR(240) NOT NULL,
    authorization_revision VARCHAR(240) NOT NULL,
    projection_revision VARCHAR(240) NOT NULL,
    status VARCHAR(16) NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, projection_id),
    CONSTRAINT pay_legal_entity_scope_authority_unique
        UNIQUE (
            tenant_id, actor_id, context_scope_key,
            policy_revision, authorization_revision),
    CONSTRAINT pay_legal_entity_scope_positive
        CHECK (tenant_id > 0 AND actor_id > 0),
    CONSTRAINT pay_legal_entity_scope_key_shape
        CHECK (context_scope_key ~ '^scope-[0-9a-f]{32}$'),
    CONSTRAINT pay_legal_entity_scope_revisions_present
        CHECK (policy_revision ~ '^rollout-[0-9a-f]{64}$'
            AND authorization_revision ~ '^psr-[0-9a-f]{64}$'
            AND btrim(projection_revision) <> ''),
    CONSTRAINT pay_legal_entity_scope_status_valid
        CHECK (status IN ('BUILDING', 'ACTIVE', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT pay_legal_entity_scope_validity
        CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE UNIQUE INDEX pay_legal_entity_scope_one_active_idx
    ON pay_legal_entity_scope_projections (tenant_id, actor_id, context_scope_key)
    WHERE status = 'ACTIVE';

CREATE INDEX pay_legal_entity_scope_authority_idx
    ON pay_legal_entity_scope_projections (
        tenant_id, actor_id, context_scope_key,
        policy_revision, authorization_revision, valid_from, valid_until);

CREATE TABLE pay_legal_entity_scope_members (
    tenant_id BIGINT NOT NULL,
    projection_id UUID NOT NULL,
    legal_entity_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, projection_id, legal_entity_id),
    CONSTRAINT pay_legal_entity_scope_member_projection_fk
        FOREIGN KEY (tenant_id, projection_id)
        REFERENCES pay_legal_entity_scope_projections (tenant_id, projection_id)
        ON DELETE RESTRICT
);

CREATE INDEX pay_legal_entity_scope_member_lookup_idx
    ON pay_legal_entity_scope_members (tenant_id, legal_entity_id, projection_id);

CREATE FUNCTION pay_guard_legal_entity_scope_projection_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $pay_legal_entity_scope_projection_transition$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'BUILDING' THEN
            RAISE EXCEPTION 'Payroll legal-entity scope projections must start in BUILDING';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payroll legal-entity scope projections are append-only';
    END IF;
    IF OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.projection_id IS DISTINCT FROM NEW.projection_id
       OR OLD.actor_id IS DISTINCT FROM NEW.actor_id
       OR OLD.context_scope_key IS DISTINCT FROM NEW.context_scope_key
       OR OLD.policy_revision IS DISTINCT FROM NEW.policy_revision
       OR OLD.authorization_revision IS DISTINCT FROM NEW.authorization_revision
       OR OLD.projection_revision IS DISTINCT FROM NEW.projection_revision
       OR OLD.valid_from IS DISTINCT FROM NEW.valid_from
       OR OLD.recorded_at IS DISTINCT FROM NEW.recorded_at THEN
        RAISE EXCEPTION 'Payroll legal-entity scope projection identity is immutable';
    END IF;
    IF OLD.status = 'BUILDING' THEN
        IF NEW.status <> 'ACTIVE'
           OR OLD.valid_until IS DISTINCT FROM NEW.valid_until
           OR NOT EXISTS (
                SELECT 1
                  FROM pay_legal_entity_scope_members scope_member
                 WHERE scope_member.tenant_id = OLD.tenant_id
                   AND scope_member.projection_id = OLD.projection_id) THEN
            RAISE EXCEPTION 'A nonempty BUILDING payroll scope must be sealed as ACTIVE';
        END IF;
    ELSIF OLD.status = 'ACTIVE' THEN
        IF NEW.status NOT IN ('SUPERSEDED', 'REVOKED')
           OR NEW.valid_until IS NULL
           OR NEW.valid_until <= OLD.valid_from THEN
            RAISE EXCEPTION 'Invalid terminal payroll legal-entity scope projection transition';
        END IF;
    ELSE
        RAISE EXCEPTION 'Terminal payroll legal-entity scope projections are immutable';
    END IF;
    RETURN NEW;
END
$pay_legal_entity_scope_projection_transition$;

CREATE TRIGGER pay_legal_entity_scope_projection_transition_guard
    BEFORE INSERT OR UPDATE OR DELETE ON pay_legal_entity_scope_projections
    FOR EACH ROW
    EXECUTE FUNCTION pay_guard_legal_entity_scope_projection_transition();

CREATE FUNCTION pay_guard_legal_entity_scope_member_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $pay_legal_entity_scope_member_lifecycle$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Payroll legal-entity scope members are immutable';
    END IF;

    -- The row lock closes the race between the final member insert and parent activation.
    SELECT projection.status
      INTO parent_status
      FROM pay_legal_entity_scope_projections projection
     WHERE projection.tenant_id = NEW.tenant_id
       AND projection.projection_id = NEW.projection_id
       FOR UPDATE;
    IF NOT FOUND OR parent_status <> 'BUILDING' THEN
        RAISE EXCEPTION 'Payroll legal-entity scope members may only be added while BUILDING';
    END IF;
    RETURN NEW;
END
$pay_legal_entity_scope_member_lifecycle$;

CREATE TRIGGER pay_legal_entity_scope_member_lifecycle_guard
    BEFORE INSERT OR UPDATE OR DELETE ON pay_legal_entity_scope_members
    FOR EACH ROW
    EXECUTE FUNCTION pay_guard_legal_entity_scope_member_lifecycle();

REVOKE ALL ON FUNCTION pay_guard_legal_entity_scope_projection_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION pay_guard_legal_entity_scope_member_lifecycle() FROM PUBLIC;

REVOKE ALL ON TABLE pay_legal_entity_scope_projections FROM PUBLIC;
REVOKE ALL ON TABLE pay_legal_entity_scope_members FROM PUBLIC;

ALTER TABLE pay_legal_entity_scope_projections ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_legal_entity_scope_projections FORCE ROW LEVEL SECURITY;
ALTER TABLE pay_legal_entity_scope_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_legal_entity_scope_members FORCE ROW LEVEL SECURITY;

CREATE POLICY pay_legal_entity_scope_projection_tenant_policy
    ON pay_legal_entity_scope_projections
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

CREATE POLICY pay_legal_entity_scope_member_tenant_policy
    ON pay_legal_entity_scope_members
    USING (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT)
    WITH CHECK (tenant_id = NULLIF(current_setting('dwp.payroll_tenant_id', true), '')::BIGINT);

DO $payroll_scope_runtime_access$
DECLARE
    runtime_role_name TEXT := '${payrollRuntimeRole}';
    runtime_role RECORD;
BEGIN
    IF runtime_role_name !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'Invalid payroll runtime role name';
    END IF;

    SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
      INTO runtime_role
      FROM pg_roles
     WHERE rolname = runtime_role_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Payroll runtime role % must exist', runtime_role_name;
    END IF;
    IF runtime_role.rolsuper
       OR runtime_role.rolcreaterole
       OR runtime_role.rolcreatedb
       OR runtime_role.rolreplication
       OR runtime_role.rolbypassrls THEN
        RAISE EXCEPTION 'Payroll runtime role % has unsafe PostgreSQL attributes', runtime_role_name;
    END IF;

    EXECUTE format(
        'GRANT SELECT ON TABLE pay_legal_entity_scope_projections TO %I',
        runtime_role_name);
    EXECUTE format(
        'GRANT SELECT ON TABLE pay_legal_entity_scope_members TO %I',
        runtime_role_name);
END
$payroll_scope_runtime_access$;

COMMENT ON TABLE pay_legal_entity_scope_projections IS
    'Versioned Payroll-owned authority projection; BUILDING revisions become visible only after an atomic nonempty ACTIVE seal.';
COMMENT ON TABLE pay_legal_entity_scope_members IS
    'Exact immutable legal entities added only while the parent is BUILDING; no wildcard membership exists.';
