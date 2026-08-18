-- Widget width and height are independent semantic constraints. Pixels remain a
-- presentation concern; persisted documents store only validated height tokens.
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'dwp-platform-server', 'Home widget height',
     'Responsive semantic height choices accepted by personal and governed home widgets.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomePreferenceService.WIDGET_HEIGHTS', 'REFERENCE')
ON CONFLICT (code_set_key) DO UPDATE
   SET display_name = EXCLUDED.display_name,
       description = EXCLUDED.description,
       validation_source = EXCLUDED.validation_source,
       source_reference = EXCLUDED.source_reference,
       contract_kind = EXCLUDED.contract_kind,
       lifecycle_state = 'ACTIVE',
       schema_version = GREATEST(sys_code_sets.schema_version, 1),
       updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'short', 'Short',
     '{"ko":"낮게","en":"Short"}', 10,
     '{"baselinePx":8,"blockSizePx":288,"contentRows":2,"phoneMode":"CONTENT"}'),
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'standard', 'Standard',
     '{"ko":"표준","en":"Standard"}', 20,
     '{"baselinePx":8,"blockSizePx":368,"contentRows":3,"phoneMode":"CONTENT"}'),
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'tall', 'Tall',
     '{"ko":"높게","en":"Tall"}', 30,
     '{"baselinePx":8,"blockSizePx":448,"contentRows":4,"phoneMode":"CONTENT"}'),
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'expanded', 'Expanded',
     '{"ko":"확장","en":"Expanded"}', 40,
     '{"baselinePx":8,"blockSizePx":560,"contentRows":6,"phoneMode":"CONTENT"}')
ON CONFLICT (code_set_key, code) DO UPDATE
   SET display_name = EXCLUDED.display_name,
       label_i18n = EXCLUDED.label_i18n,
       sort_order = EXCLUDED.sort_order,
       behavior_metadata = EXCLUDED.behavior_metadata,
       lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceDtos.WidgetPreference.height', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'dwp-platform-server', 'API_CONTRACT',
     'HomeExperienceDtos.GovernedHomeZone.height', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_WIDGET_HEIGHT', 'dwp-frontend', 'UI_SELECTION',
     'workspace-composer/widget-height', 'CATALOG_LOOKUP')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;

UPDATE sys_code_values
   SET behavior_metadata = behavior_metadata || CASE code
           WHEN 'command-rail' THEN
               '{"defaultHeight":"short","allowedHeights":["short","standard"]}'::jsonb
           WHEN 'activity' THEN
               '{"defaultHeight":"tall","allowedHeights":["short","standard","tall"]}'::jsonb
           WHEN 'focus' THEN
               '{"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]}'::jsonb
           WHEN 'schedule' THEN
               '{"defaultHeight":"standard","allowedHeights":["short","standard","tall"]}'::jsonb
           WHEN 'daily-brief' THEN
               '{"defaultHeight":"standard","allowedHeights":["short","standard","tall"]}'::jsonb
           ELSE '{}'::jsonb
       END,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET';

UPDATE sys_code_values
   SET behavior_metadata = behavior_metadata || CASE code
           WHEN 'quick-actions' THEN
               '{"defaultHeight":"short","allowedHeights":["short","standard"]}'::jsonb
           WHEN 'people-signals' THEN
               '{"defaultHeight":"standard","allowedHeights":["short","standard","tall"]}'::jsonb
           WHEN 'attention' THEN
               '{"defaultHeight":"tall","allowedHeights":["standard","tall","expanded"]}'::jsonb
           WHEN 'profile' THEN
               '{"defaultHeight":"standard","allowedHeights":["short","standard","tall"]}'::jsonb
           WHEN 'team' THEN
               '{"defaultHeight":"tall","allowedHeights":["standard","tall","expanded"]}'::jsonb
           WHEN 'operations' THEN
               '{"defaultHeight":"tall","allowedHeights":["standard","tall","expanded"]}'::jsonb
           ELSE '{}'::jsonb
       END,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HCM_HOME_WIDGET';

