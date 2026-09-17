CREATE TABLE prv_feature_rollout_application_receipts (
    receipt_id UUID PRIMARY KEY,
    provider_tenant_id UUID NOT NULL
        REFERENCES prv_tenants(provider_tenant_id),
    feature_flag_id UUID NOT NULL
        REFERENCES prv_feature_flags(feature_flag_id),
    target_id VARCHAR(120) NOT NULL,
    observed_opaque_revision BIGINT NOT NULL,
    observation_state VARCHAR(16) NOT NULL,
    error_code VARCHAR(80),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prv_feature_rollout_receipt_target
        CHECK (target_id ~ '^[a-z][a-z0-9.-]{2,119}$'),
    CONSTRAINT ck_prv_feature_rollout_receipt_revision
        CHECK (observed_opaque_revision >= 0),
    CONSTRAINT ck_prv_feature_rollout_receipt_state
        CHECK (observation_state IN ('APPLIED', 'FAILED')),
    CONSTRAINT ck_prv_feature_rollout_receipt_error
        CHECK (
            (observation_state = 'APPLIED' AND error_code IS NULL)
            OR (observation_state = 'FAILED'
                AND error_code ~ '^[A-Z][A-Z0-9_]{2,79}$'))
);

CREATE TABLE prv_feature_rollout_application_state (
    provider_tenant_id UUID NOT NULL
        REFERENCES prv_tenants(provider_tenant_id),
    feature_flag_id UUID NOT NULL
        REFERENCES prv_feature_flags(feature_flag_id),
    target_id VARCHAR(120) NOT NULL,
    latest_receipt_id UUID NOT NULL
        REFERENCES prv_feature_rollout_application_receipts(receipt_id),
    observed_opaque_revision BIGINT NOT NULL,
    observation_state VARCHAR(16) NOT NULL,
    error_code VARCHAR(80),
    observed_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    PRIMARY KEY (provider_tenant_id, feature_flag_id, target_id),
    CONSTRAINT ck_prv_feature_rollout_application_target
        CHECK (target_id ~ '^[a-z][a-z0-9.-]{2,119}$'),
    CONSTRAINT ck_prv_feature_rollout_application_revision
        CHECK (observed_opaque_revision >= 0),
    CONSTRAINT ck_prv_feature_rollout_application_state
        CHECK (observation_state IN ('APPLIED', 'FAILED')),
    CONSTRAINT ck_prv_feature_rollout_application_error
        CHECK (
            (observation_state = 'APPLIED' AND error_code IS NULL)
            OR (observation_state = 'FAILED'
                AND error_code ~ '^[A-Z][A-Z0-9_]{2,79}$'))
);

CREATE INDEX idx_prv_feature_rollout_receipt_tenant_time
    ON prv_feature_rollout_application_receipts (
        provider_tenant_id, received_at DESC, receipt_id);

CREATE UNIQUE INDEX uk_prv_feature_rollout_receipt_evidence
    ON prv_feature_rollout_application_receipts (
        provider_tenant_id, feature_flag_id, target_id,
        observed_opaque_revision, observation_state,
        COALESCE(error_code, ''));

CREATE INDEX idx_prv_feature_rollout_application_flag
    ON prv_feature_rollout_application_state (
        feature_flag_id, observation_state, observed_at DESC);

CREATE OR REPLACE FUNCTION prv_reject_feature_rollout_receipt_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Feature rollout application receipts are immutable';
END;
$$;

CREATE TRIGGER trg_prv_feature_rollout_receipt_immutable
BEFORE UPDATE OR DELETE ON prv_feature_rollout_application_receipts
FOR EACH ROW EXECUTE FUNCTION prv_reject_feature_rollout_receipt_mutation();

COMMENT ON TABLE prv_feature_rollout_application_receipts IS
    'Immutable receipts emitted only after the trusted Gateway accepts a Provider rollout decision into its enforcement cache.';
COMMENT ON TABLE prv_feature_rollout_application_state IS
    'Tenant-isolated latest runtime observation for a named evidence scope. The initial Gateway target means a sampled authoritative request path and does not assert fleet-wide replica convergence.';
