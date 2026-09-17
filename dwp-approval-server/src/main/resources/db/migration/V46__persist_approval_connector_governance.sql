CREATE TABLE apr_connector_heads (
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    resource_set_key VARCHAR(80) NOT NULL,
    connector_id UUID NOT NULL,
    connector_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    connector_type VARCHAR(24) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 1,
    draft_revision_id UUID,
    published_revision_id UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, connector_id),
    UNIQUE (tenant_id, resource_set_key, connector_key),
    CHECK (resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    CHECK (connector_key ~ '^[A-Z][A-Z0-9_.-]{2,99}$'),
    CHECK (btrim(display_name) <> ''),
    CHECK (connector_type IN ('REST', 'WEBHOOK', 'KAFKA', 'ERP', 'SIGNATURE', 'CUSTOM')),
    CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'DISABLED', 'RETIRED')),
    CHECK (version BETWEEN 1 AND 9007199254740991),
    CHECK (created_by > 0 AND updated_by > 0),
    CHECK (draft_revision_id IS NOT NULL OR published_revision_id IS NOT NULL),
    CHECK (draft_revision_id IS NULL OR published_revision_id IS NULL
        OR draft_revision_id <> published_revision_id)
);

CREATE TABLE apr_connector_revisions (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    connector_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    revision_number BIGINT NOT NULL,
    endpoint_uri VARCHAR(2048) NOT NULL,
    credential_reference VARCHAR(512) NOT NULL,
    header_allowlist JSONB NOT NULL,
    request_mapping JSONB NOT NULL,
    response_mapping JSONB NOT NULL,
    timeout_millis INTEGER NOT NULL,
    rate_limit_per_minute INTEGER NOT NULL,
    max_attempts SMALLINT NOT NULL,
    initial_backoff_millis INTEGER NOT NULL,
    max_backoff_millis INTEGER NOT NULL,
    idempotency_mode VARCHAR(24) NOT NULL,
    signing_mode VARCHAR(24) NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    maker_user_id BIGINT NOT NULL,
    maker_person_public_id UUID NOT NULL,
    editor_user_id BIGINT NOT NULL,
    editor_person_public_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, resource_set_key, connector_id, revision_id),
    UNIQUE (tenant_id, resource_set_key, connector_id, revision_number),
    FOREIGN KEY (tenant_id, resource_set_key, connector_id)
        REFERENCES apr_connector_heads(tenant_id, resource_set_key, connector_id),
    CHECK (revision_number BETWEEN 1 AND 9007199254740991),
    CHECK (endpoint_uri ~ '^https://'),
    CHECK (credential_reference ~ '^vault://[A-Za-z0-9/_:.@-]+$'
        AND length(credential_reference) BETWEEN 11 AND 512),
    CHECK (jsonb_typeof(header_allowlist) = 'array'
        AND jsonb_typeof(request_mapping) = 'object'
        AND jsonb_typeof(response_mapping) = 'object'),
    CHECK (timeout_millis BETWEEN 100 AND 120000),
    CHECK (rate_limit_per_minute BETWEEN 1 AND 10000),
    CHECK (max_attempts BETWEEN 1 AND 10),
    CHECK (initial_backoff_millis BETWEEN 10 AND 60000),
    CHECK (max_backoff_millis BETWEEN initial_backoff_millis AND 3600000),
    CHECK (idempotency_mode IN ('HEADER', 'BODY_HASH', 'PROVIDER_NATIVE')),
    CHECK (signing_mode IN ('NONE', 'HMAC_SHA256', 'JWS_ED25519', 'MTLS')),
    CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (maker_user_id > 0 AND editor_user_id > 0)
);

