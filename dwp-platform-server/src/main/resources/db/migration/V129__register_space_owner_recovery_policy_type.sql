INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
VALUES (
    'SPACE.POLICY_EVALUATION.TYPE',
    'SPACE_OWNER_RECOVERY',
    'Space owner recovery',
    '{"ko":"Space 소유자 복구","en":"Space owner recovery"}'::jsonb,
    60,
    '{"requiresReason":true,"minimumReasonLength":10,"riskLevel":"CRITICAL"}'::jsonb,
    'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
