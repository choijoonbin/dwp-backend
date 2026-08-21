ALTER TABLE msg_conversation_members
    DROP CONSTRAINT ck_msg_member_notification;

ALTER TABLE msg_conversation_members
    ADD CONSTRAINT ck_msg_member_notification CHECK (
        notification_level IN ('DEFAULT', 'ALL', 'MENTIONS', 'MUTE'));

COMMENT ON COLUMN msg_conversation_members.notification_level IS
    'DEFAULT follows product attention policy; ALL explicitly subscribes to every group message; MENTIONS limits delivery to mentions and replies; MUTE suppresses conversation notifications.';
