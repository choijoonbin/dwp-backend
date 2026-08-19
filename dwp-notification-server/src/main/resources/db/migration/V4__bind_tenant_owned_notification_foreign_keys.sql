ALTER TABLE ntf_notification_intents DISABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notifications DISABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications DISABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_delivery_jobs DISABLE ROW LEVEL SECURITY;

ALTER TABLE ntf_notification_types
    ADD COLUMN scope_tenant_id BIGINT
        GENERATED ALWAYS AS (COALESCE(tenant_id, 0)) STORED,
    ADD CONSTRAINT uq_ntf_type_scope_tenant_identity
        UNIQUE (scope_tenant_id, type_id);

ALTER TABLE ntf_notification_type_versions
    ADD COLUMN scope_tenant_id BIGINT
        GENERATED ALWAYS AS (COALESCE(tenant_id, 0)) STORED,
    ADD CONSTRAINT uq_ntf_type_version_scope_tenant_identity
        UNIQUE (scope_tenant_id, type_version_id),
    ADD CONSTRAINT fk_ntf_type_version_scope_tenant_type
        FOREIGN KEY (scope_tenant_id, type_id)
        REFERENCES ntf_notification_types (scope_tenant_id, type_id);

ALTER TABLE ntf_template_versions
    ADD COLUMN scope_tenant_id BIGINT
        GENERATED ALWAYS AS (COALESCE(tenant_id, 0)) STORED,
    ADD CONSTRAINT uq_ntf_template_scope_tenant_identity
        UNIQUE (scope_tenant_id, template_version_id),
    ADD CONSTRAINT fk_ntf_template_scope_tenant_type_version
        FOREIGN KEY (scope_tenant_id, type_version_id)
        REFERENCES ntf_notification_type_versions (scope_tenant_id, type_version_id);

ALTER TABLE ntf_routing_policies
    ADD COLUMN scope_tenant_id BIGINT
        GENERATED ALWAYS AS (COALESCE(tenant_id, 0)) STORED,
    ADD CONSTRAINT uq_ntf_policy_scope_tenant_identity
        UNIQUE (scope_tenant_id, policy_id);

ALTER TABLE ntf_policy_channel_rules
    ADD COLUMN scope_tenant_id BIGINT
        GENERATED ALWAYS AS (COALESCE(tenant_id, 0)) STORED,
    ADD CONSTRAINT fk_ntf_policy_channel_scope_tenant_policy
        FOREIGN KEY (scope_tenant_id, policy_id)
        REFERENCES ntf_routing_policies (scope_tenant_id, policy_id);

ALTER TABLE ntf_notification_intents
    ADD COLUMN type_scope_tenant_id BIGINT;
UPDATE ntf_notification_intents intent
   SET type_scope_tenant_id = COALESCE(type_version.tenant_id, 0)
  FROM ntf_notification_type_versions type_version
 WHERE type_version.type_version_id = intent.type_version_id;
ALTER TABLE ntf_notification_intents
    ALTER COLUMN type_scope_tenant_id SET NOT NULL,
    ADD CONSTRAINT ck_ntf_intent_type_scope
        CHECK (type_scope_tenant_id = 0 OR type_scope_tenant_id = tenant_id),
    ADD CONSTRAINT fk_ntf_intent_scope_tenant_type_version
        FOREIGN KEY (type_scope_tenant_id, type_version_id)
        REFERENCES ntf_notification_type_versions (scope_tenant_id, type_version_id);

ALTER TABLE ntf_notifications
    ADD COLUMN type_scope_tenant_id BIGINT;
UPDATE ntf_notifications notification
   SET type_scope_tenant_id = COALESCE(type_version.tenant_id, 0)
  FROM ntf_notification_type_versions type_version
 WHERE type_version.type_version_id = notification.type_version_id;
ALTER TABLE ntf_notifications
    ALTER COLUMN type_scope_tenant_id SET NOT NULL,
    ADD CONSTRAINT ck_ntf_notification_type_scope
        CHECK (type_scope_tenant_id = 0 OR type_scope_tenant_id = tenant_id),
    ADD CONSTRAINT fk_ntf_notification_scope_tenant_type_version
        FOREIGN KEY (type_scope_tenant_id, type_version_id)
        REFERENCES ntf_notification_type_versions (scope_tenant_id, type_version_id);

ALTER TABLE ntf_user_notifications
    ADD COLUMN template_scope_tenant_id BIGINT;
UPDATE ntf_user_notifications user_notification
   SET template_scope_tenant_id = COALESCE(template.tenant_id, 0)
  FROM ntf_template_versions template
 WHERE template.template_version_id = user_notification.in_app_template_version_id;
ALTER TABLE ntf_user_notifications
    ALTER COLUMN template_scope_tenant_id SET NOT NULL,
    ADD CONSTRAINT ck_ntf_user_notification_template_scope
        CHECK (template_scope_tenant_id = 0 OR template_scope_tenant_id = tenant_id),
    ADD CONSTRAINT fk_ntf_user_notification_scope_tenant_template
        FOREIGN KEY (template_scope_tenant_id, in_app_template_version_id)
        REFERENCES ntf_template_versions (scope_tenant_id, template_version_id);

ALTER TABLE ntf_delivery_jobs
    ADD COLUMN template_scope_tenant_id BIGINT;
UPDATE ntf_delivery_jobs job
   SET template_scope_tenant_id = COALESCE(template.tenant_id, 0)
  FROM ntf_template_versions template
 WHERE template.template_version_id = job.template_version_id;
ALTER TABLE ntf_delivery_jobs
    ALTER COLUMN template_scope_tenant_id SET NOT NULL,
    ADD CONSTRAINT ck_ntf_delivery_template_scope
        CHECK (template_scope_tenant_id = 0 OR template_scope_tenant_id = tenant_id),
    ADD CONSTRAINT fk_ntf_delivery_scope_tenant_template
        FOREIGN KEY (template_scope_tenant_id, template_version_id)
        REFERENCES ntf_template_versions (scope_tenant_id, template_version_id);

ALTER TABLE ntf_notification_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notification_intents FORCE ROW LEVEL SECURITY;
ALTER TABLE ntf_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_notifications FORCE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_user_notifications FORCE ROW LEVEL SECURITY;
ALTER TABLE ntf_delivery_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ntf_delivery_jobs FORCE ROW LEVEL SECURITY;
