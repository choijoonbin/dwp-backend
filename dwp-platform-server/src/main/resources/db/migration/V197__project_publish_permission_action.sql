-- Project the Auth-owned PUBLISH permission added for governed publishing flows.
INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, predefined, lifecycle_state)
VALUES (
    'AUTH.PERMISSION_ACTION',
    'PUBLISH',
    'Publish',
    '{"ko":"게시","en":"Publish"}',
    90,
    '{}',
    TRUE,
    'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    predefined = TRUE,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_values.display_name,
          sys_code_values.label_i18n,
          sys_code_values.sort_order,
          sys_code_values.behavior_metadata,
          sys_code_values.predefined,
          sys_code_values.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.display_name,
          EXCLUDED.label_i18n,
          EXCLUDED.sort_order,
          EXCLUDED.behavior_metadata,
          TRUE,
          'ACTIVE');
