ALTER TABLE ntf_delivery_admission_receipts
    ADD COLUMN attention_rule_id UUID,
    ADD COLUMN attention_scope_kind VARCHAR(30),
    ADD COLUMN attention_effect VARCHAR(20),
    ADD COLUMN attention_rule_revision BIGINT,
    ADD COLUMN attention_policy_source VARCHAR(30),
    ADD CONSTRAINT ck_ntf_admission_attention_scope CHECK (
        attention_scope_kind IS NULL OR
        attention_scope_kind IN ('APP_TYPE', 'ACTOR', 'THREAD', 'RESOURCE', 'TOPIC_TOKEN')
    ),
    ADD CONSTRAINT ck_ntf_admission_attention_effect CHECK (
        attention_effect IS NULL OR attention_effect IN ('FOLLOW', 'PRIORITIZE', 'MUTE')
    ),
    ADD CONSTRAINT ck_ntf_admission_attention_revision CHECK (
        attention_rule_revision IS NULL OR attention_rule_revision > 0
    ),
    ADD CONSTRAINT ck_ntf_admission_attention_complete CHECK (
        (attention_rule_id IS NULL
         AND attention_scope_kind IS NULL
         AND attention_effect IS NULL
         AND attention_rule_revision IS NULL
         AND attention_policy_source IS NULL)
        OR
        (attention_rule_id IS NOT NULL
         AND attention_scope_kind IS NOT NULL
         AND attention_effect IS NOT NULL
         AND attention_rule_revision IS NOT NULL
         AND attention_policy_source IS NOT NULL)
    );

ALTER TABLE ntf_user_notifications DISABLE ROW LEVEL SECURITY;

ALTER TABLE ntf_user_notifications
    ADD COLUMN attention_rule_id UUID,
    ADD COLUMN attention_scope_kind VARCHAR(30),
    ADD COLUMN attention_effect VARCHAR(20),
    ADD COLUMN attention_rule_revision BIGINT,
    ADD COLUMN attention_policy_source VARCHAR(30),
    ADD CONSTRAINT ck_ntf_user_notification_attention_scope CHECK (
        attention_scope_kind IS NULL OR
        attention_scope_kind IN ('APP_TYPE', 'ACTOR', 'THREAD', 'RESOURCE', 'TOPIC_TOKEN')
    ),
    ADD CONSTRAINT ck_ntf_user_notification_attention_effect CHECK (
        attention_effect IS NULL OR attention_effect IN ('FOLLOW', 'PRIORITIZE')
    ),
    ADD CONSTRAINT ck_ntf_user_notification_attention_revision CHECK (
        attention_rule_revision IS NULL OR attention_rule_revision > 0
    ),
    ADD CONSTRAINT ck_ntf_user_notification_attention_complete CHECK (
        (attention_rule_id IS NULL
         AND attention_scope_kind IS NULL
         AND attention_effect IS NULL
         AND attention_rule_revision IS NULL
         AND attention_policy_source IS NULL)
        OR
        (attention_rule_id IS NOT NULL
         AND attention_scope_kind IS NOT NULL
         AND attention_effect IS NOT NULL
         AND attention_rule_revision IS NOT NULL
         AND attention_policy_source IS NOT NULL)
    );

ALTER TABLE ntf_user_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications FORCE ROW LEVEL SECURITY;

CREATE INDEX ix_ntf_admission_attention_rule
    ON ntf_delivery_admission_receipts (
        tenant_id, attention_rule_id, created_at DESC
    ) WHERE attention_rule_id IS NOT NULL;

CREATE INDEX ix_ntf_user_notification_attention_rule
    ON ntf_user_notifications (
        tenant_id, user_id, attention_rule_id, last_activity_at DESC
    ) WHERE attention_rule_id IS NOT NULL;

COMMENT ON COLUMN ntf_delivery_admission_receipts.attention_rule_id IS
    'Matched exact attention rule; scope key and notification content are never copied here.';
COMMENT ON COLUMN ntf_user_notifications.attention_rule_id IS
    'Attention rule snapshot used for this recipient projection; deleting a rule does not rewrite history.';
