ALTER TABLE msg_user_privacy_preferences
    ADD COLUMN presence_sharing_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN msg_user_privacy_preferences.presence_sharing_enabled IS
    'Self-owned consent for publishing an ephemeral Messenger presence lease. FALSE is enforced before every heartbeat and withdraws any current lease.';
