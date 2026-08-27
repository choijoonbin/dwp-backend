-- Project the Auth-owned MEETING_ADMIN catalog row introduced by Auth V100.
-- The behavior metadata mirrors the authoritative built-in role attributes.
INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, predefined, lifecycle_state)
VALUES (
    'AUTH.BUILT_IN_ROLE',
    'MEETING_ADMIN',
    'Meeting administrator',
    '{"ko":"화상회의 관리자","en":"Meeting administrator"}',
    59,
    '{"privileged":true,"roleFamily":"WORKSPACE","assignmentClass":"DELEGATED","assignableToGroups":true}',
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
