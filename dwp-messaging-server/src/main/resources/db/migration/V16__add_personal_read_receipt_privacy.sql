CREATE TABLE msg_user_privacy_preferences (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    read_receipts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_msg_privacy_version CHECK (version > 0)
);

COMMENT ON TABLE msg_user_privacy_preferences IS
    'Unilateral self-owned receipt sharing consent. Missing rows mean enabled/version zero. Never changes the durable unread cursor.';

CREATE TABLE msg_message_read_observations (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, user_id, message_id),
    CONSTRAINT fk_msg_observation_message FOREIGN KEY (tenant_id, conversation_id, message_id)
        REFERENCES msg_messages (tenant_id, conversation_id, message_id) ON DELETE CASCADE,
    CONSTRAINT fk_msg_observation_member FOREIGN KEY (tenant_id, conversation_id, user_id)
        REFERENCES msg_conversation_members (tenant_id, conversation_id, user_id) ON DELETE CASCADE
);

CREATE INDEX ix_msg_observation_message
    ON msg_message_read_observations (tenant_id, message_id, user_id);

COMMENT ON TABLE msg_message_read_observations IS
    'Idempotent explicit message visibility observations, retained while sharing is disabled. No cursor backfill or inferred per-message read timestamp.';

-- Repair any incorrectly addressed historical self events before enforcing the boundary.
UPDATE msg_realtime_events
   SET audience_user_id = actor_user_id
 WHERE event_type IN ('messaging.read-cursor.updated', 'messaging.privacy-preferences.updated')
   AND audience_user_id IS DISTINCT FROM actor_user_id;

ALTER TABLE msg_realtime_events
    ADD CONSTRAINT ck_msg_private_receipt_events CHECK (
        event_type NOT IN ('messaging.read-cursor.updated', 'messaging.privacy-preferences.updated')
        OR (audience_user_id IS NOT NULL AND audience_user_id = actor_user_id));
