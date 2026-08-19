ALTER TABLE msg_messages
    ADD CONSTRAINT uk_msg_message_tenant_id
        UNIQUE (tenant_id, message_id),
    ADD CONSTRAINT uk_msg_message_thread_parent
        UNIQUE (tenant_id, conversation_id, message_id),
    ADD CONSTRAINT ck_msg_reply_not_self
        CHECK (reply_to_message_id IS NULL OR reply_to_message_id <> message_id);

ALTER TABLE msg_messages
    DROP CONSTRAINT fk_msg_reply_parent,
    ADD CONSTRAINT fk_msg_reply_parent_same_conversation
        FOREIGN KEY (tenant_id, conversation_id, reply_to_message_id)
        REFERENCES msg_messages (tenant_id, conversation_id, message_id);

ALTER TABLE msg_message_reactions
    DROP CONSTRAINT msg_message_reactions_message_id_fkey,
    ADD CONSTRAINT fk_msg_reaction_tenant_message
        FOREIGN KEY (tenant_id, message_id)
        REFERENCES msg_messages (tenant_id, message_id)
        ON DELETE CASCADE;

ALTER TABLE msg_saved_items
    DROP CONSTRAINT msg_saved_items_message_id_fkey,
    ADD CONSTRAINT fk_msg_saved_tenant_message
        FOREIGN KEY (tenant_id, message_id)
        REFERENCES msg_messages (tenant_id, message_id)
        ON DELETE CASCADE;

CREATE INDEX ix_msg_messages_thread
    ON msg_messages (tenant_id, conversation_id, reply_to_message_id, created_at, message_id)
    WHERE reply_to_message_id IS NOT NULL;

CREATE INDEX ix_msg_saved_user_created
    ON msg_saved_items (tenant_id, user_id, created_at DESC, message_id DESC);

CREATE TABLE msg_realtime_events (
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    tenant_id BIGINT NOT NULL,
    audience_user_id BIGINT,
    conversation_id UUID,
    message_id UUID,
    actor_user_id BIGINT NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_msg_realtime_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES msg_conversations (tenant_id, conversation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_msg_realtime_message
        FOREIGN KEY (tenant_id, message_id)
        REFERENCES msg_messages (tenant_id, message_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_msg_realtime_event_type
        CHECK (length(btrim(event_type)) BETWEEN 1 AND 120),
    CONSTRAINT ck_msg_realtime_payload
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX ix_msg_realtime_tenant_sequence
    ON msg_realtime_events (tenant_id, event_sequence);

CREATE INDEX ix_msg_realtime_conversation_sequence
    ON msg_realtime_events (tenant_id, conversation_id, event_sequence)
    WHERE conversation_id IS NOT NULL;

CREATE INDEX ix_msg_realtime_audience_sequence
    ON msg_realtime_events (tenant_id, audience_user_id, event_sequence)
    WHERE audience_user_id IS NOT NULL;

COMMENT ON TABLE msg_realtime_events IS
    'Durable tenant-scoped messaging change log used for SSE replay and reconnect recovery.';
