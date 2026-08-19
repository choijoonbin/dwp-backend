ALTER TABLE ntf_notification_intents
    ADD COLUMN type_key VARCHAR(160);

UPDATE ntf_notification_intents intent
   SET type_key = type.type_key
  FROM ntf_notification_type_versions type_version
  JOIN ntf_notification_types type
    ON type.type_id = type_version.type_id
 WHERE type_version.type_version_id = intent.type_version_id;

ALTER TABLE ntf_notification_intents
    ALTER COLUMN type_key SET NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ntf_notification_intents
         GROUP BY tenant_id, source_event_id, type_key
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Duplicate notification intents must be reconciled before event identity hardening';
    END IF;
END
$$;

ALTER TABLE ntf_notification_intents
    DROP CONSTRAINT uq_ntf_intent_event_type,
    ADD CONSTRAINT uq_ntf_intent_event_type_key
        UNIQUE (tenant_id, source_event_id, type_key);

ALTER TABLE ntf_notifications
    ADD COLUMN type_key VARCHAR(160);

UPDATE ntf_notifications notification
   SET type_key = type.type_key
  FROM ntf_notification_type_versions type_version
  JOIN ntf_notification_types type
    ON type.type_id = type_version.type_id
 WHERE type_version.type_version_id = notification.type_version_id;

ALTER TABLE ntf_notifications
    ALTER COLUMN type_key SET NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ntf_notifications
         WHERE closed_at IS NULL
         GROUP BY tenant_id, type_key, thread_key
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Open notification threads must be reconciled before thread identity hardening';
    END IF;
END
$$;

DROP INDEX uq_ntf_active_thread;

CREATE UNIQUE INDEX uq_ntf_active_thread
    ON ntf_notifications (tenant_id, type_key, thread_key)
    WHERE closed_at IS NULL;

COMMENT ON COLUMN ntf_notification_intents.type_key IS
    'Stable notification contract identity used for source-event idempotency across contract versions.';
COMMENT ON COLUMN ntf_notifications.type_key IS
    'Stable notification type identity used to preserve an open thread across contract versions.';