ALTER TABLE apr_connector_heads
    ADD CONSTRAINT fk_apr_connector_draft
    FOREIGN KEY (tenant_id, resource_set_key, connector_id, draft_revision_id)
    REFERENCES apr_connector_revisions(
        tenant_id, resource_set_key, connector_id, revision_id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE apr_connector_heads
    ADD CONSTRAINT fk_apr_connector_published
    FOREIGN KEY (tenant_id, resource_set_key, connector_id, published_revision_id)
    REFERENCES apr_connector_revisions(
        tenant_id, resource_set_key, connector_id, revision_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE apr_connector_probe_runs (
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    connector_id UUID NOT NULL,
    probe_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    probe_kind VARCHAR(20) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    request_sha256 CHAR(64) NOT NULL,
    evidence_revision VARCHAR(240),
    evidence_sha256 CHAR(64),
    sanitized_diagnostics JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_by BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    verification_reference VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, resource_set_key, connector_id, probe_id),
    FOREIGN KEY (tenant_id, resource_set_key, connector_id, revision_id)
        REFERENCES apr_connector_revisions(
            tenant_id, resource_set_key, connector_id, revision_id),
    CHECK (probe_kind IN ('READINESS', 'SYNTHETIC_TEST')),
    CHECK (state IN ('PENDING', 'RUNNING', 'VERIFIED', 'FAILED', 'UNKNOWN_REMOTE_OUTCOME')),
    CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (jsonb_typeof(sanitized_diagnostics) = 'object'),
    CHECK (started_by > 0 AND version BETWEEN 1 AND 9007199254740991),
    CHECK ((state IN ('PENDING', 'RUNNING')
        AND evidence_revision IS NULL AND evidence_sha256 IS NULL
        AND completed_at IS NULL AND valid_until IS NULL
        AND verification_reference IS NULL)
        OR (state IN ('VERIFIED', 'FAILED', 'UNKNOWN_REMOTE_OUTCOME')
        AND evidence_revision IS NOT NULL
        AND btrim(evidence_revision) <> ''
        AND evidence_sha256 IS NOT NULL
        AND evidence_sha256 ~ '^[0-9a-f]{64}$'
        AND completed_at IS NOT NULL
        AND valid_until IS NOT NULL AND valid_until > completed_at
        AND ((state = 'VERIFIED'
                AND verification_reference IS NOT NULL
                AND verification_reference ~ '^verified:[0-9a-f]{64}$')
            OR (state <> 'VERIFIED' AND verification_reference IS NULL))))
);

CREATE TABLE apr_connector_publications (
    publication_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    resource_set_key VARCHAR(80) NOT NULL,
    connector_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    probe_id UUID NOT NULL,
    maker_person_public_id UUID NOT NULL,
    editor_person_public_id UUID NOT NULL,
    checker_user_id BIGINT NOT NULL,
    checker_person_public_id UUID NOT NULL,
    review_evidence_sha256 CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, resource_set_key, connector_id, revision_id),
    FOREIGN KEY (tenant_id, resource_set_key, connector_id, revision_id)
        REFERENCES apr_connector_revisions(
            tenant_id, resource_set_key, connector_id, revision_id),
    FOREIGN KEY (tenant_id, resource_set_key, connector_id, probe_id)
        REFERENCES apr_connector_probe_runs(
            tenant_id, resource_set_key, connector_id, probe_id),
    CHECK (checker_user_id > 0),
    CHECK (checker_person_public_id <> maker_person_public_id
        AND checker_person_public_id <> editor_person_public_id),
    CHECK (review_evidence_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE apr_connector_commands (
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
        'SAVE_CONNECTOR_DRAFT', 'START_PROBE', 'COMPLETE_PROBE',
        'PUBLISH_CONNECTOR', 'CHANGE_CONNECTOR_LIFECYCLE')),
    CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$'),
    CHECK (command_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (status IN ('UNKNOWN', 'SUCCEEDED')),
    CHECK ((status = 'UNKNOWN' AND result_type IS NULL
        AND result_payload IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND result_type IS NOT NULL
        AND btrim(result_type) <> '' AND result_payload IS NOT NULL
        AND jsonb_typeof(result_payload) = 'object' AND completed_at IS NOT NULL))
);

CREATE INDEX idx_apr_connector_latest_probe
    ON apr_connector_probe_runs (
        tenant_id, resource_set_key, connector_id, revision_id,
        completed_at DESC, probe_id);

CREATE FUNCTION reject_apr_connector_governance_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Approval connector governance evidence is append-only';
END;
$$;

CREATE TRIGGER trg_apr_connector_publication_append_only
    BEFORE UPDATE OR DELETE ON apr_connector_publications
    FOR EACH ROW EXECUTE FUNCTION reject_apr_connector_governance_evidence_mutation();

COMMENT ON TABLE apr_connector_revisions IS
    'Immutable connector configurations containing Vault references only, never credentials.';
COMMENT ON TABLE apr_connector_probe_runs IS
    'Observed probe truth; pending or unknown remote outcomes never imply readiness.';
