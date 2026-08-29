-- Align the governed Meeting climate signal vocabulary with the provider/API
-- contract after speakerless inference signals were removed. V210 remains
-- immutable; unsupported values are retired forward.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

DO $v215_preflight$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM sys_code_sets code_set
         WHERE code_set.code_set_key = 'MEETING.INTELLIGENCE.CLIMATE_SIGNAL'
           AND code_set.owner_service = 'dwp-meeting-server'
           AND code_set.source_reference =
                   'MeetingIntelligenceProvider.ClimateSignal'
           AND code_set.validation_source = 'TYPED_CONTRACT'
           AND code_set.contract_kind = 'PROTOCOL'
           AND code_set.configuration_level = 'SYSTEM'
           AND code_set.runtime_visibility = 'ADMIN_ONLY'
           AND code_set.lifecycle_state = 'ACTIVE'
    ) OR (SELECT COUNT(*)
            FROM sys_code_bindings binding
           WHERE binding.code_set_key = 'MEETING.INTELLIGENCE.CLIMATE_SIGNAL'
             AND binding.consumer_service = 'dwp-meeting-server'
             AND binding.usage_type = 'API_CONTRACT'
             AND binding.source_reference =
                     'MeetingIntelligenceProvider.ClimateSignal'
             AND binding.enforcement_type = 'TYPED_CONTRACT'
             AND binding.lifecycle_state = 'ACTIVE') <> 1
    THEN
        RAISE EXCEPTION 'V215 Meeting climate signal ownership drifted';
    END IF;
END;
$v215_preflight$;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT 'MEETING.INTELLIGENCE.CLIMATE_SIGNAL',
       value_ref.code,
       value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb,
       value_ref.ordinality::INTEGER * 10,
       TRUE,
       'ACTIVE'
  FROM unnest(ARRAY[
           'CONSTRUCTIVE_DISAGREEMENT',
           'LOW_TRANSCRIPT_EVIDENCE',
           'UNRESOLVED_DISAGREEMENT'
       ]::VARCHAR[]) WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'MEETING.INTELLIGENCE.CLIMATE_SIGNAL'
   AND code NOT IN (
       'CONSTRUCTIVE_DISAGREEMENT',
       'LOW_TRANSCRIPT_EVIDENCE',
       'UNRESOLVED_DISAGREEMENT')
   AND lifecycle_state <> 'RETIRED';

DO $v215_postcondition$
BEGIN
    IF (SELECT array_agg(code ORDER BY code)
          FROM sys_code_values
         WHERE code_set_key = 'MEETING.INTELLIGENCE.CLIMATE_SIGNAL'
           AND lifecycle_state = 'ACTIVE')
       IS DISTINCT FROM ARRAY[
           'CONSTRUCTIVE_DISAGREEMENT',
           'LOW_TRANSCRIPT_EVIDENCE',
           'UNRESOLVED_DISAGREEMENT'
       ]::VARCHAR[]
    OR EXISTS (
        SELECT 1
          FROM sys_code_values
         WHERE code_set_key = 'MEETING.INTELLIGENCE.CLIMATE_SIGNAL'
           AND code IN (
               'BALANCED_TURN_TAKING',
               'DOMINANT_MONOLOGUE_PATTERN')
           AND lifecycle_state <> 'RETIRED'
    ) THEN
        RAISE EXCEPTION 'V215 Meeting climate signal convergence failed';
    END IF;
END;
$v215_postcondition$;
