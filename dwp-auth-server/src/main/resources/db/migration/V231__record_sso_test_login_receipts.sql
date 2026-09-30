CREATE TABLE com_sso_test_login_receipts (
    test_login_job_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES com_tenants(tenant_id),
    provider_key VARCHAR(100),
    requested_by BIGINT NOT NULL REFERENCES com_users(user_id),
    idempotency_key UUID NOT NULL,
    justification VARCHAR(1000) NOT NULL,
    lifecycle_state VARCHAR(24) NOT NULL,
    internal_prerequisite_state VARCHAR(40) NOT NULL,
    external_probe_state VARCHAR(24) NOT NULL,
    blocking_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    execution_boundary VARCHAR(80) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    receipt_sha256 CHAR(64) NOT NULL,
    correlation_id VARCHAR(160),
    CONSTRAINT uk_sso_test_login_receipt_idempotency
        UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_sso_test_login_receipt_lifecycle
        CHECK (lifecycle_state IN ('BLOCKED', 'UNAVAILABLE', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_sso_test_login_receipt_probe
        CHECK (external_probe_state IN ('NOT_REQUIRED', 'UNAVAILABLE', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_sso_test_login_receipt_reasons
        CHECK (jsonb_typeof(blocking_reasons) = 'array'),
    CONSTRAINT ck_sso_test_login_receipt_hash
        CHECK (receipt_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sso_test_login_receipt_time
        CHECK (completed_at >= requested_at)
);

CREATE INDEX idx_sso_test_login_receipt_history
    ON com_sso_test_login_receipts (tenant_id, requested_at DESC, test_login_job_id DESC);

CREATE OR REPLACE FUNCTION reject_sso_test_login_receipt_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'SSO test-login receipts are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sso_test_login_receipt_immutable
    BEFORE UPDATE OR DELETE ON com_sso_test_login_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_sso_test_login_receipt_mutation();

COMMENT ON TABLE com_sso_test_login_receipts IS
    'Immutable internal command receipts for tenant SSO test-login attempts. External IdP success is never inferred.';
COMMENT ON COLUMN com_sso_test_login_receipts.execution_boundary IS
    'Names the executor boundary used for this attempt; UNCONNECTED_EXTERNAL_IDP_EXECUTOR is an explicit terminal result.';
