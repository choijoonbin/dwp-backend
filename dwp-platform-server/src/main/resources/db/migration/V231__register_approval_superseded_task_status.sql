SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

DO $v231_preflight$
DECLARE
    previous_values CONSTANT VARCHAR[] := ARRAY[
        'APPROVED', 'CANCELLED', 'CLAIMED', 'INFO_REQUESTED',
        'PENDING', 'REASSIGNED', 'REJECTED', 'SKIPPED'
    ]::VARCHAR[];
    target_values CONSTANT VARCHAR[] := ARRAY[
        'APPROVED', 'CANCELLED', 'CLAIMED', 'INFO_REQUESTED',
        'PENDING', 'REASSIGNED', 'REJECTED', 'SKIPPED', 'SUPERSEDED'
    ]::VARCHAR[];
    actual_values VARCHAR[];
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM sys_code_sets code_set
         WHERE code_set.code_set_key = 'APPROVAL.APR_TASKS.STATUS'
           AND ROW(code_set.owner_service, code_set.source_reference,
                   code_set.validation_source, code_set.contract_kind,
                   code_set.configuration_level, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               = ROW('dwp-approval-server', 'apr_tasks.status',
                     'CHECK', 'STATE_MACHINE', 'SYSTEM', 'ADMIN_ONLY', 'ACTIVE'))
    OR NOT EXISTS (
        SELECT 1
          FROM sys_code_bindings binding
         WHERE binding.code_set_key = 'APPROVAL.APR_TASKS.STATUS'
           AND binding.consumer_service = 'dwp-approval-server'
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = 'apr_tasks.status'
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE') THEN
        RAISE EXCEPTION 'V231 approval task status contract metadata drifted';
    END IF;

    SELECT array_agg(code_value.code ORDER BY code_value.code)
      INTO actual_values
      FROM sys_code_values code_value
     WHERE code_value.code_set_key = 'APPROVAL.APR_TASKS.STATUS'
       AND code_value.lifecycle_state = 'ACTIVE';
    IF actual_values IS DISTINCT FROM previous_values
       AND actual_values IS DISTINCT FROM target_values THEN
        RAISE EXCEPTION 'V231 approval task status values drifted';
    END IF;
END;
$v231_preflight$;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
VALUES (
    'APPROVAL.APR_TASKS.STATUS', 'SUPERSEDED', 'Superseded',
    '{"ko":"무효화됨","en":"Superseded"}'::jsonb,
    '{"terminal":true,"decisionEvidenceRequired":true}'::jsonb,
    90, TRUE, 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    behavior_metadata = EXCLUDED.behavior_metadata,
    sort_order = EXCLUDED.sort_order,
    predefined = EXCLUDED.predefined,
    lifecycle_state = EXCLUDED.lifecycle_state,
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(sys_code_values.display_name, sys_code_values.label_i18n,
          sys_code_values.behavior_metadata, sys_code_values.sort_order,
          sys_code_values.predefined, sys_code_values.lifecycle_state)
      IS DISTINCT FROM
      ROW(EXCLUDED.display_name, EXCLUDED.label_i18n,
          EXCLUDED.behavior_metadata, EXCLUDED.sort_order,
          EXCLUDED.predefined, EXCLUDED.lifecycle_state);

DO $v231_postflight$
DECLARE
    target_values CONSTANT VARCHAR[] := ARRAY[
        'APPROVED', 'CANCELLED', 'CLAIMED', 'INFO_REQUESTED',
        'PENDING', 'REASSIGNED', 'REJECTED', 'SKIPPED', 'SUPERSEDED'
    ]::VARCHAR[];
BEGIN
    IF (SELECT array_agg(code_value.code ORDER BY code_value.code)
          FROM sys_code_values code_value
         WHERE code_value.code_set_key = 'APPROVAL.APR_TASKS.STATUS'
           AND code_value.lifecycle_state = 'ACTIVE')
       IS DISTINCT FROM target_values THEN
        RAISE EXCEPTION 'V231 approval task status postflight failed';
    END IF;
END;
$v231_postflight$;
