UPDATE usr_personal_preferences
   SET preference_payload =
           (preference_payload - ARRAY['appearance', 'accessibility', 'regional']::TEXT[])
           || jsonb_build_object(
               'appearance',
               jsonb_build_object('mode', 'system', 'density', 'standard')
                   || COALESCE(preference_payload -> 'appearance', '{}'::jsonb),
               'accessibility',
               jsonb_build_object(
                   'highContrast', FALSE,
                   'reduceMotion', FALSE,
                   'underlineLinks', FALSE,
                   'reduceTransparency', FALSE)
                   || COALESCE(preference_payload -> 'accessibility', '{}'::jsonb),
               'regional',
               jsonb_build_object(
                   'timeZone', 'system',
                   'dateFormat', 'locale',
                   'timeFormat', 'locale',
                   'firstDayOfWeek', 'locale',
                   'numberFormat', 'locale')
                   || COALESCE(preference_payload -> 'regional', '{}'::jsonb)
           ),
       schema_version = 2,
       updated_at = CURRENT_TIMESTAMP
 WHERE schema_version < 2;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'dwp-platform-server',
     'Personal time zone', 'IANA time zones available in personal regional settings.',
     'USER', 'EXTERNAL_STANDARD', 'java.time.ZoneId / regional.timeZone', 'REFERENCE'),
    ('PLATFORM.PREFERENCE.DATE_FORMAT', 'dwp-platform-server',
     'Personal date format', 'Date presentation selected for the personal workspace.',
     'SYSTEM', 'TYPED_CONTRACT', 'PersonalPreferenceService.DATE_FORMATS / regional.dateFormat', 'PROTOCOL'),
    ('PLATFORM.PREFERENCE.TIME_FORMAT', 'dwp-platform-server',
     'Personal time format', 'Clock presentation selected for the personal workspace.',
     'SYSTEM', 'TYPED_CONTRACT', 'PersonalPreferenceService.TIME_FORMATS / regional.timeFormat', 'PROTOCOL'),
    ('PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK', 'dwp-platform-server',
     'Personal first day of week', 'Calendar week boundary selected for the personal workspace.',
     'SYSTEM', 'TYPED_CONTRACT', 'PersonalPreferenceService.FIRST_DAYS / regional.firstDayOfWeek', 'PROTOCOL'),
    ('PLATFORM.PREFERENCE.NUMBER_FORMAT', 'dwp-platform-server',
     'Personal number format', 'Numeric grouping and decimal presentation selected for the personal workspace.',
     'SYSTEM', 'TYPED_CONTRACT', 'PersonalPreferenceService.NUMBER_FORMATS / regional.numberFormat', 'PROTOCOL');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'system', 'System time zone',
     '{"ko":"기기 시간대","en":"System time zone"}', 10, '{"systemResolved":true}'),
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'UTC', 'UTC', '{}', 20, '{"iana":true}'),
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'Asia/Seoul', 'Seoul',
     '{"ko":"서울 (UTC+09:00)","en":"Seoul (UTC+09:00)"}', 30, '{"iana":true}'),
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'Asia/Tokyo', 'Tokyo',
     '{"ko":"도쿄 (UTC+09:00)","en":"Tokyo (UTC+09:00)"}', 40, '{"iana":true}'),
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'Asia/Singapore', 'Singapore',
     '{"ko":"싱가포르 (UTC+08:00)","en":"Singapore (UTC+08:00)"}', 50, '{"iana":true}'),
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'Europe/London', 'London',
     '{"ko":"런던","en":"London"}', 60, '{"iana":true}'),
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'America/New_York', 'New York',
     '{"ko":"뉴욕","en":"New York"}', 70, '{"iana":true}'),
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'America/Los_Angeles', 'Los Angeles',
     '{"ko":"로스앤젤레스","en":"Los Angeles"}', 80, '{"iana":true}'),
    ('PLATFORM.PREFERENCE.DATE_FORMAT', 'locale', 'Language default',
     '{"ko":"언어 기본값","en":"Language default"}', 10, '{}'),
    ('PLATFORM.PREFERENCE.DATE_FORMAT', 'iso', 'ISO date',
     '{"ko":"연-월-일","en":"Year-month-day"}', 20, '{"pattern":"yyyy-MM-dd"}'),
    ('PLATFORM.PREFERENCE.DATE_FORMAT', 'month_first', 'Month first',
     '{"ko":"월/일/연","en":"Month/day/year"}', 30, '{"pattern":"MM/dd/yyyy"}'),
    ('PLATFORM.PREFERENCE.DATE_FORMAT', 'day_first', 'Day first',
     '{"ko":"일/월/연","en":"Day/month/year"}', 40, '{"pattern":"dd/MM/yyyy"}'),
    ('PLATFORM.PREFERENCE.TIME_FORMAT', 'locale', 'Language default',
     '{"ko":"언어 기본값","en":"Language default"}', 10, '{}'),
    ('PLATFORM.PREFERENCE.TIME_FORMAT', '12_hour', '12-hour clock',
     '{"ko":"12시간제","en":"12-hour clock"}', 20, '{"hour12":true}'),
    ('PLATFORM.PREFERENCE.TIME_FORMAT', '24_hour', '24-hour clock',
     '{"ko":"24시간제","en":"24-hour clock"}', 30, '{"hour12":false}'),
    ('PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK', 'locale', 'Language default',
     '{"ko":"언어 기본값","en":"Language default"}', 10, '{}'),
    ('PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK', 'monday', 'Monday',
     '{"ko":"월요일","en":"Monday"}', 20, '{}'),
    ('PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK', 'sunday', 'Sunday',
     '{"ko":"일요일","en":"Sunday"}', 30, '{}'),
    ('PLATFORM.PREFERENCE.NUMBER_FORMAT', 'locale', 'Language default',
     '{"ko":"언어 기본값","en":"Language default"}', 10, '{}'),
    ('PLATFORM.PREFERENCE.NUMBER_FORMAT', 'comma_decimal', '1,234.56', '{}', 20, '{"locale":"en-US"}'),
    ('PLATFORM.PREFERENCE.NUMBER_FORMAT', 'dot_decimal', '1.234,56', '{}', 30, '{"locale":"de-DE"}'),
    ('PLATFORM.PREFERENCE.NUMBER_FORMAT', 'space_decimal', '1 234,56', '{}', 40, '{"locale":"fr-FR"}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
SELECT code_set_key, 'dwp-platform-server', 'API_CONTRACT', source_reference, 'TYPED_CONTRACT'
  FROM sys_code_sets
 WHERE code_set_key IN (
    'PLATFORM.PREFERENCE.TIME_ZONE',
    'PLATFORM.PREFERENCE.DATE_FORMAT',
    'PLATFORM.PREFERENCE.TIME_FORMAT',
    'PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK',
    'PLATFORM.PREFERENCE.NUMBER_FORMAT'
 );

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.PREFERENCE.TIME_ZONE', 'dwp-frontend', 'UI_SELECTION', 'account/settings regional.timeZone', 'CATALOG_LOOKUP'),
    ('PLATFORM.PREFERENCE.DATE_FORMAT', 'dwp-frontend', 'UI_SELECTION', 'account/settings regional.dateFormat', 'CATALOG_LOOKUP'),
    ('PLATFORM.PREFERENCE.TIME_FORMAT', 'dwp-frontend', 'UI_SELECTION', 'account/settings regional.timeFormat', 'CATALOG_LOOKUP'),
    ('PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK', 'dwp-frontend', 'UI_SELECTION', 'account/settings regional.firstDayOfWeek', 'CATALOG_LOOKUP'),
    ('PLATFORM.PREFERENCE.NUMBER_FORMAT', 'dwp-frontend', 'UI_SELECTION', 'account/settings regional.numberFormat', 'CATALOG_LOOKUP');

COMMENT ON COLUMN usr_personal_preferences.preference_payload IS
    'Versioned JSONB personal settings document; tenant-managed policy remains outside this user-owned row.';
