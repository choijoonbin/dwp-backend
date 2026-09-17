CREATE TABLE apr_routing_resolvers (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    resolver_id UUID NOT NULL,
    resolver_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    resolver_kind VARCHAR(32) NOT NULL,
    definition JSONB NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    source_state VARCHAR(20) NOT NULL DEFAULT 'NOT_VERIFIED',
    source_revision VARCHAR(240),
    source_evidence_sha256 CHAR(64),
    source_observed_at TIMESTAMPTZ,
    source_valid_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, resolver_id),
    UNIQUE (tenant_id, resource_set_key, resolver_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (resolver_key ~ '^[A-Z][A-Z0-9_.-]{2,99}$'),
    CHECK (btrim(display_name) <> ''),
    CHECK (resolver_kind IN (
        'MANAGER', 'ORG_ROLE', 'PROJECT_ROLE', 'DIRECTORY_GROUP', 'STATIC_SUBJECT')),
    CHECK (jsonb_typeof(definition) = 'object'),
    CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CHECK (source_state IN (
        'NOT_VERIFIED', 'HEALTHY', 'STALE', 'UNAVAILABLE', 'UNKNOWN')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (created_by > 0 AND updated_by > 0),
    CHECK (
        (source_state = 'NOT_VERIFIED'
            AND source_revision IS NULL
            AND source_evidence_sha256 IS NULL
            AND source_observed_at IS NULL
            AND source_valid_until IS NULL)
        OR
        (source_state <> 'NOT_VERIFIED'
            AND source_revision IS NOT NULL
            AND btrim(source_revision) <> ''
            AND source_evidence_sha256 IS NOT NULL
            AND source_evidence_sha256 ~ '^[0-9a-f]{64}$'
            AND source_observed_at IS NOT NULL
            AND source_valid_until IS NOT NULL
            AND source_valid_until > source_observed_at))
);

CREATE TABLE apr_routing_resolver_observations (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    resolver_id UUID NOT NULL,
    observation_id UUID NOT NULL,
    resolver_version BIGINT NOT NULL,
    source_state VARCHAR(20) NOT NULL,
    source_revision VARCHAR(240) NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    recorded_by BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, resolver_id, observation_id),
    FOREIGN KEY (tenant_id, resource_set_key, resolver_id)
        REFERENCES apr_routing_resolvers(tenant_id, resource_set_key, resolver_id),
    CHECK (resolver_version BETWEEN 1 AND 9007199254740991),
    CHECK (source_state IN ('HEALTHY', 'STALE', 'UNAVAILABLE', 'UNKNOWN')),
    CHECK (btrim(source_revision) <> ''),
    CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (valid_until > observed_at),
    CHECK (recorded_by > 0)
);

CREATE TABLE apr_routing_groups (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    group_id UUID NOT NULL,
    group_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, group_id),
    UNIQUE (tenant_id, resource_set_key, group_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (group_key ~ '^[A-Z][A-Z0-9_.-]{2,99}$'),
    CHECK (btrim(display_name) <> ''),
    CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (created_by > 0 AND updated_by > 0)
);

CREATE TABLE apr_routing_group_members (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    group_id UUID NOT NULL,
    member_id UUID NOT NULL,
    member_kind VARCHAR(16) NOT NULL,
    member_user_id BIGINT,
    member_person_public_id UUID,
    nested_group_id UUID,
    resolver_id UUID,
    priority SMALLINT NOT NULL DEFAULT 100,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, group_id, member_id),
    FOREIGN KEY (tenant_id, resource_set_key, group_id)
        REFERENCES apr_routing_groups(tenant_id, resource_set_key, group_id),
    FOREIGN KEY (tenant_id, resource_set_key, nested_group_id)
        REFERENCES apr_routing_groups(tenant_id, resource_set_key, group_id),
    FOREIGN KEY (tenant_id, resource_set_key, resolver_id)
        REFERENCES apr_routing_resolvers(tenant_id, resource_set_key, resolver_id),
    CHECK (member_kind IN ('SUBJECT', 'GROUP', 'RESOLVER')),
    CHECK (priority BETWEEN 1 AND 1000),
    CHECK (
        (member_kind = 'SUBJECT'
            AND member_user_id > 0
            AND member_person_public_id IS NOT NULL
            AND nested_group_id IS NULL AND resolver_id IS NULL)
        OR
        (member_kind = 'GROUP'
            AND member_user_id IS NULL AND member_person_public_id IS NULL
            AND nested_group_id IS NOT NULL AND resolver_id IS NULL)
        OR
        (member_kind = 'RESOLVER'
            AND member_user_id IS NULL AND member_person_public_id IS NULL
            AND nested_group_id IS NULL AND resolver_id IS NOT NULL))
);

