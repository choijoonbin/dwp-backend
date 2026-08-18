-- A 60-unit logical grid is the least common grid that exactly represents
-- 1/2, 1/3, 1/4, and 1/5 widget footprints without fractional spans.
UPDATE sys_code_sets
   SET display_name = 'Home widget footprint',
       description = 'Responsive widget footprints governed by a 60-unit desktop grid.',
       schema_version = GREATEST(schema_version, 3),
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET_SIZE';

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.HOME_WIDGET_SIZE', 'fifth', 'Five per row',
     '{"ko":"한 줄에 5개","en":"5 per row"}', 10,
     '{"gridColumns":60,"desktopSpan":12,"tabletSpan":60,"mobileSpan":60,"itemsPerRow":5,"fraction":"1/5"}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'quarter', 'Four per row',
     '{"ko":"한 줄에 4개","en":"4 per row"}', 20,
     '{"gridColumns":60,"desktopSpan":15,"tabletSpan":60,"mobileSpan":60,"itemsPerRow":4,"fraction":"1/4"}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'compact', 'Three per row',
     '{"ko":"한 줄에 3개","en":"3 per row"}', 30,
     '{"gridColumns":60,"desktopSpan":20,"tabletSpan":60,"mobileSpan":60,"itemsPerRow":3,"fraction":"1/3"}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'medium', 'Two per row',
     '{"ko":"한 줄에 2개","en":"2 per row"}', 40,
     '{"gridColumns":60,"desktopSpan":30,"tabletSpan":60,"mobileSpan":60,"itemsPerRow":2,"fraction":"1/2"}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'large', 'Two-thirds width',
     '{"ko":"한 줄의 3분의 2","en":"Two-thirds width"}', 50,
     '{"gridColumns":60,"desktopSpan":40,"tabletSpan":60,"mobileSpan":60,"fraction":"2/3"}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'full', 'Full width',
     '{"ko":"전체 너비","en":"Full width"}', 60,
     '{"gridColumns":60,"desktopSpan":60,"tabletSpan":60,"mobileSpan":60,"itemsPerRow":1,"fraction":"1/1"}')
ON CONFLICT (code_set_key, code) DO UPDATE
   SET display_name = EXCLUDED.display_name,
       label_i18n = EXCLUDED.label_i18n,
       sort_order = EXCLUDED.sort_order,
       behavior_metadata = EXCLUDED.behavior_metadata,
       lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP;

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
               '{"canHide":true,"defaultSize":"quarter","allowedSizes":["fifth","quarter","compact","medium"],"owner":"Digital Workplace Product","dataSource":"DWP_ACTIVITY","freshnessSeconds":30,"privacyClass":"INTERNAL","retention":"NONE","analyticsKey":"home.activity"}'::jsonb
           WHEN 'focus' THEN
               '{"canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"owner":"Digital Workplace Product","dataSource":"DWP_WORKSPACE","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.focus"}'::jsonb
           WHEN 'schedule' THEN
               '{"canHide":true,"defaultSize":"quarter","allowedSizes":["fifth","quarter","compact","medium"],"owner":"Calendar Product","dataSource":"DWP_CALENDAR","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.schedule"}'::jsonb
           WHEN 'daily-brief' THEN
               '{"canHide":true,"defaultSize":"full","allowedSizes":["large","full"],"owner":"Digital Workplace Product","dataSource":"DWP_HOME_OVERVIEW","freshnessSeconds":30,"privacyClass":"INTERNAL","retention":"NONE","analyticsKey":"home.workday-insights"}'::jsonb
           ELSE behavior_metadata
       END,
       lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET'
   AND code IN ('activity', 'focus', 'schedule', 'daily-brief');

-- Existing personal layouts keep their stored footprint so an upgrade never
-- rewrites an intentional user choice. New and reset layouts use the defaults above.
