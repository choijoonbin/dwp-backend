CREATE TEMP TABLE tmp_runtime_product_code_contracts (
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(200) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    PRIMARY KEY (owner_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_runtime_product_code_contracts (
    owner_service, source_reference, allowed_values)
VALUES
    ('dwp-approval-server', 'apr_delegations.lifecycle_state', ARRAY['ACTIVE', 'EXPIRED', 'REVOKED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_delegations.scope_type', ARRAY['ALL', 'WORKFLOW']::VARCHAR[]),
    ('dwp-approval-server', 'apr_form_versions.lifecycle_state', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_forms.lifecycle_state', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_integration_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rules.enforcement_mode', ARRAY['BLOCK', 'MONITOR', 'WARN']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rules.lifecycle_state', ARRAY['ACTIVE', 'DISABLED', 'RETIRED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rules.policy_type', ARRAY['DATA', 'DECISION', 'IDENTITY', 'SEGREGATION_OF_DUTIES', 'SLA']::VARCHAR[]),
    ('dwp-approval-server', 'apr_policy_rules.severity', ARRAY['CRITICAL', 'HIGH', 'LOW', 'MEDIUM']::VARCHAR[]),
    ('dwp-approval-server', 'apr_request_events.actor_type', ARRAY['AGENT', 'SERVICE', 'SYSTEM', 'USER']::VARCHAR[]),
    ('dwp-approval-server', 'apr_request_events.outcome', ARRAY['DENIED', 'FAILED', 'SUCCESS']::VARCHAR[]),
    ('dwp-approval-server', 'apr_requests.data_classification', ARRAY['CONFIDENTIAL', 'INTERNAL', 'RESTRICTED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_requests.priority', ARRAY['HIGH', 'LOW', 'NORMAL', 'URGENT']::VARCHAR[]),
    ('dwp-approval-server', 'apr_requests.status', ARRAY['APPROVED', 'CANCELLED', 'DRAFT', 'IN_REVIEW', 'NEEDS_INFO', 'REJECTED', 'SUBMITTED', 'WITHDRAWN']::VARCHAR[]),
    ('dwp-approval-server', 'apr_signature_providers.lifecycle_state', ARRAY['ACTIVE', 'CONFIGURATION_REQUIRED', 'DEGRADED', 'DISABLED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_signature_providers.provider_type', ARRAY['ADOBE_SIGN', 'CUSTOM', 'DOCUSIGN', 'INTERNAL_ATTESTATION']::VARCHAR[]),
    ('dwp-approval-server', 'apr_steps.approval_mode', ARRAY['ALL', 'ANY', 'SEQUENTIAL']::VARCHAR[]),
    ('dwp-approval-server', 'apr_steps.status', ARRAY['APPROVED', 'CANCELLED', 'IN_PROGRESS', 'PENDING', 'REJECTED', 'SKIPPED', 'WAITING']::VARCHAR[]),
    ('dwp-approval-server', 'apr_tasks.status', ARRAY['APPROVED', 'CANCELLED', 'CLAIMED', 'INFO_REQUESTED', 'PENDING', 'REASSIGNED', 'REJECTED', 'SKIPPED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_tenants.lifecycle_state', ARRAY['ACTIVE', 'RETIRED', 'SUSPENDED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_workflow_definitions.category', ARRAY['ACCESS', 'FINANCE', 'GENERAL', 'PEOPLE', 'PROCUREMENT']::VARCHAR[]),
    ('dwp-approval-server', 'apr_workflow_definitions.data_classification', ARRAY['CONFIDENTIAL', 'INTERNAL', 'RESTRICTED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_workflow_definitions.lifecycle_state', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[]),
    ('dwp-approval-server', 'apr_workflow_versions.lifecycle_state', ARRAY['DRAFT', 'PUBLISHED', 'RETIRED']::VARCHAR[]),
    ('dwp-approval-server', 'sys_audit_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('dwp-approval-server', 'sys_domain_event_inbox.status', ARRAY['DEAD', 'DEFERRED', 'DUPLICATE', 'FAILED', 'PROCESSING', 'RECEIVED', 'REPLAY_PENDING', 'SUCCEEDED']::VARCHAR[]),
    ('dwp-approval-server', 'sys_domain_event_outbox.status', ARRAY['DEAD', 'FAILED', 'PENDING', 'PUBLISHED', 'SENDING']::VARCHAR[]),
    ('dwp-approval-server', 'sys_domain_event_replay_audit.direction', ARRAY['INBOX', 'OUTBOX']::VARCHAR[]),
    ('dwp-people-server', 'abs_leave_balances.data_origin', ARRAY['MANUAL', 'REFERENCE', 'SOURCE']::VARCHAR[]),
    ('dwp-people-server', 'abs_leave_plans.accrual_method', ARRAY['ANNUAL', 'EVENT', 'MONTHLY', 'NONE']::VARCHAR[]),
    ('dwp-people-server', 'abs_leave_plans.approval_policy', ARRAY['AUTO', 'HR', 'MANAGER']::VARCHAR[]),
    ('dwp-people-server', 'abs_leave_plans.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('dwp-people-server', 'abs_leave_plans.unit', ARRAY['DAY', 'MINUTE']::VARCHAR[]),
    ('dwp-people-server', 'abs_leave_requests.status', ARRAY['APPROVED', 'CANCELLED', 'DRAFT', 'REJECTED', 'SUBMITTED']::VARCHAR[]),
    ('dwp-people-server', 'abs_worker_plan_enrollments.lifecycle_state', ARRAY['ACTIVE', 'ENDED']::VARCHAR[]),
    ('dwp-people-server', 'bnf_benefit_plans.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('dwp-people-server', 'bnf_benefit_plans.plan_type', ARRAY['ALLOWANCE', 'HEALTH', 'LIFE', 'OTHER', 'RETIREMENT', 'WELLNESS']::VARCHAR[]),
    ('dwp-people-server', 'bnf_benefit_programs.lifecycle_state', ARRAY['ACTIVE', 'CLOSED', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('dwp-people-server', 'bnf_enrollment_windows.lifecycle_state', ARRAY['CANCELLED', 'CLOSED', 'OPEN', 'SCHEDULED']::VARCHAR[]),
    ('dwp-people-server', 'bnf_enrollment_windows.window_type', ARRAY['CORRECTION', 'LIFE_EVENT', 'NEW_HIRE', 'OPEN_ENROLLMENT']::VARCHAR[]),
    ('dwp-people-server', 'bnf_enrollments.coverage_level', ARRAY['EMPLOYEE', 'EMPLOYEE_CHILDREN', 'EMPLOYEE_SPOUSE', 'FAMILY', 'WAIVED']::VARCHAR[]),
    ('dwp-people-server', 'bnf_enrollments.status', ARRAY['ACTIVE', 'DRAFT', 'ELECTED', 'ENDED', 'WAIVED']::VARCHAR[]),
    ('dwp-people-server', 'pay_pay_cycles.status', ARRAY['APPROVED', 'CANCELLED', 'COLLECTING', 'PAID', 'PLANNED', 'VALIDATING']::VARCHAR[]),
    ('dwp-people-server', 'pay_statement_references.availability_state', ARRAY['AVAILABLE', 'PENDING', 'RETIRED', 'WITHHELD']::VARCHAR[]),
    ('dwp-people-server', 'tal_goals.goal_type', ARRAY['DEVELOPMENT', 'ORGANIZATION', 'PERFORMANCE', 'TEAM']::VARCHAR[]),
    ('dwp-people-server', 'tal_goals.status', ARRAY['ACTIVE', 'AT_RISK', 'CANCELLED', 'COMPLETED', 'DRAFT']::VARCHAR[]),
    ('dwp-people-server', 'tal_goals.visibility', ARRAY['MANAGER', 'PRIVATE', 'TEAM', 'TENANT']::VARCHAR[]),
    ('dwp-people-server', 'tal_journey_instances.status', ARRAY['ACTIVE', 'CANCELLED', 'COMPLETED', 'PAUSED']::VARCHAR[]),
    ('dwp-people-server', 'tal_journey_templates.journey_type', ARRAY['GROWTH', 'MOBILITY', 'OFFBOARDING', 'ONBOARDING', 'RETURN']::VARCHAR[]),
    ('dwp-people-server', 'tal_journey_templates.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('dwp-people-server', 'tal_learning_assignments.status', ARRAY['ASSIGNED', 'COMPLETED', 'EXPIRED', 'IN_PROGRESS', 'WAIVED']::VARCHAR[]),
    ('dwp-people-server', 'tme_time_cards.data_origin', ARRAY['MANUAL', 'REFERENCE', 'SOURCE']::VARCHAR[]),
    ('dwp-people-server', 'tme_time_cards.status', ARRAY['APPROVED', 'LOCKED', 'OPEN', 'REJECTED', 'SUBMITTED']::VARCHAR[]),
    ('dwp-people-server', 'tme_time_entries.entry_type', ARRAY['BREAK', 'CORRECTION', 'ON_CALL', 'TRAINING', 'WORK']::VARCHAR[]),
    ('dwp-people-server', 'tme_time_entries.lifecycle_state', ARRAY['ACTIVE', 'VOID']::VARCHAR[]),
    ('dwp-people-server', 'tme_time_entries.work_mode', ARRAY['FIELD', 'HYBRID', 'OFFICE', 'REMOTE']::VARCHAR[]),
    ('dwp-people-server', 'tme_time_exceptions.lifecycle_state', ARRAY['OPEN', 'RESOLVED', 'WAIVED']::VARCHAR[]),
    ('dwp-people-server', 'tme_time_exceptions.severity', ARRAY['BLOCKING', 'INFO', 'WARNING']::VARCHAR[]),
    ('dwp-people-server', 'tme_work_schedule_profiles.lifecycle_state', ARRAY['ACTIVE', 'DRAFT', 'RETIRED']::VARCHAR[]),
    ('dwp-people-server', 'tme_worker_schedule_assignments.data_origin', ARRAY['MANUAL', 'REFERENCE', 'SOURCE']::VARCHAR[]),
    ('dwp-platform-server', 'cal_calendars.calendar_type', ARRAY['PERSONAL', 'RESOURCE', 'SYSTEM', 'TEAM']::VARCHAR[]),
    ('dwp-platform-server', 'cal_calendars.lifecycle_state', ARRAY['ACTIVE', 'ARCHIVED']::VARCHAR[]),
    ('dwp-platform-server', 'cal_calendars.visibility', ARRAY['DETAILS', 'FREE_BUSY', 'PRIVATE']::VARCHAR[]),
    ('dwp-platform-server', 'cal_event_attendees.attendee_type', ARRAY['OPTIONAL', 'REQUIRED', 'RESOURCE']::VARCHAR[]),
    ('dwp-platform-server', 'cal_event_attendees.response_status', ARRAY['ACCEPTED', 'DECLINED', 'NEEDS_ACTION', 'TENTATIVE']::VARCHAR[]),
    ('dwp-platform-server', 'cal_events.event_type', ARRAY['FOCUS', 'MEETING', 'OUT_OF_OFFICE', 'REMINDER', 'TASK']::VARCHAR[]),
    ('dwp-platform-server', 'cal_events.recurrence_pattern', ARRAY['DAILY', 'MONTHLY', 'NONE', 'WEEKLY']::VARCHAR[]),
    ('dwp-platform-server', 'cal_events.source_type', ARRAY['APPROVAL', 'GOOGLE', 'HRIS', 'MICROSOFT', 'NATIVE']::VARCHAR[]),
    ('dwp-platform-server', 'cal_events.status', ARRAY['CANCELLED', 'CONFIRMED', 'TENTATIVE']::VARCHAR[]),
    ('dwp-platform-server', 'cal_events.visibility', ARRAY['CONFIDENTIAL', 'DEFAULT', 'PRIVATE', 'PUBLIC']::VARCHAR[]),
    ('dwp-platform-server', 'cal_resource_bookings.booking_status', ARRAY['CANCELLED', 'CONFIRMED', 'DECLINED', 'PENDING']::VARCHAR[]),
    ('dwp-platform-server', 'cal_resources.lifecycle_state', ARRAY['AVAILABLE', 'MAINTENANCE', 'RETIRED']::VARCHAR[]),
    ('dwp-platform-server', 'cal_resources.resource_type', ARRAY['DESK', 'EQUIPMENT', 'ROOM']::VARCHAR[]),
    ('dwp-platform-server', 'usr_home_recommendation_feedback.feedback_type', ARRAY['DISMISSED', 'HELPFUL', 'NOT_RELEVANT']::VARCHAR[]);

WITH manifest AS (
    SELECT CASE owner_service
               WHEN 'dwp-approval-server' THEN 'APPROVAL'
               WHEN 'dwp-people-server' THEN 'PEOPLE'
               WHEN 'dwp-platform-server' THEN 'PLATFORM'
           END || '.' || UPPER(REPLACE(source_reference, '.', '.')) AS code_set_key,
           owner_service,
           source_reference,
           allowed_values
      FROM tmp_runtime_product_code_contracts
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
           WHEN source_reference ~ '(status|lifecycle_state|availability_state)$'
               THEN 'STATE_MACHINE'
           WHEN source_reference ~ '(actor_type|outcome|direction)$'
               THEN 'OBSERVABILITY'
           WHEN source_reference ~ '(data_classification|visibility|severity|auth_mode|approval_policy|enforcement_mode)$'
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
               WHEN 'dwp-people-server' THEN 'PEOPLE'
               WHEN 'dwp-platform-server' THEN 'PLATFORM'
           END || '.' || UPPER(source_reference) AS code_set_key,
           allowed_values
      FROM tmp_runtime_product_code_contracts
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
               WHEN 'dwp-people-server' THEN 'PEOPLE'
               WHEN 'dwp-platform-server' THEN 'PLATFORM'
           END || '.' || UPPER(source_reference) AS code_set_key,
           owner_service,
           source_reference
      FROM tmp_runtime_product_code_contracts
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
    enforcement_type = EXCLUDED.enforcement_type,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
