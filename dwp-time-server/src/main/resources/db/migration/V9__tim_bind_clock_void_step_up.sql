-- G7-CONTROL-TIM-STEPUP-V9-R1
-- One-time, owner-local ledger for the v36 command-bound corrective-void challenge. The row is
-- inserted in the same transaction as the receipt, immutable clock fact, and outbox envelope.

CREATE TABLE tme_clock_step_up_replay_ledger (
    replay_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    challenge_id VARCHAR(160) NOT NULL,
    nonce VARCHAR(160) NOT NULL,
    command_contract_key VARCHAR(200) NOT NULL,
    activation_policy VARCHAR(120) NOT NULL,
    capability_contract_key VARCHAR(200) NOT NULL,
    context_key VARCHAR(500) NOT NULL,
    scope_ref VARCHAR(500) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(240) NOT NULL,
    target_version BIGINT NOT NULL,
    command_method VARCHAR(8) NOT NULL,
    command_path VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    decision_revision VARCHAR(200) NOT NULL,
    issuer VARCHAR(500) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_tme_clock_step_up_challenge_nonce UNIQUE (challenge_id, nonce),
    CONSTRAINT ck_tme_clock_step_up_identity CHECK (
        tenant_id > 0 AND actor_user_id > 0 AND target_version > 0),
    CONSTRAINT ck_tme_clock_step_up_route CHECK (
        command_contract_key = 'route.hcm.operations.time-clock-event-void.action'
        AND activation_policy = 'STEPUP-MGMT-HIGH-V1'
        AND capability_contract_key = 'hcm.operations.time.update'
        AND target_type = 'CLOCK_EVENT'
        AND command_method = 'POST'),
    CONSTRAINT ck_tme_clock_step_up_target CHECK (
        target_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        AND command_path = '/api/time/v1/clock-events/' || target_id || '/voids'),
    CONSTRAINT ck_tme_clock_step_up_idempotency CHECK (
        idempotency_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_tme_clock_step_up_digests CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$'
        AND decision_revision ~ '^psr-[0-9a-f]{64}$'),
    CONSTRAINT ck_tme_clock_step_up_expiry CHECK (expires_at > consumed_at)
);

CREATE INDEX ix_tme_clock_step_up_actor_consumed
    ON tme_clock_step_up_replay_ledger (tenant_id, actor_user_id, consumed_at DESC);
CREATE INDEX ix_tme_clock_step_up_expiry
    ON tme_clock_step_up_replay_ledger (expires_at, replay_id);

ALTER TABLE tme_clock_step_up_replay_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE tme_clock_step_up_replay_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY tme_clock_step_up_replay_ledger_tenant_isolation
    ON tme_clock_step_up_replay_ledger
    USING (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)
    WITH CHECK (
        tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT);

REVOKE ALL ON TABLE tme_clock_step_up_replay_ledger FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE tme_clock_step_up_replay_ledger
TO "${timeRuntimeRole}";

COMMENT ON TABLE tme_clock_step_up_replay_ledger IS
    'Time-owned one-time ledger for Auth-signed v36 clock-event void challenges.';
