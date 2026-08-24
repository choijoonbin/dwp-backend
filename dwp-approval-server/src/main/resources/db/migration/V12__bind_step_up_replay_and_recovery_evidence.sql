ALTER TABLE apr_integration_outbox
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN event_originator_user_id BIGINT,
    ADD COLUMN assigned_auditor_user_id BIGINT;

ALTER TABLE apr_integration_outbox
    ADD CONSTRAINT ck_apr_integration_version CHECK (version >= 0),
    ADD CONSTRAINT ck_apr_integration_recovery_parties CHECK (
        event_originator_user_id IS NULL
        OR assigned_auditor_user_id IS NULL
        OR event_originator_user_id <> assigned_auditor_user_id);

CREATE TABLE apr_step_up_replay_ledger (
    replay_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    challenge_id VARCHAR(160) NOT NULL,
    nonce VARCHAR(160) NOT NULL,
    activation_policy VARCHAR(120) NOT NULL,
    capability_contract_key VARCHAR(200) NOT NULL,
    scope_ref VARCHAR(200) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(240) NOT NULL,
    target_version BIGINT NOT NULL,
    command_method VARCHAR(10) NOT NULL,
    command_path VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    decision_revision VARCHAR(200) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_apr_step_up_challenge_nonce UNIQUE (challenge_id, nonce),
    CONSTRAINT ck_apr_step_up_target_version CHECK (target_version >= 0),
    CONSTRAINT ck_apr_step_up_payload_sha CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_apr_step_up_method CHECK (
        command_method IN ('POST', 'PUT', 'PATCH', 'DELETE'))
);

CREATE INDEX idx_apr_step_up_actor_consumed
    ON apr_step_up_replay_ledger (tenant_id, actor_user_id, consumed_at DESC);

CREATE INDEX idx_apr_step_up_replay_expiry
    ON apr_step_up_replay_ledger (expires_at, replay_id);

CREATE TABLE apr_high_risk_idempotency_ledger (
    idempotency_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    route_contract_key VARCHAR(240) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    challenge_id VARCHAR(160),
    result_receipt JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
    CONSTRAINT uk_apr_high_risk_idempotency
        UNIQUE (tenant_id, actor_user_id, route_contract_key, idempotency_key),
    CONSTRAINT ck_apr_high_risk_idempotency_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_apr_high_risk_idempotency_status
        CHECK (status IN ('IN_PROGRESS', 'COMMITTED')),
    CONSTRAINT ck_apr_high_risk_idempotency_result
        CHECK ((status = 'IN_PROGRESS' AND result_receipt IS NULL AND committed_at IS NULL)
            OR (status = 'COMMITTED' AND result_receipt IS NOT NULL
                AND committed_at IS NOT NULL AND challenge_id IS NOT NULL))
);

CREATE INDEX idx_apr_high_risk_idempotency_committed
    ON apr_high_risk_idempotency_ledger
        (tenant_id, actor_user_id, committed_at DESC)
    WHERE status = 'COMMITTED';

CREATE INDEX idx_apr_high_risk_idempotency_expiry
    ON apr_high_risk_idempotency_ledger (expires_at, idempotency_id);

COMMENT ON TABLE apr_step_up_replay_ledger IS
    'Product-local, transaction-coupled single-use ledger for signed command-bound step-up challenges.';

COMMENT ON TABLE apr_high_risk_idempotency_ledger IS
    'Durable minimal command-receipt ledger; request hash is checked before challenge replay and mutation.';

COMMENT ON COLUMN apr_high_risk_idempotency_ledger.result_receipt IS
    'Minimal non-domain receipt only; full Approval response payloads are forbidden in this ledger.';

COMMENT ON COLUMN apr_integration_outbox.event_originator_user_id IS
    'Server-recorded command actor that originated this delivery; NULL legacy rows fail recovery SoD closed.';

COMMENT ON COLUMN apr_integration_outbox.assigned_auditor_user_id IS
    'Explicitly assigned recovery auditor; NULL rows are not recoverable by the W1a high-risk command.';
