-- Deep links must be revalidated when their source object is deleted or access
-- is revoked. Keep the state on the recipient projection so shared thread
-- aggregation cannot leak or revive another recipient's target.
ALTER TABLE ntf_user_notifications DISABLE ROW LEVEL SECURITY;

ALTER TABLE ntf_user_notifications
    ADD COLUMN target_state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN target_state_reason VARCHAR(500),
    ADD CONSTRAINT ck_ntf_user_notification_target_state
        CHECK (target_state IN ('AVAILABLE', 'DELETED', 'FORBIDDEN')),
    ADD CONSTRAINT ck_ntf_user_notification_target_reason
        CHECK (
            (target_state = 'AVAILABLE' AND target_state_reason IS NULL)
            OR
            (target_state <> 'AVAILABLE' AND target_state_reason IS NOT NULL)
        );

CREATE INDEX ix_ntf_user_notifications_target
    ON ntf_user_notifications (tenant_id, target_ref)
    WHERE target_ref IS NOT NULL;

COMMENT ON COLUMN ntf_user_notifications.target_state IS
    'Recipient-scoped source target lifecycle used for authoritative deep-link resolution.';

ALTER TABLE ntf_user_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications FORCE ROW LEVEL SECURITY;
