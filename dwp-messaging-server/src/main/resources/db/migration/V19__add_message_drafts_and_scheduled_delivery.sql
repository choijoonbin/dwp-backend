CREATE TABLE msg_message_drafts (
    draft_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    thread_root_message_id UUID,
    body TEXT NOT NULL DEFAULT '',
    mentioned_user_ids BIGINT[] NOT NULL DEFAULT '{}'::BIGINT[],
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_draft_member
        FOREIGN KEY (tenant_id, conversation_id, user_id)
        REFERENCES msg_conversation_members (tenant_id, conversation_id, user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_msg_draft_thread_root
        FOREIGN KEY (tenant_id, conversation_id, thread_root_message_id)
        REFERENCES msg_messages (tenant_id, conversation_id, message_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_msg_draft_scope
        UNIQUE NULLS NOT DISTINCT (
            tenant_id, user_id, conversation_id, thread_root_message_id),
    CONSTRAINT ck_msg_draft_body_length CHECK (length(body) <= 20000),
    CONSTRAINT ck_msg_draft_mentions CHECK (
        cardinality(mentioned_user_ids) <= 50
        AND array_position(mentioned_user_ids, NULL) IS NULL),
    CONSTRAINT ck_msg_draft_version CHECK (version > 0)
);

CREATE INDEX ix_msg_drafts_user_updated
    ON msg_message_drafts (tenant_id, user_id, updated_at DESC);

CREATE TABLE msg_scheduled_messages (
    scheduled_message_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    sender_user_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    body TEXT NOT NULL,
    reply_to_message_id UUID,
    mentioned_user_ids BIGINT[] NOT NULL DEFAULT '{}'::BIGINT[],
    request_idempotency_key UUID NOT NULL,
    send_idempotency_key UUID NOT NULL DEFAULT gen_random_uuid(),
    request_hash CHAR(64) NOT NULL,
    scheduled_for TIMESTAMPTZ NOT NULL,
    display_time_zone VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    processing_started_at TIMESTAMPTZ,
    processing_lease_until TIMESTAMPTZ,
    delivered_message_id UUID,
    delivered_at TIMESTAMPTZ,
    failure_code VARCHAR(80),
    failure_detail VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_scheduled_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES msg_conversations (tenant_id, conversation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_msg_scheduled_reply
        FOREIGN KEY (tenant_id, conversation_id, reply_to_message_id)
        REFERENCES msg_messages (tenant_id, conversation_id, message_id),
    CONSTRAINT fk_msg_scheduled_delivery
        FOREIGN KEY (tenant_id, conversation_id, delivered_message_id)
        REFERENCES msg_messages (tenant_id, conversation_id, message_id),
    CONSTRAINT uk_msg_scheduled_request
        UNIQUE (tenant_id, sender_user_id, request_idempotency_key),
    CONSTRAINT uk_msg_scheduled_send_key
        UNIQUE (tenant_id, sender_user_id, send_idempotency_key),
    CONSTRAINT ck_msg_scheduled_body CHECK (
        length(btrim(body)) BETWEEN 1 AND 20000),
    CONSTRAINT ck_msg_scheduled_mentions CHECK (
        cardinality(mentioned_user_ids) <= 50
        AND array_position(mentioned_user_ids, NULL) IS NULL),
    CONSTRAINT ck_msg_scheduled_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_msg_scheduled_zone CHECK (
        length(btrim(display_time_zone)) BETWEEN 1 AND 64),
    CONSTRAINT ck_msg_scheduled_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_msg_scheduled_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_msg_scheduled_version CHECK (version >= 0),
    CONSTRAINT ck_msg_scheduled_processing CHECK (
        (status = 'PROCESSING'
            AND processing_started_at IS NOT NULL
            AND processing_lease_until IS NOT NULL)
        OR (status <> 'PROCESSING'
            AND processing_started_at IS NULL
            AND processing_lease_until IS NULL)),
    CONSTRAINT ck_msg_scheduled_delivery_state CHECK (
        (status = 'SENT'
            AND delivered_message_id IS NOT NULL
            AND delivered_at IS NOT NULL)
        OR (status <> 'SENT'
            AND delivered_message_id IS NULL
            AND delivered_at IS NULL)),
    CONSTRAINT ck_msg_scheduled_failure_state CHECK (
        (status = 'FAILED' AND failure_code IS NOT NULL)
        OR status <> 'FAILED')
);

CREATE INDEX ix_msg_scheduled_due
    ON msg_scheduled_messages (next_attempt_at, scheduled_message_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_msg_scheduled_expired_lease
    ON msg_scheduled_messages (processing_lease_until, scheduled_message_id)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_msg_scheduled_user
    ON msg_scheduled_messages (
        tenant_id, sender_user_id, created_at DESC, scheduled_message_id DESC);

COMMENT ON TABLE msg_message_drafts IS
    'Server-authoritative per-user composer continuity, scoped to a conversation or thread.';
COMMENT ON TABLE msg_scheduled_messages IS
    'Durable scheduled delivery commands. Attachments are intentionally excluded until governed reservation exists.';
COMMENT ON COLUMN msg_scheduled_messages.display_time_zone IS
    'IANA zone used only to preserve the author display intent; scheduled_for is the UTC instant.';
