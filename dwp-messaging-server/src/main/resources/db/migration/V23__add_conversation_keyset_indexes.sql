CREATE INDEX ix_msg_members_conversation_keyset
    ON msg_conversation_members (
        tenant_id, user_id, pinned DESC, favorite DESC, conversation_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX ix_msg_conversations_activity_keyset
    ON msg_conversations (
        tenant_id, last_message_at DESC NULLS LAST, conversation_id)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON INDEX ix_msg_members_conversation_keyset IS
    'Supports tenant/user-scoped conversation keyset traversal without offset scans.';
COMMENT ON INDEX ix_msg_conversations_activity_keyset IS
    'Supports stable recent-activity keyset traversal for active conversations.';
