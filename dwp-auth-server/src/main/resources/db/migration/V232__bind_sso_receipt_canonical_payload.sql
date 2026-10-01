CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE com_sso_test_login_receipts
    ADD COLUMN receipt_payload_canonical TEXT,
    ADD COLUMN pre_canonical_receipt_sha256 CHAR(64);

CREATE OR REPLACE FUNCTION canonical_sso_test_login_receipt_payload(
    p_test_login_job_id UUID,
    p_tenant_id BIGINT,
    p_provider_key VARCHAR,
    p_requested_by BIGINT,
    p_idempotency_key UUID,
    p_justification VARCHAR,
    p_lifecycle_state VARCHAR,
    p_internal_prerequisite_state VARCHAR,
    p_external_probe_state VARCHAR,
    p_blocking_reasons JSONB,
    p_execution_boundary VARCHAR,
    p_requested_at TIMESTAMPTZ,
    p_completed_at TIMESTAMPTZ,
    p_correlation_id VARCHAR)
RETURNS TEXT AS $$
    SELECT jsonb_build_object(
        'schemaVersion', 1,
        'testLoginJobId', p_test_login_job_id,
        'tenantId', p_tenant_id,
        'providerKey', p_provider_key,
        'requestedBy', p_requested_by,
        'idempotencyKey', p_idempotency_key,
        'justification', p_justification,
        'lifecycleState', p_lifecycle_state,
        'internalPrerequisiteState', p_internal_prerequisite_state,
        'externalProbeState', p_external_probe_state,
        'blockingReasons', p_blocking_reasons,
        'executionBoundary', p_execution_boundary,
        'requestedAtEpochMicros',
            (extract(epoch FROM p_requested_at) * 1000000)::BIGINT,
        'completedAtEpochMicros',
            (extract(epoch FROM p_completed_at) * 1000000)::BIGINT,
        'correlationId', p_correlation_id)::TEXT
$$ LANGUAGE SQL IMMUTABLE;

DROP TRIGGER trg_sso_test_login_receipt_immutable
    ON com_sso_test_login_receipts;

UPDATE com_sso_test_login_receipts receipt
   SET pre_canonical_receipt_sha256 = receipt.receipt_sha256,
       receipt_payload_canonical = canonical_sso_test_login_receipt_payload(
           receipt.test_login_job_id,
           receipt.tenant_id,
           receipt.provider_key,
           receipt.requested_by,
           receipt.idempotency_key,
           receipt.justification,
           receipt.lifecycle_state,
           receipt.internal_prerequisite_state,
           receipt.external_probe_state,
           receipt.blocking_reasons,
           receipt.execution_boundary,
           receipt.requested_at,
           receipt.completed_at,
           receipt.correlation_id),
       receipt_sha256 = encode(digest(
           canonical_sso_test_login_receipt_payload(
               receipt.test_login_job_id,
               receipt.tenant_id,
               receipt.provider_key,
               receipt.requested_by,
               receipt.idempotency_key,
               receipt.justification,
               receipt.lifecycle_state,
               receipt.internal_prerequisite_state,
               receipt.external_probe_state,
               receipt.blocking_reasons,
               receipt.execution_boundary,
               receipt.requested_at,
               receipt.completed_at,
               receipt.correlation_id),
           'sha256'), 'hex');

ALTER TABLE com_sso_test_login_receipts
    ALTER COLUMN receipt_payload_canonical SET NOT NULL,
    ADD CONSTRAINT ck_sso_test_login_receipt_payload_object
        CHECK (jsonb_typeof(receipt_payload_canonical::JSONB) = 'object'),
    ADD CONSTRAINT ck_sso_test_login_receipt_pre_canonical_hash
        CHECK (pre_canonical_receipt_sha256 IS NULL
            OR pre_canonical_receipt_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_sso_test_login_receipt_payload_binding
        CHECK (receipt_sha256 = encode(
            digest(receipt_payload_canonical, 'sha256'), 'hex'));

CREATE OR REPLACE FUNCTION bind_sso_test_login_receipt_payload()
RETURNS TRIGGER AS $$
BEGIN
    NEW.receipt_payload_canonical := canonical_sso_test_login_receipt_payload(
        NEW.test_login_job_id,
        NEW.tenant_id,
        NEW.provider_key,
        NEW.requested_by,
        NEW.idempotency_key,
        NEW.justification,
        NEW.lifecycle_state,
        NEW.internal_prerequisite_state,
        NEW.external_probe_state,
        NEW.blocking_reasons,
        NEW.execution_boundary,
        NEW.requested_at,
        NEW.completed_at,
        NEW.correlation_id);
    NEW.receipt_sha256 := encode(
        digest(NEW.receipt_payload_canonical, 'sha256'), 'hex');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sso_test_login_receipt_bind_payload
    BEFORE INSERT ON com_sso_test_login_receipts
    FOR EACH ROW EXECUTE FUNCTION bind_sso_test_login_receipt_payload();

CREATE TRIGGER trg_sso_test_login_receipt_immutable
    BEFORE UPDATE OR DELETE ON com_sso_test_login_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_sso_test_login_receipt_mutation();

COMMENT ON COLUMN com_sso_test_login_receipts.receipt_payload_canonical IS
    'Exact UTF-8 payload bound by receipt_sha256; timestamps use persisted epoch microseconds.';
COMMENT ON COLUMN com_sso_test_login_receipts.pre_canonical_receipt_sha256 IS
    'Original pre-V232 application hash retained during canonical evidence repair; null for new rows.';
