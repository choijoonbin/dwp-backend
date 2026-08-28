-- Reconcile the administrator-only code registry with the CHECK constraints
-- present after every service applies its latest forward migration. Service
-- schemas remain authoritative; this migration changes registry projections only.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v209_check_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    UNIQUE (owner_service, source_reference),
    CONSTRAINT ck_tmp_v209_contract_kind
        CHECK (contract_kind IN (
            'REFERENCE', 'STATE_MACHINE', 'SECURITY',
            'PROTOCOL', 'OBSERVABILITY')),
    CONSTRAINT ck_tmp_v209_allowed_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v209_check_contract_manifest (
    code_set_key, owner_service, source_reference,
    contract_kind, allowed_values)
VALUES
    ('AUTH.AUTH_PRODUCT_PREDICATE_POLICY.OWNER_SERVICE_KEY',
     'dwp-auth-server', 'auth_product_predicate_policy.owner_service_key',
     'SECURITY',
     ARRAY['agent', 'approval', 'auth', 'meeting', 'messaging',
           'notification', 'people', 'platform', 'space']::VARCHAR[]),

    ('MEETING.VM_MEETING_CONTENT_ACL.CONTENT_TYPE',
     'dwp-meeting-server', 'vm_meeting_content_acl.content_type',
     'REFERENCE', ARRAY['INTELLIGENCE_REPORT']::VARCHAR[]),
    ('MEETING.VM_MEETING_CONTENT_ACL.PERMISSION',
     'dwp-meeting-server', 'vm_meeting_content_acl.permission',
     'SECURITY', ARRAY['MANAGE', 'REVIEW', 'VIEW']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_DELETIONS.DELETION_REASON',
     'dwp-meeting-server',
     'vm_meeting_intelligence_deletions.deletion_reason',
     'PROTOCOL', ARRAY['RETENTION_EXPIRED']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_REPORTS.AUDIENCE',
     'dwp-meeting-server', 'vm_meeting_intelligence_reports.audience',
     'SECURITY',
     ARRAY['MEETING_PARTICIPANTS', 'PRIVATE_REVIEWERS']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_REPORTS.REPORT_STATE',
     'dwp-meeting-server', 'vm_meeting_intelligence_reports.report_state',
     'STATE_MACHINE',
     ARRAY['APPROVED', 'DELETED', 'DRAFT', 'PUBLISHED',
           'REJECTED']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_RETENTION_HEALTH.HEALTH_KEY',
     'dwp-meeting-server',
     'vm_meeting_intelligence_retention_health.health_key',
     'OBSERVABILITY', ARRAY['REPORT_RETENTION']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_REVIEWS.DECISION',
     'dwp-meeting-server', 'vm_meeting_intelligence_reviews.decision',
     'PROTOCOL', ARRAY['APPROVE', 'REJECT']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_RUNS.ANALYSIS_PROFILE',
     'dwp-meeting-server', 'vm_meeting_intelligence_runs.analysis_profile',
     'PROTOCOL', ARRAY['STANDARD_RECAP_V1']::VARCHAR[]),
    ('MEETING.VM_MEETING_INTELLIGENCE_RUNS.RUN_STATE',
     'dwp-meeting-server', 'vm_meeting_intelligence_runs.run_state',
     'STATE_MACHINE', ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_STATE',
     'dwp-meeting-server', 'vm_meeting_media_operations.operation_state',
     'STATE_MACHINE', ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_TYPE',
     'dwp-meeting-server', 'vm_meeting_media_operations.operation_type',
     'PROTOCOL', ARRAY['END', 'START']::VARCHAR[]),

    ('NOTIFICATION.NTF_NOTIFICATION_INTENTS.DECISION',
     'dwp-notification-server', 'ntf_notification_intents.decision',
     'STATE_MACHINE',
     ARRAY['DUPLICATE', 'MATERIALIZED', 'QUARANTINED',
           'SUPPRESSED']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATION_STEP_ATTEMPTS.LIFECYCLE_STATE',
     'dwp-provider-server', 'prv_operation_step_attempts.lifecycle_state',
     'STATE_MACHINE',
     ARRAY['ABANDONED', 'FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]);

CREATE TEMP TABLE tmp_v209_retired_check_contracts (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    UNIQUE (owner_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_v209_retired_check_contracts VALUES
    ('AUTH.AI_AGENT_CITATIONS.SOURCE_TYPE',
     'dwp-auth-server', 'ai_agent_citations.source_type'),
    ('AUTH.AI_AGENT_RUNS.ANSWER_STATE',
     'dwp-auth-server', 'ai_agent_runs.answer_state'),
    ('AUTH.AI_AGENT_RUNS.POLICY_OUTCOME',
     'dwp-auth-server', 'ai_agent_runs.policy_outcome'),
    ('AUTH.AI_AGENT_RUNS.RISK_TIER',
     'dwp-auth-server', 'ai_agent_runs.risk_tier'),
    ('AUTH.AI_AGENT_RUNS.RUN_STATE',
     'dwp-auth-server', 'ai_agent_runs.run_state'),
    ('AUTH.AI_ANSWER_FEEDBACK.RATING',
     'dwp-auth-server', 'ai_answer_feedback.rating'),
    ('AUTH.AI_CONVERSATION_MESSAGES.ROLE',
     'dwp-auth-server', 'ai_conversation_messages.role'),
    ('AUTH.AI_MODEL_CALLS.CALL_STATE',
     'dwp-auth-server', 'ai_model_calls.call_state');

DO $v209_registry_guard$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v209_check_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.owner_service
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V209 found a conflicting active CHECK binding for an active source';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v209_check_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> manifest.owner_service
            OR code_set.source_reference <> manifest.source_reference
    ) THEN
        RAISE EXCEPTION
            'V209 active code-set key is owned by a different source';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v209_retired_check_contracts retired
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = retired.code_set_key
         WHERE code_set.owner_service <> retired.owner_service
            OR code_set.source_reference <> retired.source_reference
    ) THEN
        RAISE EXCEPTION
            'V209 retired code-set key is owned by a different source';
    END IF;
END;
$v209_registry_guard$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       manifest.source_reference,
       'Database CHECK contract for ' || manifest.source_reference || '.',
       'SYSTEM', 'CHECK', manifest.source_reference, manifest.contract_kind,
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v209_check_contract_manifest manifest
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    configuration_level = 'SYSTEM',
    validation_source = 'CHECK',
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = 'ADMIN_ONLY',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_sets.owner_service,
          sys_code_sets.configuration_level,
          sys_code_sets.validation_source,
          sys_code_sets.source_reference,
          sys_code_sets.contract_kind,
          sys_code_sets.runtime_visibility,
          sys_code_sets.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.owner_service,
          'SYSTEM',
          'CHECK',
          EXCLUDED.source_reference,
          EXCLUDED.contract_kind,
          'ADMIN_ONLY',
          'ACTIVE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT manifest.code_set_key,
       value_ref.code,
       value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb,
       value_ref.ordinality::INTEGER * 10,
       TRUE,
       'ACTIVE'
  FROM tmp_v209_check_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v209_check_contract_manifest manifest
 WHERE code_value.code_set_key = manifest.code_set_key
   AND NOT (code_value.code = ANY (manifest.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       'DATABASE_COLUMN',
       manifest.source_reference,
       'CHECK',
       'ACTIVE'
  FROM tmp_v209_check_contract_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = 'CHECK',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_bindings.enforcement_type,
          sys_code_bindings.lifecycle_state)
      IS DISTINCT FROM ROW('CHECK', 'ACTIVE');

UPDATE sys_code_bindings binding
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v209_retired_check_contracts retired
 WHERE binding.code_set_key = retired.code_set_key
   AND binding.lifecycle_state <> 'RETIRED';

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v209_retired_check_contracts retired
 WHERE code_value.code_set_key = retired.code_set_key
   AND code_value.lifecycle_state <> 'RETIRED';

UPDATE sys_code_sets code_set
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v209_retired_check_contracts retired
 WHERE code_set.code_set_key = retired.code_set_key
   AND code_set.lifecycle_state <> 'RETIRED';
