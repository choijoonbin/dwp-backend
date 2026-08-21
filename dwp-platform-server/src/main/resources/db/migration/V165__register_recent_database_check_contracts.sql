CREATE TEMP TABLE tmp_recent_database_check_contracts (
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(200) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    PRIMARY KEY (owner_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_recent_database_check_contracts (
    owner_service, source_reference, allowed_values)
VALUES
    ('dwp-approval-server', 'apr_form_categories.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('dwp-approval-server', 'apr_form_workflow_bindings.binding_type', ARRAY['CONDITIONAL', 'DEFAULT']::VARCHAR[]),
    ('dwp-approval-server', 'apr_form_workflow_bindings.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('dwp-approval-server', 'apr_forms.form_kind', ARRAY['DOCUMENT', 'REQUEST', 'SIGNATURE']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rule_versions.enforcement_mode', ARRAY['BLOCK', 'MONITOR', 'WARN']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rule_versions.lifecycle_state', ARRAY['ACTIVE', 'DISABLED', 'RETIRED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rule_versions.severity', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rules.pending_enforcement_mode', ARRAY['BLOCK', 'MONITOR', 'WARN']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rules.pending_lifecycle_state', ARRAY['ACTIVE', 'DISABLED', 'RETIRED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rules.pending_severity', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('dwp-approval-server', 'apr_request_payload_versions.change_type', ARRAY['BASELINE', 'DRAFT_CREATED', 'DRAFT_UPDATED', 'INFORMATION_RESPONDED']::VARCHAR[]),
    ('dwp-auth-server', 'ai_agent_citations.source_type', ARRAY['APPROVAL_FORM', 'APPROVAL_OPERATION', 'APPROVAL_REQUEST', 'APPROVAL_TASK', 'CALENDAR', 'MAIL', 'WORK_ITEM']::VARCHAR[]),
    ('dwp-auth-server', 'ai_agent_runs.answer_state', ARRAY['ABSTAINED', 'COMPLETED', 'CONFIGURATION_REQUIRED']::VARCHAR[]),
    ('dwp-auth-server', 'ai_agent_runs.policy_outcome', ARRAY['ALLOW', 'DENY', 'HANDOFF']::VARCHAR[]),
    ('dwp-auth-server', 'ai_agent_runs.risk_tier', ARRAY['L0', 'L1', 'L2', 'L3']::VARCHAR[]),
    ('dwp-auth-server', 'ai_agent_runs.run_state', ARRAY['COMPLETED', 'FAILED', 'RUNNING']::VARCHAR[]),
    ('dwp-auth-server', 'ai_answer_feedback.rating', ARRAY['DOWN', 'UP']::VARCHAR[]),
    ('dwp-auth-server', 'ai_conversation_messages.role', ARRAY['ASSISTANT', 'USER']::VARCHAR[]),
    ('dwp-auth-server', 'ai_model_calls.call_state', ARRAY['COMPLETED', 'CONFIGURATION_REQUIRED', 'REFUSED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_accounts.account_kind', ARRAY['PERSONAL', 'SHARED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_accounts.connection_state', ARRAY['ACTIVE', 'DISCONNECTED', 'REAUTHENTICATION_REQUIRED', 'SUSPENDED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_accounts.synchronization_state', ARRAY['DEGRADED', 'PAUSED', 'READY', 'SYNCING']::VARCHAR[]),
    ('dwp-platform-server', 'mail_action_proposals.proposal_status', ARRAY['ACCEPTED', 'DISMISSED', 'EXECUTED', 'EXPIRED', 'PROPOSED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_action_proposals.proposal_type', ARRAY['CREATE_CALENDAR_EVENT', 'CREATE_LEAVE_REQUEST', 'CREATE_TASK', 'DRAFT_REPLY', 'ESCALATE_NOTIFICATION']::VARCHAR[]),
    ('dwp-platform-server', 'mail_action_proposals.risk_level', ARRAY['HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('dwp-platform-server', 'mail_delivery_outbox.delivery_status', ARRAY['DELIVERED', 'FAILED', 'LEASED', 'QUEUED', 'RETRY_WAIT']::VARCHAR[]),
    ('dwp-platform-server', 'mail_folders.folder_type', ARRAY['ARCHIVE', 'CUSTOM', 'DRAFTS', 'INBOX', 'SENT', 'SPAM', 'TRASH']::VARCHAR[]),
    ('dwp-platform-server', 'mail_folders.lifecycle_state', ARRAY['ACTIVE', 'ARCHIVED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_messages.body_format', ARRAY['HTML', 'TEXT']::VARCHAR[]),
    ('dwp-platform-server', 'mail_messages.message_direction', ARRAY['DRAFT', 'INBOUND', 'OUTBOUND']::VARCHAR[]),
    ('dwp-platform-server', 'mail_provider_connections.authentication_mode', ARRAY['API_TOKEN', 'NONE', 'OAUTH2', 'PASSWORD', 'SERVICE_ACCOUNT']::VARCHAR[]),
    ('dwp-platform-server', 'mail_provider_connections.connection_state', ARRAY['ACTIVE', 'CONFIGURATION_REQUIRED', 'DEGRADED', 'SUSPENDED', 'SYNCING']::VARCHAR[]),
    ('dwp-platform-server', 'mail_provider_connections.provider_type', ARRAY['DWP_SANDBOX', 'GOOGLE_GMAIL', 'IMAP_SMTP', 'JMAP', 'MICROSOFT_GRAPH', 'NAVER_WORKS']::VARCHAR[]),
    ('dwp-platform-server', 'mail_shared_inbox_members.lifecycle_state', ARRAY['ACTIVE', 'RETIRED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_shared_inbox_members.member_role', ARRAY['MANAGER', 'MEMBER']::VARCHAR[]),
    ('dwp-platform-server', 'mail_shared_inboxes.lifecycle_state', ARRAY['ACTIVE', 'ARCHIVED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_threads.classification', ARRAY['CONFIDENTIAL', 'INTERNAL', 'PUBLIC', 'RESTRICTED']::VARCHAR[]),
    ('dwp-platform-server', 'mail_threads.importance', ARRAY['HIGH', 'LOW', 'NORMAL', 'URGENT']::VARCHAR[]),
    ('dwp-platform-server', 'mail_threads.triage_lane', ARRAY['ASSIGNED', 'NEEDS_REPLY', 'NEWSLETTERS', 'PRIORITY', 'UPDATES']::VARCHAR[]),
    ('dwp-platform-server', 'mail_threads.workflow_state', ARRAY['ARCHIVED', 'DONE', 'DRAFT', 'OPEN', 'SNOOZED']::VARCHAR[]),
    ('dwp-platform-server', 'sys_tenant_media_cleanup_outbox.cleanup_status', ARRAY['COMPLETED', 'DEAD', 'LEASED', 'PENDING', 'RETRY_WAIT']::VARCHAR[]),
    ('dwp-platform-server', 'wp_bookings.booking_status', ARRAY['CANCELLED', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW', 'RELEASED', 'RESERVED']::VARCHAR[]),
    ('dwp-platform-server', 'wp_campuses.lifecycle_state', ARRAY['ACTIVE', 'CLOSED', 'MAINTENANCE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_delegated_admin_scopes.delegate_type', ARRAY['GROUP_REF', 'USER']::VARCHAR[]),
    ('dwp-platform-server', 'wp_delegated_admin_scopes.lifecycle_state', ARRAY['ACTIVE', 'REVOKED']::VARCHAR[]),
    ('dwp-platform-server', 'wp_delegated_admin_scopes.permission_codes', ARRAY['ACCESS_MANAGE', 'CATALOG_MANAGE', 'CATALOG_VIEW', 'DELEGATION_VIEW', 'FLOOR_PLAN_MANAGE', 'POLICY_MANAGE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_delegated_admin_scopes.scope_type', ARRAY['GROUP_REF', 'SITE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_floor_plan_media_assets.asset_status', ARRAY['PENDING_DELETE', 'REFERENCED', 'STAGED']::VARCHAR[]),
    ('dwp-platform-server', 'wp_floor_plan_revisions.lifecycle_state', ARRAY['ARCHIVED', 'DRAFT', 'PUBLISHED', 'REVIEW']::VARCHAR[]),
    ('dwp-platform-server', 'wp_floors.background_content_type', ARRAY['image/jpeg', 'image/png']::VARCHAR[]),
    ('dwp-platform-server', 'wp_floors.lifecycle_state', ARRAY['ACTIVE', 'CLOSED', 'DRAFT']::VARCHAR[]),
    ('dwp-platform-server', 'wp_policy_overrides.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_policy_overrides.scope_type', ARRAY['CAMPUS', 'FLOOR', 'RESOURCE', 'SITE', 'TENANT', 'ZONE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_resource_release_windows.release_status', ARRAY['ACTIVE', 'CANCELLED']::VARCHAR[]),
    ('dwp-platform-server', 'wp_resources.booking_mode', ARRAY['ASSIGNED', 'DROP_IN', 'RESERVABLE', 'UNAVAILABLE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_resources.lifecycle_state', ARRAY['AVAILABLE', 'MAINTENANCE', 'RETIRED']::VARCHAR[]),
    ('dwp-platform-server', 'wp_resources.resource_type', ARRAY['DESK', 'EQUIPMENT', 'FOCUS_POD', 'LOCKER', 'PARKING', 'PHONE_BOOTH', 'ROOM']::VARCHAR[]),
    ('dwp-platform-server', 'wp_sections.lifecycle_state', ARRAY['ACTIVE', 'CLOSED', 'MAINTENANCE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_site_access_rules.effect', ARRAY['ALLOW', 'DENY']::VARCHAR[]),
    ('dwp-platform-server', 'wp_site_access_rules.lifecycle_state', ARRAY['ACTIVE', 'INACTIVE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_site_access_rules.permission_code', ARRAY['BOOK', 'MANAGE', 'VIEW']::VARCHAR[]),
    ('dwp-platform-server', 'wp_site_access_rules.subject_type', ARRAY['GROUP_REF', 'USER']::VARCHAR[]),
    ('dwp-platform-server', 'wp_sites.lifecycle_state', ARRAY['ACTIVE', 'CLOSED', 'MAINTENANCE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_sites.site_type', ARRAY['CLIENT_SITE', 'HEADQUARTERS', 'SATELLITE', 'SHARED_OFFICE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_zones.lifecycle_state', ARRAY['ACTIVE', 'CLOSED', 'MAINTENANCE']::VARCHAR[]),
    ('dwp-platform-server', 'wp_zones.zone_type', ARRAY['COLLABORATION', 'GENERAL', 'QUIET', 'RESTRICTED', 'SERVICE', 'WORK_AREA']::VARCHAR[]);

WITH manifest AS (
    SELECT CASE owner_service
               WHEN 'dwp-approval-server' THEN 'APPROVAL'
               WHEN 'dwp-auth-server' THEN 'AUTH'
               WHEN 'dwp-platform-server' THEN 'PLATFORM'
           END || '.' || UPPER(source_reference) AS code_set_key,
           owner_service,
           source_reference,
           allowed_values
      FROM tmp_recent_database_check_contracts
)
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
SELECT code_set_key,
       owner_service,
       INITCAP(REPLACE(REPLACE(source_reference, '.', ' '), '_', ' ')),
       'Database CHECK contract for ' || source_reference || '.',
       'SYSTEM', 'CHECK', source_reference,
       CASE
           WHEN source_reference ~ '(status|state)$' THEN 'STATE_MACHINE'
           WHEN source_reference ~ '(risk|severity|classification|importance|permission|effect|authentication_mode|enforcement_mode)'
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
    SELECT CASE owner_service
               WHEN 'dwp-approval-server' THEN 'APPROVAL'
               WHEN 'dwp-auth-server' THEN 'AUTH'
               WHEN 'dwp-platform-server' THEN 'PLATFORM'
           END || '.' || UPPER(source_reference) AS code_set_key,
           allowed_values
      FROM tmp_recent_database_check_contracts
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
    SELECT CASE owner_service
               WHEN 'dwp-approval-server' THEN 'APPROVAL'
               WHEN 'dwp-auth-server' THEN 'AUTH'
               WHEN 'dwp-platform-server' THEN 'PLATFORM'
           END || '.' || UPPER(source_reference) AS code_set_key,
           owner_service,
           source_reference
      FROM tmp_recent_database_check_contracts
)
INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT code_set_key, owner_service, 'DATABASE_COLUMN',
       source_reference, 'CHECK', 'ACTIVE'
  FROM manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference
) DO UPDATE SET
    enforcement_type = 'CHECK',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
