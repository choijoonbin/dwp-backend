-- Tenant-scoped evidence for Calendar scheduling evaluation and hard recheck.
-- Opaque client tokens are never persisted; only SHA-256 digests are stored.
CREATE TABLE cal_scheduling_evaluations_v2 (
    evaluation_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_person_public_id UUID NOT NULL,
    criteria_hash CHAR(64) NOT NULL,
    participant_hash CHAR(64) NOT NULL,
    criteria_payload JSONB NOT NULL,
    source_evidence JSONB NOT NULL,
    room_evidence JSONB NOT NULL,
    source_version VARCHAR(160) NOT NULL,
    challenge_token_hash CHAR(64) NOT NULL,
    challenge_consumed_at TIMESTAMPTZ,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cal_scheduling_evaluation_tenant
        UNIQUE (tenant_id, evaluation_id),
    CONSTRAINT uk_cal_scheduling_evaluation_challenge
        UNIQUE (challenge_token_hash),
    CONSTRAINT ck_cal_scheduling_evaluation_hashes CHECK (
        criteria_hash ~ '^[0-9a-f]{64}$'
        AND participant_hash ~ '^[0-9a-f]{64}$'
        AND challenge_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_cal_scheduling_evaluation_payloads CHECK (
        jsonb_typeof(criteria_payload) = 'object'
        AND jsonb_typeof(source_evidence) = 'array'
        AND jsonb_typeof(room_evidence) = 'array'),
    CONSTRAINT ck_cal_scheduling_evaluation_ttl CHECK (
        expires_at > generated_at
        AND (challenge_consumed_at IS NULL OR challenge_consumed_at >= generated_at))
);

CREATE INDEX idx_cal_scheduling_evaluation_actor_ttl
    ON cal_scheduling_evaluations_v2 (
        tenant_id, actor_user_id, actor_person_public_id, expires_at DESC);

-- Supports bounded opportunistic deletion after the short privacy-retention grace.
CREATE INDEX idx_cal_scheduling_evaluation_retention
    ON cal_scheduling_evaluations_v2 (tenant_id, expires_at, evaluation_id);

CREATE TABLE cal_scheduling_rechecks_v2 (
    recheck_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    evaluation_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_person_public_id UUID NOT NULL,
    criteria_hash CHAR(64) NOT NULL,
    participant_hash CHAR(64) NOT NULL,
    selection_hash CHAR(64) NOT NULL,
    selected_room_id UUID,
    source_version VARCHAR(160) NOT NULL,
    resource_version BIGINT,
    policy_version BIGINT NOT NULL,
    result_status VARCHAR(20) NOT NULL,
    hard_recheck_token_hash CHAR(64),
    consumed_at TIMESTAMPTZ,
    consumed_by_event_id UUID,
    evidence_payload JSONB NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cal_scheduling_recheck_evaluation
        FOREIGN KEY (tenant_id, evaluation_id)
        REFERENCES cal_scheduling_evaluations_v2 (tenant_id, evaluation_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_cal_scheduling_recheck_token
        UNIQUE (hard_recheck_token_hash),
    CONSTRAINT ck_cal_scheduling_recheck_hashes CHECK (
        criteria_hash ~ '^[0-9a-f]{64}$'
        AND participant_hash ~ '^[0-9a-f]{64}$'
        AND selection_hash ~ '^[0-9a-f]{64}$'
        AND (hard_recheck_token_hash IS NULL
             OR hard_recheck_token_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_cal_scheduling_recheck_versions CHECK (
        (resource_version IS NULL OR resource_version >= 0)
        AND policy_version >= 0),
    CONSTRAINT ck_cal_scheduling_recheck_status CHECK (
        result_status IN ('PASS', 'BLOCKED', 'INCOMPLETE')),
    CONSTRAINT ck_cal_scheduling_recheck_token_state CHECK (
        (result_status = 'PASS' AND hard_recheck_token_hash IS NOT NULL)
        OR (result_status <> 'PASS' AND hard_recheck_token_hash IS NULL)),
    CONSTRAINT ck_cal_scheduling_recheck_consumption CHECK (
        (consumed_at IS NULL AND consumed_by_event_id IS NULL)
        OR (consumed_at IS NOT NULL AND consumed_by_event_id IS NOT NULL
            AND consumed_at >= checked_at)),
    CONSTRAINT ck_cal_scheduling_recheck_payload CHECK (
        jsonb_typeof(evidence_payload) = 'object'),
    CONSTRAINT ck_cal_scheduling_recheck_ttl CHECK (expires_at > checked_at)
);

CREATE INDEX idx_cal_scheduling_recheck_evaluation_time
    ON cal_scheduling_rechecks_v2 (
        tenant_id, evaluation_id, checked_at DESC);

CREATE INDEX idx_cal_scheduling_recheck_actor_ttl
    ON cal_scheduling_rechecks_v2 (
        tenant_id, actor_user_id, actor_person_public_id, expires_at DESC)
    WHERE result_status = 'PASS';

CREATE INDEX idx_cal_scheduling_recheck_retention
    ON cal_scheduling_rechecks_v2 (tenant_id, expires_at, recheck_id);

COMMENT ON TABLE cal_scheduling_evaluations_v2 IS
    'Short-lived Calendar v2 scheduling evidence; challenge plaintext is never stored and expired payloads are removed by bounded opportunistic and global scheduled retention.';
COMMENT ON TABLE cal_scheduling_rechecks_v2 IS
    'Hard-recheck evidence bound to actor, criteria, participant set, selection, source and resource-policy versions; token plaintext is never stored and PASS tokens are consumed once by an event command.';
