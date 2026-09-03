-- Bind the Meeting transcript deletion Java enum introduced with V25 to the
-- canonical CHECK-backed code set projected by Platform V218. V218 remains
-- the database CHECK registration boundary; this migration adds typed binding.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v219_typed_binding_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    source_reference VARCHAR(300) NOT NULL UNIQUE,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    PRIMARY KEY (code_set_key, source_reference),
    CONSTRAINT ck_tmp_v219_contract_kind
        CHECK (contract_kind = 'STATE_MACHINE'),
    CONSTRAINT ck_tmp_v219_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v219_typed_binding_manifest VALUES
    ('MEETING.VM_MEETING_TRANSCRIPT_DELETION_COMMANDS.COMMAND_STATE',
     'MeetingTranscriptDeletionModels.CommandState',
     'STATE_MACHINE',
     ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]);

DO $v219_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v219_typed_binding_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
            OR ROW(code_set.owner_service, code_set.validation_source,
                   code_set.contract_kind, code_set.configuration_level,
                   code_set.runtime_visibility, code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW('dwp-meeting-server', 'CHECK', manifest.contract_kind,
                   'SYSTEM', 'ADMIN_ONLY', 'ACTIVE')
            OR (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = manifest.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM manifest.allowed_values
            OR (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = 'dwp-meeting-server'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.enforcement_type = 'CHECK'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) THEN
        RAISE EXCEPTION
            'V219 canonical Meeting transcript deletion CHECK contract drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v219_typed_binding_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = 'dwp-meeting-server'
           AND binding.usage_type = 'API_CONTRACT'
           AND binding.source_reference = manifest.source_reference
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V219 found a conflicting Meeting transcript deletion typed binding';
    END IF;
END;
$v219_preflight$;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       'dwp-meeting-server',
       'API_CONTRACT',
       manifest.source_reference,
       'TYPED_CONTRACT',
       'ACTIVE'
  FROM tmp_v219_typed_binding_manifest manifest
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

DO $v219_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v219_typed_binding_manifest manifest
         WHERE (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = 'dwp-meeting-server'
                   AND binding.usage_type = 'API_CONTRACT'
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'TYPED_CONTRACT'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
            OR EXISTS (
                SELECT 1
                  FROM sys_code_bindings binding
                 WHERE binding.consumer_service = 'dwp-meeting-server'
                   AND binding.usage_type = 'API_CONTRACT'
                   AND binding.source_reference = manifest.source_reference
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND binding.code_set_key <> manifest.code_set_key)
    ) THEN
        RAISE EXCEPTION
            'V219 Meeting transcript deletion typed binding convergence failed';
    END IF;
END;
$v219_postcondition$;