UPDATE sys_code_values
   SET behavior_metadata = behavior_metadata || CASE code
           WHEN 'decision-pulse' THEN
               '{"defaultHeight":"short","allowedHeights":["short","standard"]}'::jsonb
           WHEN 'focus-queue' THEN
               '{"defaultHeight":"tall","allowedHeights":["standard","tall","expanded"]}'::jsonb
           WHEN 'flow' THEN
               '{"defaultHeight":"standard","allowedHeights":["short","standard","tall"]}'::jsonb
           WHEN 'my-requests' THEN
               '{"defaultHeight":"standard","allowedHeights":["short","standard","tall"]}'::jsonb
           WHEN 'insights' THEN
               '{"defaultHeight":"standard","allowedHeights":["short","standard","tall"]}'::jsonb
           WHEN 'admin-health' THEN
               '{"defaultHeight":"tall","allowedHeights":["standard","tall","expanded"]}'::jsonb
           ELSE '{}'::jsonb
       END,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.APPROVAL_HOME_WIDGET';

UPDATE usr_home_preferences preference
   SET layout_payload = jsonb_set(
           preference.layout_payload,
           '{widgets}',
           COALESCE((
               SELECT jsonb_agg(
                       CASE
                           WHEN widget ->> 'height' IN ('short', 'standard', 'tall', 'expanded')
                               THEN widget
                           ELSE widget || jsonb_build_object(
                               'height',
                               CASE preference.surface_key
                                   WHEN 'workspace-home' THEN CASE widget ->> 'widgetKey'
                                       WHEN 'command-rail' THEN 'short'
                                       WHEN 'activity' THEN 'tall'
                                       WHEN 'focus' THEN 'tall'
                                       WHEN 'schedule' THEN 'standard'
                                       WHEN 'daily-brief' THEN 'standard'
                                       ELSE 'standard'
                                   END
                                   WHEN 'hcm-home' THEN CASE widget ->> 'widgetKey'
                                       WHEN 'quick-actions' THEN 'short'
                                       WHEN 'people-signals' THEN 'standard'
                                       WHEN 'attention' THEN 'tall'
                                       WHEN 'profile' THEN 'standard'
                                       WHEN 'team' THEN 'tall'
                                       WHEN 'operations' THEN 'tall'
                                       ELSE 'standard'
                                   END
                                   WHEN 'approval-home' THEN CASE widget ->> 'widgetKey'
                                       WHEN 'decision-pulse' THEN 'short'
                                       WHEN 'focus-queue' THEN 'tall'
                                       WHEN 'flow' THEN 'standard'
                                       WHEN 'my-requests' THEN 'standard'
                                       WHEN 'insights' THEN 'standard'
                                       WHEN 'admin-health' THEN 'tall'
                                       ELSE 'standard'
                                   END
                                   ELSE 'standard'
                               END)
                       END
                       ORDER BY ordinal)
                 FROM jsonb_array_elements(preference.layout_payload -> 'widgets')
                      WITH ORDINALITY AS item(widget, ordinal)
           ), '[]'::jsonb),
           true),
       schema_version = 5,
       updated_at = CURRENT_TIMESTAMP
 WHERE jsonb_typeof(preference.layout_payload -> 'widgets') = 'array';

UPDATE adm_home_experiences experience
   SET composition_policy = jsonb_set(
           jsonb_set(experience.composition_policy, '{schemaVersion}', '2'::jsonb, true),
           '{governedZones}',
           COALESCE((
               SELECT jsonb_agg(
                       CASE
                           WHEN zone ->> 'height' IN ('short', 'standard') THEN zone
                           ELSE zone || jsonb_build_object('height', 'short')
                       END
                       ORDER BY ordinal)
                 FROM jsonb_array_elements(experience.composition_policy -> 'governedZones')
                      WITH ORDINALITY AS item(zone, ordinal)
           ), '[]'::jsonb),
           true),
       updated_at = CURRENT_TIMESTAMP
 WHERE jsonb_typeof(experience.composition_policy -> 'governedZones') = 'array';

ALTER TABLE adm_home_experiences
    ALTER COLUMN composition_policy SET DEFAULT
    '{"schemaVersion":2,"personalCustomizationEnabled":true,"governedZones":[{"zoneKey":"announcements","placement":"CANVAS","visible":true,"size":"compact","height":"short","sortOrder":20}]}'::jsonb;

COMMENT ON COLUMN usr_home_preferences.layout_payload IS
    'Versioned, server-validated 2D widget composition. Width and height are semantic tokens; executable code and pixel geometry are never persisted.';
