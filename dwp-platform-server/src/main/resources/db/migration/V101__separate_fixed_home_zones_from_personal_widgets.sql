-- Fixed home composition zones are rendered by the product shell and do not
-- belong in a user's movable, resizable widget preference document.
UPDATE sys_code_sets
   SET description = 'Personalizable widgets accepted by the persisted workspace home layout.',
       schema_version = GREATEST(schema_version, 2),
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET';

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       behavior_metadata = behavior_metadata ||
           '{"compositionKind":"FIXED_ZONE","preferenceStored":false}'::jsonb,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET'
   AND code = 'announcements';

UPDATE sys_code_values
   SET sort_order = CASE code
           WHEN 'activity' THEN 10
           WHEN 'focus' THEN 20
           WHEN 'schedule' THEN 30
           WHEN 'daily-brief' THEN 40
           ELSE sort_order
       END,
       behavior_metadata = CASE code
           WHEN 'activity' THEN
               '{"canHide":true,"defaultSize":"compact","allowedSizes":["compact","medium"],"owner":"Digital Workplace Product","dataSource":"DWP_ACTIVITY","freshnessSeconds":30,"privacyClass":"INTERNAL","retention":"NONE","analyticsKey":"home.activity"}'::jsonb
           WHEN 'focus' THEN
               '{"canHide":true,"defaultSize":"compact","allowedSizes":["compact","medium","large","full"],"owner":"Digital Workplace Product","dataSource":"DWP_WORKSPACE","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.focus"}'::jsonb
           WHEN 'schedule' THEN
               '{"canHide":true,"defaultSize":"compact","allowedSizes":["compact","medium"],"owner":"Calendar Product","dataSource":"DWP_CALENDAR","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.schedule"}'::jsonb
           WHEN 'daily-brief' THEN
               '{"canHide":true,"defaultSize":"full","allowedSizes":["large","full"],"owner":"Digital Workplace Product","dataSource":"DWP_HOME_OVERVIEW","freshnessSeconds":30,"privacyClass":"INTERNAL","retention":"NONE","analyticsKey":"home.workday-insights"}'::jsonb
           ELSE behavior_metadata
       END,
       lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET'
   AND code IN ('activity', 'focus', 'schedule', 'daily-brief');

UPDATE usr_home_preferences AS preference
   SET layout_payload = jsonb_set(
           preference.layout_payload,
           '{widgets}',
           (
               SELECT COALESCE(jsonb_agg(item.widget ORDER BY item.ordinality), '[]'::jsonb)
                 FROM jsonb_array_elements(preference.layout_payload -> 'widgets')
                      WITH ORDINALITY AS item(widget, ordinality)
                WHERE item.widget ->> 'widgetKey' <> 'announcements'
           ),
           false)
 WHERE preference.surface_key = 'workspace-home'
   AND jsonb_typeof(preference.layout_payload -> 'widgets') = 'array'
   AND EXISTS (
       SELECT 1
         FROM jsonb_array_elements(preference.layout_payload -> 'widgets') AS item(widget)
        WHERE item.widget ->> 'widgetKey' = 'announcements'
   );
