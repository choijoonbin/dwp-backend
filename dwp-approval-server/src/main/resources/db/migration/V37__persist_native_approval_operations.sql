CREATE TABLE apr_operation_batches (
    operation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES apr_tenants(tenant_id),
    management_resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_person_public_id UUID NOT NULL,
    route_contract_key VARCHAR(240) NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    command_mode VARCHAR(12) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    decision_revision VARCHAR(240) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    item_count SMALLINT NOT NULL,
    result_receipt JSONB NOT NULL,
    broker_observed_at TIMESTAMPTZ NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retention_until TIMESTAMPTZ NOT NULL
        DEFAULT (CURRENT_TIMESTAMP + INTERVAL '2555 days'),
    CONSTRAINT uk_apr_operation_actor_idempotency UNIQUE
        (tenant_id, management_resource_set_key, actor_user_id, idempotency_key),
    CONSTRAINT uk_apr_operation_exact_parent UNIQUE
        (operation_id, tenant_id, management_resource_set_key, actor_user_id),
    CONSTRAINT ck_apr_operation_scope CHECK (
        management_resource_set_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_apr_operation_actor CHECK (actor_user_id > 0),
    CONSTRAINT ck_apr_operation_type CHECK (operation_type IN (
        'DELIVERY_RETRY', 'DELIVERY_DEAD_LETTER', 'DELIVERY_REPLAY',
        'DELIVERY_RECONCILE', 'TASK_REASSIGN')),
    CONSTRAINT ck_apr_operation_mode CHECK (command_mode IN ('SINGLE', 'BATCH')),
    CONSTRAINT ck_apr_operation_idempotency CHECK (
        idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    CONSTRAINT ck_apr_operation_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_apr_operation_revision CHECK (
        btrim(decision_revision) <> ''),
    CONSTRAINT ck_apr_operation_reason CHECK (
        btrim(reason) <> ''),
    CONSTRAINT ck_apr_operation_cardinality CHECK (item_count BETWEEN 1 AND 50),
    CONSTRAINT ck_apr_operation_receipt CHECK (
        jsonb_typeof(result_receipt) = 'object'),
    CONSTRAINT ck_apr_operation_retention CHECK (retention_until > committed_at)
);

CREATE TABLE apr_operation_items (
    operation_id UUID NOT NULL,
    item_sequence SMALLINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    management_resource_set_key VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    target_id UUID NOT NULL,
    request_id UUID NOT NULL,
    expected_version BIGINT NOT NULL,
    committed_version BIGINT NOT NULL,
    status_before VARCHAR(24) NOT NULL,
    status_after VARCHAR(24) NOT NULL,
    authority_subject_user_id BIGINT NOT NULL,
    authority_subject_person_public_id UUID,
    authority_role_code VARCHAR(80),
    broker_revision VARCHAR(240) NOT NULL,
    broker_observed_at TIMESTAMPTZ NOT NULL,
    target_fingerprint CHAR(64) NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id, item_sequence),
    CONSTRAINT uk_apr_operation_item_target UNIQUE
        (operation_id, target_type, target_id),
    CONSTRAINT fk_apr_operation_item_parent FOREIGN KEY
        (operation_id, tenant_id, management_resource_set_key, actor_user_id)
        REFERENCES apr_operation_batches
        (operation_id, tenant_id, management_resource_set_key, actor_user_id),
    CONSTRAINT fk_apr_operation_item_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES apr_requests(tenant_id, request_id),
    CONSTRAINT ck_apr_operation_item_sequence CHECK (item_sequence BETWEEN 1 AND 50),
    CONSTRAINT ck_apr_operation_item_target CHECK (
        target_type IN ('OUTBOX_EVENT', 'APPROVAL_TASK')),
    CONSTRAINT ck_apr_operation_item_versions CHECK (
        expected_version >= 0 AND committed_version = expected_version + 1),
    CONSTRAINT ck_apr_operation_item_authority CHECK (authority_subject_user_id > 0),
    CONSTRAINT ck_apr_operation_item_role CHECK (
        authority_role_code IS NULL
        OR authority_role_code ~ '^[A-Z][A-Z0-9_]{1,79}$'),
    CONSTRAINT ck_apr_operation_item_revision CHECK (
        btrim(broker_revision) <> ''),
    CONSTRAINT ck_apr_operation_item_fingerprint CHECK (
        target_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_apr_operation_actor_timeline
    ON apr_operation_batches (
        tenant_id, management_resource_set_key, actor_user_id,
        committed_at DESC, operation_id);

CREATE INDEX idx_apr_operation_target_timeline
    ON apr_operation_items (
        tenant_id, target_type, target_id, committed_at DESC, operation_id);

CREATE INDEX idx_apr_operation_request_timeline
    ON apr_operation_items (
        tenant_id, request_id, committed_at DESC, operation_id);

CREATE FUNCTION reject_apr_operation_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Approval operation ledgers are append-only';
END;
$$;

CREATE TRIGGER trg_apr_operation_batches_append_only
    BEFORE UPDATE OR DELETE ON apr_operation_batches
    FOR EACH ROW EXECUTE FUNCTION reject_apr_operation_ledger_mutation();

CREATE TRIGGER trg_apr_operation_items_append_only
    BEFORE UPDATE OR DELETE ON apr_operation_items
    FOR EACH ROW EXECUTE FUNCTION reject_apr_operation_ledger_mutation();

COMMENT ON TABLE apr_operation_batches IS
    'Actor-owned, idempotent receipts for native Approval operations; one row commits with all items.';
COMMENT ON TABLE apr_operation_items IS
    'Append-only object/version and current-authority evidence for each committed operation item.';
