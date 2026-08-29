-- Fence durable Mail draft receipts to their closed command/state vocabulary
-- and bind the corresponding Mail and Meeting Java enums to canonical code
-- sets. Existing service CHECK contracts remain the value source of truth.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

DO $v214_mail_receipt_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM mail_draft_command_receipts
         WHERE command_type NOT IN ('CREATE', 'SAVE')
            OR command_status NOT IN ('COMPLETED', 'IN_PROGRESS')
    ) THEN
        RAISE EXCEPTION
            'V214 found an unsupported durable Mail draft receipt value';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint constraint_ref
         WHERE constraint_ref.conrelid =
                   'mail_draft_command_receipts'::regclass
           AND constraint_ref.conname =
                   'ck_mail_draft_receipt_command_type'
    ) THEN
        ALTER TABLE mail_draft_command_receipts
            ADD CONSTRAINT ck_mail_draft_receipt_command_type
            CHECK (command_type IN ('CREATE', 'SAVE'));
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint constraint_ref
         WHERE constraint_ref.conrelid =
                   'mail_draft_command_receipts'::regclass
           AND constraint_ref.conname =
                   'ck_mail_draft_receipt_command_status'
    ) THEN
        ALTER TABLE mail_draft_command_receipts
            ADD CONSTRAINT ck_mail_draft_receipt_command_status
            CHECK (command_status IN ('COMPLETED', 'IN_PROGRESS'));
    END IF;
END;
$v214_mail_receipt_preflight$;

CREATE TEMP TABLE tmp_v214_check_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    source_reference VARCHAR(240) NOT NULL UNIQUE,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    CONSTRAINT ck_tmp_v214_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v214_check_contract_manifest VALUES
    ('PLATFORM.MAIL_DRAFT_COMMAND_RECEIPTS.COMMAND_TYPE',
     'mail_draft_command_receipts.command_type', 'PROTOCOL',
     ARRAY['CREATE', 'SAVE']::VARCHAR[]),
    ('PLATFORM.MAIL_DRAFT_COMMAND_RECEIPTS.COMMAND_STATUS',
     'mail_draft_command_receipts.command_status', 'STATE_MACHINE',
     ARRAY['COMPLETED', 'IN_PROGRESS']::VARCHAR[]);

CREATE TEMP TABLE tmp_v214_typed_binding_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    consumer_service VARCHAR(80) NOT NULL,
    usage_type VARCHAR(30) NOT NULL,
    source_reference VARCHAR(300) NOT NULL,
    PRIMARY KEY (
        code_set_key, consumer_service, usage_type, source_reference),
    UNIQUE (consumer_service, source_reference)
) ON COMMIT DROP;

INSERT INTO tmp_v214_typed_binding_manifest VALUES
    ('PLATFORM.MAIL_DRAFT_COMMAND_RECEIPTS.COMMAND_TYPE',
     'dwp-platform-server', 'BEHAVIOR',
     'MailDraftCommandReceiptRepository.CommandType'),
    ('MEETING.VM_MEETING_PROVIDER_EVENTS.EVENT_TYPE',
     'dwp-meeting-server', 'API_CONTRACT',
     'MeetingMediaWebhook.EventType');

DO $v214_registry_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v214_check_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = 'dwp-platform-server'
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v214_typed_binding_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.consumer_service
           AND binding.source_reference = manifest.source_reference
           AND binding.lifecycle_state = 'ACTIVE'
         WHERE ROW(binding.code_set_key, binding.usage_type,
                   binding.enforcement_type)
               IS DISTINCT FROM
               ROW(manifest.code_set_key, manifest.usage_type,
                   'TYPED_CONTRACT')
    ) THEN
        RAISE EXCEPTION 'V214 found a conflicting active code binding';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v214_check_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> 'dwp-platform-server'
            OR code_set.source_reference <> manifest.source_reference
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v214_typed_binding_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
           AND NOT EXISTS (
               SELECT 1
                 FROM tmp_v214_check_contract_manifest check_manifest
                WHERE check_manifest.code_set_key = manifest.code_set_key)
    ) THEN
        RAISE EXCEPTION 'V214 canonical code-set ownership drifted';
    END IF;
END;
$v214_registry_preflight$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key,
       'dwp-platform-server',
       manifest.source_reference,
       'Database CHECK contract for ' || manifest.source_reference || '.',
       'SYSTEM', 'CHECK', manifest.source_reference, manifest.contract_kind,
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v214_check_contract_manifest manifest
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
          EXCLUDED.owner_service, 'SYSTEM', 'CHECK',
          EXCLUDED.source_reference, EXCLUDED.contract_kind,
          'ADMIN_ONLY', 'ACTIVE');

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
  FROM tmp_v214_check_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v214_check_contract_manifest manifest
 WHERE code_value.code_set_key = manifest.code_set_key
   AND NOT (code_value.code = ANY (manifest.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       'dwp-platform-server',
       'DATABASE_COLUMN',
       manifest.source_reference,
       'CHECK',
       'ACTIVE'
  FROM tmp_v214_check_contract_manifest manifest
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

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.consumer_service,
       manifest.usage_type,
       manifest.source_reference,
       'TYPED_CONTRACT',
       'ACTIVE'
  FROM tmp_v214_typed_binding_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = 'TYPED_CONTRACT',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_bindings.enforcement_type,
          sys_code_bindings.lifecycle_state)
      IS DISTINCT FROM ROW('TYPED_CONTRACT', 'ACTIVE');

DO $v214_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v214_check_contract_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
            OR ROW(code_set.owner_service, code_set.validation_source,
                   code_set.source_reference, code_set.contract_kind,
                   code_set.configuration_level, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW('dwp-platform-server', 'CHECK',
                   manifest.source_reference, manifest.contract_kind,
                   'SYSTEM', 'ADMIN_ONLY', 'ACTIVE')
            OR (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = manifest.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM manifest.allowed_values
            OR (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = 'dwp-platform-server'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'CHECK'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v214_typed_binding_manifest manifest
         WHERE (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = manifest.consumer_service
                   AND binding.usage_type = manifest.usage_type
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'TYPED_CONTRACT'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint constraint_ref
         WHERE constraint_ref.conrelid =
                   'mail_draft_command_receipts'::regclass
           AND constraint_ref.conname =
                   'ck_mail_draft_receipt_command_type'
           AND constraint_ref.convalidated
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_constraint constraint_ref
         WHERE constraint_ref.conrelid =
                   'mail_draft_command_receipts'::regclass
           AND constraint_ref.conname =
                   'ck_mail_draft_receipt_command_status'
           AND constraint_ref.convalidated
    ) THEN
        RAISE EXCEPTION 'V214 code-contract convergence failed';
    END IF;
END;
$v214_postcondition$;
