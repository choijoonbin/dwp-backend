-- Shared R1-14 event-delivery ledger. Broker transport remains disabled until D-07 approval.

CREATE TABLE IF NOT EXISTS sys_domain_event_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    spec_version VARCHAR(16) NOT NULL,
    event_source VARCHAR(240) NOT NULL,
    event_type VARCHAR(240) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    subject VARCHAR(320),
    tenant_id BIGINT,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(240) NOT NULL,
    aggregate_sequence BIGINT NOT NULL CHECK (aggregate_sequence > 0),
    correlation_id VARCHAR(160) NOT NULL,
    causation_id VARCHAR(160),
    trace_parent VARCHAR(80),
    payload JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (
        status IN ('PENDING', 'SENDING', 'PUBLISHED', 'FAILED', 'DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    available_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(240),
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE sys_domain_event_outbox
    DROP CONSTRAINT IF EXISTS uq_domain_event_outbox_aggregate_sequence;
CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_event_outbox_aggregate_sequence
    ON sys_domain_event_outbox (
        COALESCE(tenant_id, 0), event_source, aggregate_type,
        aggregate_id, aggregate_sequence);

CREATE INDEX IF NOT EXISTS idx_domain_event_outbox_delivery
    ON sys_domain_event_outbox (status, available_at, created_at);
CREATE INDEX IF NOT EXISTS idx_domain_event_outbox_aggregate
    ON sys_domain_event_outbox (
        tenant_id, event_source, aggregate_type, aggregate_id,
        aggregate_sequence, status);
CREATE INDEX IF NOT EXISTS idx_domain_event_outbox_correlation
    ON sys_domain_event_outbox (correlation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_domain_event_outbox_tenant
    ON sys_domain_event_outbox (tenant_id, created_at DESC)
    WHERE tenant_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS sys_domain_event_inbox (
    consumer_name VARCHAR(240) NOT NULL,
    event_id UUID NOT NULL,
    event_source VARCHAR(240) NOT NULL,
    event_type VARCHAR(240) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    tenant_id BIGINT,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(240) NOT NULL,
    aggregate_sequence BIGINT NOT NULL CHECK (aggregate_sequence > 0),
    correlation_id VARCHAR(160) NOT NULL,
    causation_id VARCHAR(160),
    trace_parent VARCHAR(80),
    payload JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (
        status IN (
            'RECEIVED', 'PROCESSING', 'SUCCEEDED', 'FAILED',
            'DEFERRED', 'DEAD', 'REPLAY_PENDING', 'DUPLICATE')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    available_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(240),
    lock_token VARCHAR(80),
    locked_until TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX IF NOT EXISTS idx_domain_event_inbox_delivery
    ON sys_domain_event_inbox (consumer_name, status, available_at, created_at);
CREATE INDEX IF NOT EXISTS idx_domain_event_inbox_aggregate
    ON sys_domain_event_inbox (
        consumer_name, tenant_id, event_source, aggregate_type, aggregate_id,
        aggregate_sequence, status);
CREATE INDEX IF NOT EXISTS idx_domain_event_inbox_correlation
    ON sys_domain_event_inbox (correlation_id, created_at DESC);

CREATE TABLE IF NOT EXISTS sys_domain_event_offsets (
    consumer_name VARCHAR(240) NOT NULL,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    event_source VARCHAR(240) NOT NULL,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(240) NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    last_event_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        consumer_name, tenant_id, event_source, aggregate_type, aggregate_id)
);

ALTER TABLE sys_domain_event_offsets
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
UPDATE sys_domain_event_offsets SET tenant_id = 0 WHERE tenant_id IS NULL;
ALTER TABLE sys_domain_event_offsets
    ALTER COLUMN tenant_id SET DEFAULT 0,
    ALTER COLUMN tenant_id SET NOT NULL;

DO $tenant_offset_key$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'sys_domain_event_offsets'::regclass
           AND contype = 'p'
           AND pg_get_constraintdef(oid) NOT LIKE '%tenant_id%'
    ) THEN
        ALTER TABLE sys_domain_event_offsets
            DROP CONSTRAINT sys_domain_event_offsets_pkey;
        ALTER TABLE sys_domain_event_offsets
            ADD CONSTRAINT sys_domain_event_offsets_pkey PRIMARY KEY (
                consumer_name, tenant_id, event_source, aggregate_type, aggregate_id);
    END IF;
END
$tenant_offset_key$;

CREATE TABLE IF NOT EXISTS sys_domain_event_replay_audit (
    replay_request_id UUID PRIMARY KEY,
    direction VARCHAR(16) NOT NULL CHECK (direction IN ('OUTBOX', 'INBOX')),
    target_id VARCHAR(240) NOT NULL,
    consumer_name VARCHAR(240),
    requested_by VARCHAR(240) NOT NULL,
    reason VARCHAR(1200) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_domain_event_replay_audit_target
    ON sys_domain_event_replay_audit (direction, target_id, requested_at DESC);

CREATE OR REPLACE VIEW sys_domain_event_dead_letters AS
SELECT
    'OUTBOX'::VARCHAR(16) AS direction,
    NULL::VARCHAR(240) AS consumer_name,
    event_id,
    event_source,
    event_type,
    schema_version,
    tenant_id,
    aggregate_type,
    aggregate_id,
    aggregate_sequence,
    correlation_id,
    payload_sha256,
    attempt_count,
    replay_count,
    last_error,
    dead_lettered_at
FROM sys_domain_event_outbox
WHERE status = 'DEAD'
UNION ALL
SELECT
    'INBOX'::VARCHAR(16),
    consumer_name,
    event_id,
    event_source,
    event_type,
    schema_version,
    tenant_id,
    aggregate_type,
    aggregate_id,
    aggregate_sequence,
    correlation_id,
    payload_sha256,
    attempt_count,
    replay_count,
    last_error,
    dead_lettered_at
FROM sys_domain_event_inbox
WHERE status = 'DEAD';

COMMENT ON TABLE sys_domain_event_outbox IS
    'Transactional producer ledger for versioned DWP domain events.';
COMMENT ON TABLE sys_domain_event_inbox IS
    'Idempotent consumer ledger with leases, retry, ordering, and DLQ state.';
COMMENT ON TABLE sys_domain_event_offsets IS
    'Last successfully applied sequence by consumer and aggregate.';
COMMENT ON TABLE sys_domain_event_replay_audit IS
    'Append-only operator evidence for controlled event replay requests.';
