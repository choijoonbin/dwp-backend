ALTER TABLE msg_scheduled_messages
    ADD COLUMN payload_purged_at TIMESTAMPTZ;

ALTER TABLE msg_scheduled_messages
    DROP CONSTRAINT ck_msg_scheduled_body,
    ADD CONSTRAINT ck_msg_scheduled_body CHECK (
        (payload_purged_at IS NULL AND length(btrim(body)) BETWEEN 1 AND 20000)
        OR (payload_purged_at IS NOT NULL
            AND status IN ('SENT', 'FAILED', 'CANCELLED')
            AND body = ''
            AND cardinality(mentioned_user_ids) = 0
            AND failure_detail IS NULL
            AND request_hash = encode(digest(
                'msg-scheduled-payload-purged:' || scheduled_message_id::text,
                'sha256'), 'hex'))
    );

CREATE INDEX ix_msg_drafts_retention_candidates
    ON msg_message_drafts (tenant_id, updated_at, draft_id);

CREATE INDEX ix_msg_scheduled_retention_candidates
    ON msg_scheduled_messages (tenant_id, updated_at, scheduled_message_id)
    WHERE status IN ('SENT', 'FAILED', 'CANCELLED')
      AND payload_purged_at IS NULL;

CREATE TABLE msg_scheduled_delivery_worker_heartbeats (
    worker_id UUID PRIMARY KEY,
    last_started_at TIMESTAMPTZ NOT NULL,
    last_completed_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_failure_code VARCHAR(80),
    claimed_count INTEGER NOT NULL DEFAULT 0,
    delivered_count INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_msg_scheduled_worker_counts CHECK (
        claimed_count >= 0 AND delivered_count >= 0
        AND retry_count >= 0 AND failed_count >= 0),
    CONSTRAINT ck_msg_scheduled_worker_failure CHECK (
        (last_failure_at IS NULL AND last_failure_code IS NULL)
        OR (last_failure_at IS NOT NULL AND last_failure_code IS NOT NULL))
);

CREATE INDEX ix_msg_scheduled_worker_freshness
    ON msg_scheduled_delivery_worker_heartbeats (last_completed_at DESC, worker_id);

COMMENT ON COLUMN msg_scheduled_messages.payload_purged_at IS
    'Governed retention redaction timestamp. Delivery state and immutable evidence remain; author payload is removed.';
COMMENT ON COLUMN msg_scheduled_messages.request_hash IS
    'Request digest before payload retention expiry; deterministic scheduled-message tombstone digest after payload purge.';
COMMENT ON TABLE msg_scheduled_delivery_worker_heartbeats IS
    'Payload-free, cross-instance scheduled delivery cycle evidence used by health and admin readiness.';
