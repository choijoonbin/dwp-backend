CREATE TABLE msg_message_mentions (
    tenant_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    mentioned_user_id BIGINT NOT NULL,
    display_name_snapshot VARCHAR(160) NOT NULL,
    mention_kind VARCHAR(16) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, message_id, mentioned_user_id),
    CONSTRAINT fk_msg_mention_message
        FOREIGN KEY (tenant_id, conversation_id, message_id)
        REFERENCES msg_messages (tenant_id, conversation_id, message_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_msg_mention_kind CHECK (mention_kind IN ('USER', 'ALL')),
    CONSTRAINT ck_msg_mention_display_name
        CHECK (length(btrim(display_name_snapshot)) BETWEEN 1 AND 160)
);

CREATE INDEX ix_msg_mentions_user_unread
    ON msg_message_mentions (tenant_id, mentioned_user_id, conversation_id, created_at DESC);

COMMENT ON TABLE msg_message_mentions IS
    'Structured, tenant-scoped message mentions. Display names are immutable send-time snapshots.';
