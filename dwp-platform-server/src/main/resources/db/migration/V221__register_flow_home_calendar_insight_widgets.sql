-- Flow Home calendar insights are independent personal widgets backed by the
-- existing bounded home overview projection. Persisted layouts are deliberately
-- left untouched; HomeLayoutPolicy reconciles missing registered widgets at read.
UPDATE sys_code_sets
   SET description = 'Personal widgets accepted by the persisted workspace home layout.',
       schema_version = GREATEST(schema_version, 5),
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET';

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.HOME_WIDGET', 'focus-balance', 'Focus balance',
     '{"ko":"집중 시간","en":"Focus balance"}', 60,
     '{"canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium"],"defaultHeight":"short","allowedHeights":["short","standard"],"owner":"core.calendar","sourceAppResourceKey":"APP.CALENDAR","dataSource":"DWP_CALENDAR","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.focus-balance","compositionKind":"PERSONAL","flowAlias":"focus-balance"}'),
    ('PLATFORM.HOME_WIDGET', 'meeting-load', 'Meeting load',
     '{"ko":"회의 부하","en":"Meeting load"}', 70,
     '{"canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium"],"defaultHeight":"short","allowedHeights":["short","standard"],"owner":"core.calendar","sourceAppResourceKey":"APP.CALENDAR","dataSource":"DWP_CALENDAR","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.meeting-load","compositionKind":"PERSONAL","flowAlias":"meeting-load"}')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
