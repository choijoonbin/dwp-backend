CREATE TEMP TABLE tmp_notification_database_check_contracts (
    source_reference VARCHAR(200) PRIMARY KEY,
    allowed_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_notification_database_check_contracts (
    source_reference, allowed_values)
VALUES
    ('ntf_delivery_admission_receipts.channel', ARRAY['EMAIL', 'IN_APP', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_delivery_admission_receipts.decision', ARRAY['ADMITTED', 'PENDING', 'RATE_LIMITED', 'SUPPRESSED']::VARCHAR[]),
    ('ntf_delivery_jobs.channel', ARRAY['EMAIL', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_delivery_jobs.qos_lane', ARRAY['BULK', 'CRITICAL', 'INTERACTIVE']::VARCHAR[]),
    ('ntf_delivery_jobs.state', ARRAY['DISABLED', 'FAILED', 'LEASED', 'QUEUED', 'SENT', 'UNKNOWN']::VARCHAR[]),
    ('ntf_delivery_rate_windows.channel', ARRAY['EMAIL', 'IN_APP', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_delivery_suppressions.channel', ARRAY['ALL', 'EMAIL', 'IN_APP', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_delivery_suppressions.scope_key', ARRAY['*']::VARCHAR[]),
    ('ntf_delivery_suppressions.scope_type', ARRAY['APP', 'TENANT', 'TYPE']::VARCHAR[]),
    ('ntf_notification_intents.decision', ARRAY['DUPLICATE', 'MATERIALIZED', 'QUARANTINED']::VARCHAR[]),
    ('ntf_notification_type_versions.lifecycle_state', ARRAY['ACTIVE', 'DEPRECATED', 'DRAFT']::VARCHAR[]),
    ('ntf_notification_type_versions.priority', ARRAY['HIGH', 'LOW', 'NORMAL', 'URGENT']::VARCHAR[]),
    ('ntf_notification_type_versions.urgency', ARRAY['ACTIONABLE', 'CRITICAL', 'INFORMATIONAL']::VARCHAR[]),
    ('ntf_notification_types.lifecycle_state', ARRAY['ACTIVE', 'DEPRECATED', 'DISABLED', 'DRAFT']::VARCHAR[]),
    ('ntf_notification_types.scope_type', ARRAY['PROVIDER', 'TENANT']::VARCHAR[]),
    ('ntf_policy_channel_rules.channel', ARRAY['EMAIL', 'IN_APP', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_policy_channel_rules.default_mode', ARRAY['DIGEST', 'IMMEDIATE', 'MUTED']::VARCHAR[]),
    ('ntf_routing_policies.scope_type', ARRAY['APP', 'PROVIDER', 'TENANT', 'TYPE']::VARCHAR[]),
    ('ntf_routing_policies.state', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[]),
    ('ntf_template_versions.channel', ARRAY['EMAIL', 'IN_APP', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_template_versions.state', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[]),
    ('ntf_tenant_template_revisions.channel', ARRAY['EMAIL', 'IN_APP', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_tenant_template_revisions.state', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[]),
    ('ntf_user_delivery_profiles.digest_frequency', ARRAY['DAILY', 'IMMEDIATE', 'NONE', 'WEEKLY']::VARCHAR[]),
    ('ntf_user_notifications.effective_priority', ARRAY['HIGH', 'LOW', 'NORMAL', 'URGENT']::VARCHAR[]),
    ('ntf_user_notifications.inbox_state', ARRAY['ACTIVE', 'DONE']::VARCHAR[]),
    ('ntf_user_subscription_rule_channels.channel', ARRAY['EMAIL', 'IN_APP', 'MOBILE_PUSH', 'SLACK', 'TEAMS', 'WEB_PUSH']::VARCHAR[]),
    ('ntf_user_subscription_rules.delivery_mode', ARRAY['DAILY_DIGEST', 'IMMEDIATE', 'MUTED', 'WEEKLY_DIGEST']::VARCHAR[]),
    ('sys_domain_event_inbox.status', ARRAY['DEAD', 'DEFERRED', 'DUPLICATE', 'FAILED', 'PROCESSING', 'RECEIVED', 'REPLAY_PENDING', 'SUCCEEDED']::VARCHAR[]),
    ('sys_domain_event_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('sys_domain_event_replay_audit.direction', ARRAY['INBOX', 'OUTBOX']::VARCHAR[]);

WITH manifest AS (
    SELECT 'NOTIFICATION.' || UPPER(source_reference) AS code_set_key,
           source_reference,
           allowed_values
      FROM tmp_notification_database_check_contracts
)
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
SELECT code_set_key,
       'dwp-notification-server',
       INITCAP(REPLACE(REPLACE(source_reference, '.', ' '), '_', ' ')),
       'Database CHECK contract for ' || source_reference || '.',
       'SYSTEM', 'CHECK', source_reference,
       CASE
           WHEN source_reference ~ '(status|state|decision|direction)$'
               THEN 'STATE_MACHINE'
           WHEN source_reference ~ '(priority|urgency|channel|qos_lane|scope_type)$'
               THEN 'SECURITY'
           ELSE 'REFERENCE'
       END,
       'ADMIN_ONLY'
  FROM manifest
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    configuration_level = EXCLUDED.configuration_level,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = EXCLUDED.runtime_visibility,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

WITH manifest AS (
    SELECT 'NOTIFICATION.' || UPPER(source_reference) AS code_set_key,
           allowed_values
      FROM tmp_notification_database_check_contracts
)
INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
SELECT manifest.code_set_key,
       value.code,
       INITCAP(REPLACE(LOWER(value.code), '_', ' ')),
       jsonb_build_object(
           'ko', INITCAP(REPLACE(LOWER(value.code), '_', ' ')),
           'en', INITCAP(REPLACE(LOWER(value.code), '_', ' '))),
       value.ordinality * 10,
       '{}'::jsonb,
       'ACTIVE'
  FROM manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
      WITH ORDINALITY AS value(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

WITH manifest AS (
    SELECT 'NOTIFICATION.' || UPPER(source_reference) AS code_set_key,
           source_reference
      FROM tmp_notification_database_check_contracts
)
INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT code_set_key, 'dwp-notification-server', 'DATABASE_COLUMN',
       source_reference, 'CHECK', 'ACTIVE'
  FROM manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = 'CHECK',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
