ALTER TABLE msg_conversation_members
    ADD COLUMN history_start_sequence BIGINT,
    ADD COLUMN membership_started_at TIMESTAMPTZ;

UPDATE msg_conversation_members
   SET history_start_sequence = 1,
       membership_started_at = created_at;

ALTER TABLE msg_conversation_members
    ALTER COLUMN history_start_sequence SET NOT NULL,
    ALTER COLUMN history_start_sequence SET DEFAULT 1,
    ALTER COLUMN membership_started_at SET NOT NULL,
    ALTER COLUMN membership_started_at SET DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT ck_msg_member_history_start_positive
        CHECK (history_start_sequence > 0),
    ADD CONSTRAINT ck_msg_member_read_not_before_history
        CHECK (last_read_sequence >= history_start_sequence - 1);

CREATE INDEX ix_msg_member_active_history
    ON msg_conversation_members (
        tenant_id, conversation_id, user_id, history_start_sequence)
    WHERE lifecycle_state = 'ACTIVE';

COMMENT ON COLUMN msg_conversation_members.history_start_sequence IS
    'FROM_JOIN visibility boundary. Members may access only messages at or after this conversation-local sequence.';
COMMENT ON COLUMN msg_conversation_members.membership_started_at IS
    'Start time of the current active membership term; refreshed when a revoked member rejoins.';
