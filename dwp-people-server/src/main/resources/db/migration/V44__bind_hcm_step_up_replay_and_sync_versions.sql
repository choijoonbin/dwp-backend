CREATE TABLE ppl_step_up_replay_ledger (
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
    CONSTRAINT uk_ppl_step_up_challenge_nonce UNIQUE (challenge_id, nonce),
    CONSTRAINT ck_ppl_step_up_target_version CHECK (target_version >= 0),
    CONSTRAINT ck_ppl_step_up_payload_sha CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ppl_step_up_method CHECK (
        command_method IN ('POST', 'PUT', 'PATCH', 'DELETE'))
);

CREATE INDEX idx_ppl_step_up_actor_consumed
    ON ppl_step_up_replay_ledger (tenant_id, actor_user_id, consumed_at DESC);

CREATE INDEX idx_ppl_step_up_replay_expiry
    ON ppl_step_up_replay_ledger (expires_at, replay_id);

-- Sync-run retry is an object-version-bound high-risk command. Older rows begin
-- at version zero; lifecycle completion increments the revision.
ALTER TABLE int_sync_runs
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON TABLE ppl_step_up_replay_ledger IS
    'People-owned one-time ledger for Auth-signed command-bound HCM step-up challenges.';
