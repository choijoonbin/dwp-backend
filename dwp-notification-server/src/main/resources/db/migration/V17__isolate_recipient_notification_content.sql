-- A shared notification thread is an aggregation identity, not a safe place for
-- recipient-visible content. Snapshot all rendered content and counters on the
-- user projection so a later event addressed to a different audience cannot
-- mutate another user's inbox or deep link.
ALTER TABLE ntf_user_notifications DISABLE ROW LEVEL SECURITY;

ALTER TABLE ntf_user_notifications
    ADD COLUMN actor_ref VARCHAR(300),
    ADD COLUMN subject_ref VARCHAR(300),
    ADD COLUMN target_ref VARCHAR(300),
    ADD COLUMN safe_body TEXT,
    ADD COLUMN action_payload JSONB,
    ADD COLUMN first_activity_at TIMESTAMPTZ,
    ADD COLUMN occurrence_count BIGINT;

UPDATE ntf_user_notifications user_notification
   SET actor_ref = notification.actor_ref,
       subject_ref = notification.subject_ref,
       target_ref = notification.target_ref,
       safe_body = notification.safe_body,
       action_payload = notification.action_payload,
       first_activity_at = notification.first_activity_at,
       occurrence_count = notification.occurrence_count
  FROM ntf_notifications notification
 WHERE notification.tenant_id = user_notification.tenant_id
   AND notification.notification_id = user_notification.notification_id;

ALTER TABLE ntf_user_notifications
    ALTER COLUMN safe_body SET DEFAULT '',
    ALTER COLUMN safe_body SET NOT NULL,
    ALTER COLUMN action_payload SET DEFAULT '{}'::jsonb,
    ALTER COLUMN action_payload SET NOT NULL,
    ALTER COLUMN first_activity_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN first_activity_at SET NOT NULL,
    ALTER COLUMN occurrence_count SET DEFAULT 1,
    ALTER COLUMN occurrence_count SET NOT NULL,
    ADD CONSTRAINT ck_ntf_user_notification_occurrence_count
        CHECK (occurrence_count > 0);

COMMENT ON COLUMN ntf_user_notifications.safe_body IS
    'Recipient-scoped rendered body; never read recipient content from the shared thread.';
COMMENT ON COLUMN ntf_user_notifications.action_payload IS
    'Recipient-scoped rendered action payload and deep link.';

ALTER TABLE ntf_user_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications FORCE ROW LEVEL SECURITY;
