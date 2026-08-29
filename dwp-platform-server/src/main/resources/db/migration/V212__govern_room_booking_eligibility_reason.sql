-- Govern the public room availability eligibility reason returned by the
-- Calendar/Rooms API. The enum is an exact security decision vocabulary: a
-- client may submit a booking only when the returned reason is ELIGIBLE.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

DO $v212_preflight$
DECLARE
    expected_values CONSTANT VARCHAR[] := ARRAY[
        'ELIGIBLE',
        'POLICY_BLOCKED',
        'RESOURCE_CONFLICT',
        'RESOURCE_UNAVAILABLE'
    ]::VARCHAR[];
BEGIN
    IF EXISTS (
        SELECT 1
          FROM sys_code_sets code_set
         WHERE code_set.code_set_key =
                   'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON'
           AND ROW(code_set.owner_service, code_set.source_reference,
                   code_set.validation_source, code_set.contract_kind,
                   code_set.configuration_level, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW('dwp-platform-server',
                   'CalendarTypes.RoomBookingEligibilityReason',
                   'TYPED_CONTRACT', 'SECURITY', 'SYSTEM', 'ADMIN_ONLY',
                   'ACTIVE')
    ) OR EXISTS (
        SELECT 1
          FROM sys_code_sets code_set
         WHERE code_set.owner_service = 'dwp-platform-server'
           AND code_set.source_reference =
                   'CalendarTypes.RoomBookingEligibilityReason'
           AND code_set.code_set_key <>
                   'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON'
           AND code_set.lifecycle_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'V212 room booking eligibility code-set metadata drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM sys_code_sets code_set
         WHERE code_set.code_set_key =
                   'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON'
           AND (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM expected_values
    ) THEN
        RAISE EXCEPTION
            'V212 room booking eligibility values drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM sys_code_bindings binding
         WHERE binding.consumer_service = 'dwp-platform-server'
           AND binding.source_reference =
                   'CalendarTypes.RoomBookingEligibilityReason'
           AND binding.lifecycle_state = 'ACTIVE'
           AND ROW(binding.code_set_key, binding.usage_type,
                   binding.enforcement_type)
               IS DISTINCT FROM
               ROW('PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON',
                   'API_CONTRACT', 'TYPED_CONTRACT')
    ) THEN
        RAISE EXCEPTION
            'V212 room booking eligibility binding drifted';
    END IF;
END;
$v212_preflight$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
VALUES (
    'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON',
    'dwp-platform-server',
    'Room booking eligibility reason',
    'Exact public API decision vocabulary for room booking eligibility.',
    'SYSTEM', 'TYPED_CONTRACT',
    'CalendarTypes.RoomBookingEligibilityReason', 'SECURITY',
    'ADMIN_ONLY', 'ACTIVE')
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT 'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON',
       value_ref.code, value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb, value_ref.ordinality::INTEGER * 10, TRUE, 'ACTIVE'
  FROM unnest(ARRAY[
           'ELIGIBLE',
           'POLICY_BLOCKED',
           'RESOURCE_CONFLICT',
           'RESOURCE_UNAVAILABLE'
       ]::VARCHAR[]) WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
VALUES (
    'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON',
    'dwp-platform-server', 'API_CONTRACT',
    'CalendarTypes.RoomBookingEligibilityReason',
    'TYPED_CONTRACT', 'ACTIVE')
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO NOTHING;

DO $v212_postcondition$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM sys_code_sets code_set
         WHERE code_set.code_set_key =
                   'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON'
           AND code_set.owner_service = 'dwp-platform-server'
           AND code_set.source_reference =
                   'CalendarTypes.RoomBookingEligibilityReason'
           AND code_set.validation_source = 'TYPED_CONTRACT'
           AND code_set.contract_kind = 'SECURITY'
           AND code_set.configuration_level = 'SYSTEM'
           AND code_set.runtime_visibility = 'ADMIN_ONLY'
           AND code_set.lifecycle_state = 'ACTIVE'
           AND (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE') = ARRAY[
                       'ELIGIBLE',
                       'POLICY_BLOCKED',
                       'RESOURCE_CONFLICT',
                       'RESOURCE_UNAVAILABLE'
                   ]::VARCHAR[]
    ) OR (SELECT COUNT(*)
            FROM sys_code_bindings binding
           WHERE binding.code_set_key =
                     'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON'
             AND binding.consumer_service = 'dwp-platform-server'
             AND binding.usage_type = 'API_CONTRACT'
             AND binding.source_reference =
                     'CalendarTypes.RoomBookingEligibilityReason'
             AND binding.enforcement_type = 'TYPED_CONTRACT'
             AND binding.lifecycle_state = 'ACTIVE') <> 1
    THEN
        RAISE EXCEPTION
            'V212 room booking eligibility contract convergence failed';
    END IF;
END;
$v212_postcondition$;