CREATE TABLE apr_routing_group_usages (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    group_id UUID NOT NULL,
    usage_kind VARCHAR(32) NOT NULL,
    usage_owner_id UUID NOT NULL,
    usage_revision VARCHAR(240) NOT NULL,
    usage_sha256 CHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (
        tenant_id, resource_set_key, group_id, usage_kind, usage_owner_id),
    FOREIGN KEY (tenant_id, resource_set_key, group_id)
        REFERENCES apr_routing_groups(tenant_id, resource_set_key, group_id),
    CHECK (usage_kind IN ('WORKFLOW', 'POLICY', 'ESCALATION', 'DELEGATION')),
    CHECK (btrim(usage_revision) <> ''),
    CHECK (usage_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE apr_routing_directory_commands (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    operation VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    target_id UUID,
    command_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    result_type VARCHAR(240),
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (
        tenant_id, resource_set_key, actor_user_id, operation, idempotency_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (actor_user_id > 0),
    CHECK (operation IN (
        'SAVE_RESOLVER', 'OBSERVE_RESOLVER', 'SAVE_GROUP',
        'ACTIVATE_GROUP', 'RECORD_USAGE', 'RETIRE_GROUP')),
    CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (command_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('UNKNOWN', 'SUCCEEDED')),
    CHECK (
        (status = 'UNKNOWN' AND result_type IS NULL
            AND result_payload IS NULL AND completed_at IS NULL)
        OR
        (status = 'SUCCEEDED' AND result_type IS NOT NULL
            AND btrim(result_type) <> '' AND result_payload IS NOT NULL
            AND jsonb_typeof(result_payload) = 'object'
            AND completed_at IS NOT NULL))
);

CREATE INDEX idx_apr_routing_group_usage_active
    ON apr_routing_group_usages (
        tenant_id, resource_set_key, group_id, usage_kind, usage_owner_id)
    WHERE active;

CREATE INDEX idx_apr_routing_resolver_effective
    ON apr_routing_resolvers (
        tenant_id, resource_set_key, lifecycle_state,
        effective_from, effective_to, resolver_id);

CREATE FUNCTION reject_apr_routing_group_cycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.member_kind <> 'GROUP' THEN
        RETURN NEW;
    END IF;
    IF NEW.nested_group_id = NEW.group_id OR EXISTS (
        WITH RECURSIVE descendants(group_id) AS (
            SELECT NEW.nested_group_id
            UNION
            SELECT member.nested_group_id
              FROM apr_routing_group_members member
              JOIN descendants parent ON parent.group_id = member.group_id
             WHERE member.tenant_id = NEW.tenant_id
               AND member.resource_set_key = NEW.resource_set_key
               AND member.member_kind = 'GROUP'
        )
        SELECT 1 FROM descendants WHERE group_id = NEW.group_id
    ) THEN
        RAISE EXCEPTION 'Approval routing groups cannot contain cycles';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_apr_routing_group_cycle
    BEFORE INSERT OR UPDATE OF group_id, member_kind, nested_group_id
    ON apr_routing_group_members
    FOR EACH ROW EXECUTE FUNCTION reject_apr_routing_group_cycle();

CREATE FUNCTION reject_apr_routing_observation_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Approval routing source observations are append-only';
END;
$$;

CREATE TRIGGER trg_apr_routing_observations_append_only
    BEFORE UPDATE OR DELETE ON apr_routing_resolver_observations
    FOR EACH ROW EXECUTE FUNCTION reject_apr_routing_observation_mutation();

COMMENT ON TABLE apr_routing_groups IS
    'Tenant and management-scope bound reusable approver groups; nested cycles are rejected.';
COMMENT ON TABLE apr_routing_resolvers IS
    'Purpose-bound approver resolver definitions with explicit, expiring source health.';
COMMENT ON TABLE apr_routing_directory_commands IS
    'Exact-once command receipts; UNKNOWN never represents successful routing-directory work.';
