ALTER TABLE msg_messages
    ADD COLUMN sequence BIGINT;

WITH ranked_messages AS (
    SELECT message_id,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, conversation_id
               ORDER BY created_at, message_id) AS message_sequence
      FROM msg_messages
)
UPDATE msg_messages message
   SET sequence = ranked.message_sequence
  FROM ranked_messages ranked
 WHERE ranked.message_id = message.message_id;

ALTER TABLE msg_messages
    ALTER COLUMN sequence SET NOT NULL,
    ADD CONSTRAINT ck_msg_message_sequence_positive CHECK (sequence > 0),
    ADD CONSTRAINT uk_msg_message_conversation_sequence
        UNIQUE (tenant_id, conversation_id, sequence);

CREATE TABLE msg_conversation_sequences (
    tenant_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    next_sequence BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, conversation_id),
    CONSTRAINT fk_msg_sequence_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES msg_conversations (tenant_id, conversation_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_msg_conversation_next_sequence CHECK (next_sequence > 0)
);

INSERT INTO msg_conversation_sequences (tenant_id, conversation_id, next_sequence)
SELECT conversation.tenant_id,
       conversation.conversation_id,
       COALESCE(MAX(message.sequence), 0) + 1
  FROM msg_conversations conversation
  LEFT JOIN msg_messages message
    ON message.tenant_id = conversation.tenant_id
   AND message.conversation_id = conversation.conversation_id
 GROUP BY conversation.tenant_id, conversation.conversation_id;

CREATE TABLE msg_idempotency_keys (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_message_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, user_id, operation, idempotency_key),
    CONSTRAINT fk_msg_idempotency_result
        FOREIGN KEY (tenant_id, result_message_id)
        REFERENCES msg_messages (tenant_id, message_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_msg_idempotency_operation
        CHECK (length(btrim(operation)) BETWEEN 1 AND 80),
    CONSTRAINT ck_msg_idempotency_request_hash
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_msg_idempotency_completion
        CHECK ((result_message_id IS NULL AND completed_at IS NULL)
            OR (result_message_id IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_msg_idempotency_created
    ON msg_idempotency_keys (tenant_id, created_at);

ALTER TABLE msg_conversation_members
    ADD COLUMN last_read_sequence BIGINT NOT NULL DEFAULT 0;

UPDATE msg_conversation_members member
   SET last_read_message_id = NULL,
       last_read_at = NULL
 WHERE member.last_read_message_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
         FROM msg_messages message
        WHERE message.tenant_id = member.tenant_id
          AND message.conversation_id = member.conversation_id
          AND message.message_id = member.last_read_message_id);

UPDATE msg_conversation_members member
   SET last_read_sequence = message.sequence,
       last_read_at = message.created_at
  FROM msg_messages message
 WHERE message.tenant_id = member.tenant_id
   AND message.conversation_id = member.conversation_id
   AND message.message_id = member.last_read_message_id;

ALTER TABLE msg_conversation_members
    ADD CONSTRAINT ck_msg_member_read_sequence_non_negative
        CHECK (last_read_sequence >= 0),
    ADD CONSTRAINT fk_msg_member_read_cursor_message
        FOREIGN KEY (tenant_id, conversation_id, last_read_message_id)
        REFERENCES msg_messages (tenant_id, conversation_id, message_id);

ALTER TABLE msg_realtime_events
    ADD COLUMN message_sequence BIGINT;

UPDATE msg_realtime_events event
   SET message_sequence = message.sequence
  FROM msg_messages message
 WHERE message.tenant_id = event.tenant_id
   AND message.message_id = event.message_id;

ALTER TABLE msg_realtime_events
    ADD CONSTRAINT ck_msg_realtime_message_sequence_positive
        CHECK (message_sequence IS NULL OR message_sequence > 0);

CREATE INDEX ix_msg_realtime_message_sequence
    ON msg_realtime_events (tenant_id, conversation_id, message_sequence)
    WHERE message_sequence IS NOT NULL;

COMMENT ON COLUMN msg_messages.sequence IS
    'Gap-free while transactions commit normally; strictly increasing within one tenant conversation.';
COMMENT ON TABLE msg_conversation_sequences IS
    'Row-locked allocator for conversation-local message ordering.';
COMMENT ON TABLE msg_idempotency_keys IS
    'Concurrency-safe command ledger. A reused key is valid only for an identical SHA-256 request fingerprint.';
COMMENT ON COLUMN msg_conversation_members.last_read_sequence IS
    'Monotonic conversation-local read cursor. Zero means that no message has been read.';
COMMENT ON TABLE msg_realtime_events IS
    'Canonical transactional messaging domain-event log and SSE replay source.
External broker publication is downstream work, not implied by this table.';
