CREATE TABLE apr_deployment_packages (
    package_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    package_key VARCHAR(120) NOT NULL,
    package_version INTEGER NOT NULL CHECK (package_version > 0),
    display_name VARCHAR(200) NOT NULL CHECK (length(btrim(display_name)) > 0),
    manifest_payload JSONB NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    rollback_class VARCHAR(20) NOT NULL,
    created_by BIGINT NOT NULL CHECK (created_by > 0),
    idempotency_key VARCHAR(120) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, management_resource_set_key, created_by, idempotency_key),
    UNIQUE (tenant_id, management_resource_set_key, package_key, package_version),
    UNIQUE (tenant_id, management_resource_set_key, package_id),
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (package_key ~ '^[A-Z][A-Z0-9_.-]{2,119}$'),
    CHECK (jsonb_typeof(manifest_payload) = 'object'),
    CHECK (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    CHECK (request_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (rollback_class IN ('REVERSIBLE', 'CONDITIONAL', 'IRREVERSIBLE'))
);

CREATE TABLE apr_deployment_assets (
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    package_id UUID NOT NULL,
    asset_key VARCHAR(200) NOT NULL,
    asset_type VARCHAR(20) NOT NULL,
    asset_id UUID NOT NULL,
    asset_version VARCHAR(80) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    rollback_disposition VARCHAR(20) NOT NULL,
    external_side_effects BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, management_resource_set_key, package_id, asset_key),
    FOREIGN KEY (tenant_id, management_resource_set_key, package_id)
        REFERENCES apr_deployment_packages(
            tenant_id, management_resource_set_key, package_id),
    CHECK (asset_type IN ('FORM', 'WORKFLOW', 'POLICY', 'TEMPLATE')),
    CHECK (rollback_disposition IN ('REVERSIBLE', 'CONDITIONAL', 'IRREVERSIBLE')),
    CHECK (content_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE apr_deployment_dependencies (
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    package_id UUID NOT NULL,
    asset_key VARCHAR(200) NOT NULL,
    depends_on_asset_key VARCHAR(200) NOT NULL,
    required_sha256 CHAR(64) NOT NULL,
    optional BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (
        tenant_id, management_resource_set_key, package_id,
        asset_key, depends_on_asset_key),
    FOREIGN KEY (tenant_id, management_resource_set_key, package_id, asset_key)
        REFERENCES apr_deployment_assets(
            tenant_id, management_resource_set_key, package_id, asset_key),
    FOREIGN KEY (tenant_id, management_resource_set_key, package_id, depends_on_asset_key)
        REFERENCES apr_deployment_assets(
            tenant_id, management_resource_set_key, package_id, asset_key),
    CHECK (asset_key <> depends_on_asset_key),
    CHECK (required_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE apr_deployment_environment_heads (
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    environment VARCHAR(16) NOT NULL,
    active_package_id UUID,
    previous_package_id UUID,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, management_resource_set_key, environment),
    FOREIGN KEY (tenant_id, management_resource_set_key, active_package_id)
        REFERENCES apr_deployment_packages(
            tenant_id, management_resource_set_key, package_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, previous_package_id)
        REFERENCES apr_deployment_packages(
            tenant_id, management_resource_set_key, package_id),
    CHECK (environment IN ('DEVELOPMENT', 'TEST', 'PRODUCTION')),
    CHECK (active_package_id IS NULL OR active_package_id <> previous_package_id)
);

CREATE TABLE apr_deployment_promotions (
    promotion_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    package_id UUID NOT NULL,
    source_environment VARCHAR(16) NOT NULL,
    target_environment VARCHAR(16) NOT NULL,
    status VARCHAR(40) NOT NULL,
    maker_user_id BIGINT NOT NULL CHECK (maker_user_id > 0),
    checker_user_id BIGINT,
    review_comment VARCHAR(1000),
    step_up_evidence_reference VARCHAR(500),
    authorization_context_key VARCHAR(200),
    decision_revision VARCHAR(200),
    scheduled_for TIMESTAMPTZ,
    activation_started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    idempotency_key VARCHAR(120) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, management_resource_set_key, maker_user_id, idempotency_key),
    UNIQUE (tenant_id, management_resource_set_key, promotion_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, package_id)
        REFERENCES apr_deployment_packages(
            tenant_id, management_resource_set_key, package_id),
    CHECK (source_environment IN ('DEVELOPMENT', 'TEST', 'PRODUCTION')),
    CHECK (target_environment IN ('DEVELOPMENT', 'TEST', 'PRODUCTION')),
    CHECK (source_environment <> target_environment),
    CHECK (status IN (
        'PENDING_REVIEW', 'APPROVED', 'SCHEDULED', 'ACTIVATING',
        'ACTIVE', 'PARTIAL', 'UNKNOWN', 'FAILED', 'ROLLBACK_PENDING',
        'PACKAGE_HEAD_RESTORED')),
    CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    CHECK (request_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (checker_user_id IS NULL OR checker_user_id <> maker_user_id),
    CHECK ((checker_user_id IS NULL AND review_comment IS NULL
            AND step_up_evidence_reference IS NULL
            AND authorization_context_key IS NULL AND decision_revision IS NULL)
        OR (checker_user_id IS NOT NULL
            AND review_comment IS NOT NULL
            AND length(btrim(review_comment)) BETWEEN 10 AND 1000
            AND step_up_evidence_reference IS NOT NULL
            AND authorization_context_key IS NOT NULL
            AND decision_revision IS NOT NULL))
);

CREATE TABLE apr_deployment_evidence (
    evidence_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    promotion_id UUID NOT NULL,
    evidence_type VARCHAR(24) NOT NULL,
    observed_outcome VARCHAR(20) NOT NULL,
    external_reference VARCHAR(500) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    verification_reference VARCHAR(249) NOT NULL,
    source_generated_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, management_resource_set_key, promotion_id, evidence_id),
    FOREIGN KEY (tenant_id, management_resource_set_key, promotion_id)
        REFERENCES apr_deployment_promotions(
            tenant_id, management_resource_set_key, promotion_id),
    CHECK (evidence_type IN ('CANARY_HEALTH', 'ACTIVATION', 'ROLLBACK')),
    CHECK (observed_outcome IN ('HEALTHY', 'DEGRADED', 'UNKNOWN', 'FAILED')),
    CHECK (payload_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (verification_reference ~ '^verified:[0-9a-f]{64}$')
);

CREATE TABLE apr_deployment_journal (
    journal_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    promotion_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    actor_user_id BIGINT NOT NULL CHECK (actor_user_id > 0),
    event_payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, management_resource_set_key, promotion_id)
        REFERENCES apr_deployment_promotions(
            tenant_id, management_resource_set_key, promotion_id),
    CHECK (jsonb_typeof(event_payload) = 'object')
);

CREATE INDEX idx_apr_deployment_promotions_scope
    ON apr_deployment_promotions (
        tenant_id, management_resource_set_key, target_environment, updated_at DESC);
CREATE INDEX idx_apr_deployment_evidence_promotion
    ON apr_deployment_evidence (
        tenant_id, management_resource_set_key, promotion_id, recorded_at DESC);

CREATE OR REPLACE FUNCTION deny_approval_package_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Approval deployment packages and manifests are immutable'
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER trg_apr_deployment_package_immutable
BEFORE UPDATE OR DELETE ON apr_deployment_packages
FOR EACH ROW EXECUTE FUNCTION deny_approval_package_mutation();
CREATE TRIGGER trg_apr_deployment_asset_immutable
BEFORE UPDATE OR DELETE ON apr_deployment_assets
FOR EACH ROW EXECUTE FUNCTION deny_approval_package_mutation();
CREATE TRIGGER trg_apr_deployment_dependency_immutable
BEFORE UPDATE OR DELETE ON apr_deployment_dependencies
FOR EACH ROW EXECUTE FUNCTION deny_approval_package_mutation();

COMMENT ON TABLE apr_deployment_evidence IS
    'References externally observed health. It does not attest that provider-side effects were reversed.';
COMMENT ON COLUMN apr_deployment_promotions.status IS
    'PACKAGE_HEAD_RESTORED means only the Approval asset head changed; external side effects remain unasserted.';
