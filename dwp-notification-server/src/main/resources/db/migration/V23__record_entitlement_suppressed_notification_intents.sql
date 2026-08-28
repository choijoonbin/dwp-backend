ALTER TABLE ntf_notification_intents
    DROP CONSTRAINT ntf_notification_intents_decision_check;

ALTER TABLE ntf_notification_intents
    ADD CONSTRAINT ck_ntf_notification_intent_decision
        CHECK (decision IN ('MATERIALIZED', 'DUPLICATE', 'QUARANTINED', 'SUPPRESSED'));

COMMENT ON COLUMN ntf_notification_intents.decision IS
    'Admission outcome. SUPPRESSED records an idempotent source receipt without creating notification, recipient projection, or delivery outbox rows.';
