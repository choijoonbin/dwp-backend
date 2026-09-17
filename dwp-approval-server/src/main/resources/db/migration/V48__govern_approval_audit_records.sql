CREATE TABLE apr_audit_saved_views (
    saved_view_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    owner_user_id BIGINT NOT NULL CHECK (owner_user_id > 0),
    view_name VARCHAR(120) NOT NULL CHECK (length(btrim(view_name)) BETWEEN 1 AND 120),
    visibility VARCHAR(16) NOT NULL,
    filter_payload JSONB NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, management_resource_set_key, saved_view_id),
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (visibility IN ('PERSONAL', 'SHARED')),
    CHECK (jsonb_typeof(filter_payload) = 'object'),
    CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED'))
);

CREATE UNIQUE INDEX uk_apr_audit_personal_saved_view_name
    ON apr_audit_saved_views (
        tenant_id, management_resource_set_key, owner_user_id, lower(view_name))
    WHERE lifecycle_state = 'ACTIVE' AND visibility = 'PERSONAL';
CREATE UNIQUE INDEX uk_apr_audit_shared_saved_view_name
    ON apr_audit_saved_views (
        tenant_id, management_resource_set_key, lower(view_name))
    WHERE lifecycle_state = 'ACTIVE' AND visibility = 'SHARED';
CREATE INDEX idx_apr_audit_saved_view_scope
    ON apr_audit_saved_views (
        tenant_id, management_resource_set_key, lifecycle_state, updated_at DESC);

CREATE TABLE apr_audit_export_jobs (
    export_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    requested_by BIGINT NOT NULL CHECK (requested_by > 0),
    access_level VARCHAR(20) NOT NULL,
    filter_payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    manifest_payload JSONB,
    manifest_sha256 CHAR(64),
    exported_event_count INTEGER NOT NULL DEFAULT 0 CHECK (exported_event_count >= 0),
    integrity_status VARCHAR(32) NOT NULL DEFAULT 'NOT_EVALUATED',
    external_attestation_type VARCHAR(32),
    external_attestation_reference VARCHAR(500),
    external_attested_at TIMESTAMPTZ,
    external_attestation_issuer VARCHAR(160),
    external_attestor_identity VARCHAR(160),
    external_attestation_key_id VARCHAR(120),
    external_verification_reference VARCHAR(249),
    retention_snapshot JSONB,
    failure_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    UNIQUE (tenant_id, management_resource_set_key, export_id),
    CHECK (management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CHECK (access_level IN ('METADATA', 'PRIVILEGED', 'AUDITOR')),
    CHECK (jsonb_typeof(filter_payload) = 'object'),
    CHECK (status IN ('PENDING', 'COMPLETE', 'FAILED')),
    CHECK (integrity_status IN (
        'NOT_EVALUATED', 'DIGEST_VERIFIED', 'EXTERNAL_ATTESTED', 'FAILED')),
    CHECK (manifest_payload IS NULL OR jsonb_typeof(manifest_payload) = 'object'),
    CHECK (retention_snapshot IS NULL OR jsonb_typeof(retention_snapshot) = 'object'),
    CHECK (manifest_sha256 IS NULL OR manifest_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK (
        (status = 'PENDING' AND manifest_payload IS NULL AND manifest_sha256 IS NULL
            AND completed_at IS NULL AND failure_reason IS NULL)
        OR (status = 'COMPLETE' AND manifest_payload IS NOT NULL
            AND manifest_sha256 IS NOT NULL AND completed_at IS NOT NULL
            AND failure_reason IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL
            AND failure_reason IS NOT NULL)),
    CHECK (
        (integrity_status = 'EXTERNAL_ATTESTED'
            AND external_attestation_type IN ('WORM', 'KMS_SIGNATURE', 'QUALIFIED_ARCHIVE')
            AND external_attestation_reference IS NOT NULL
            AND external_attested_at IS NOT NULL
            AND external_attestation_issuer IS NOT NULL
            AND external_attestor_identity IS NOT NULL
            AND external_attestation_key_id IS NOT NULL
            AND external_verification_reference IS NOT NULL)
        OR (integrity_status <> 'EXTERNAL_ATTESTED'
            AND external_attestation_type IS NULL
            AND external_attestation_reference IS NULL
            AND external_attested_at IS NULL
            AND external_attestation_issuer IS NULL
            AND external_attestor_identity IS NULL
            AND external_attestation_key_id IS NULL
            AND external_verification_reference IS NULL)),
    CHECK (external_verification_reference IS NULL
        OR external_verification_reference ~ '^verified:[a-f0-9]{64}$'),
    CHECK (external_attestation_issuer IS NULL
        OR length(btrim(external_attestation_issuer)) BETWEEN 1 AND 160),
    CHECK (external_attestor_identity IS NULL
        OR length(btrim(external_attestor_identity)) BETWEEN 1 AND 160),
    CHECK (external_attestation_key_id IS NULL
        OR length(btrim(external_attestation_key_id)) BETWEEN 1 AND 120)
);

CREATE INDEX idx_apr_audit_export_scope
    ON apr_audit_export_jobs (
        tenant_id, management_resource_set_key, requested_at DESC);

COMMENT ON COLUMN apr_audit_export_jobs.integrity_status IS
    'DIGEST_VERIFIED proves only manifest byte integrity. EXTERNAL_ATTESTED requires an Ed25519 proof bound to the export digest and a configured issuer, attestor identity, and key id.';
