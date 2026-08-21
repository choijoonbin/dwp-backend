INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
VALUES (
    'AUTH.BUILT_IN_ROLE',
    'NOTIFICATION_TEMPLATE_APPROVER',
    'Notification template approver',
    '{"ko":"알림 템플릿 승인자","en":"Notification template approver"}',
    146,
    '{"roleFamily":"WORKSPACE","privileged":true,"assignableToGroups":false,"assignmentClass":"DELEGATED"}',
    'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
