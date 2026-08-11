-- Keep the provider permission catalog and the global product contract registry aligned.
INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
VALUES (
    'PROVIDER.PERMISSION',
    'CATALOG_READ',
    'Read product contract catalog',
    '{"ko":"제품 계약 카탈로그 조회","en":"Read product contract catalog"}'::jsonb,
    '{"riskTier":"L1","scope":"GLOBAL_PRODUCT"}'::jsonb,
    35,
    TRUE,
    'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    behavior_metadata = EXCLUDED.behavior_metadata,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;
