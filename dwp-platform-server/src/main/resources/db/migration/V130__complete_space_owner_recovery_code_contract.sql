INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
VALUES (
    'SPACE.MEMBERSHIP.SOURCE',
    'RECOVERY',
    'Recovery',
    '{"ko":"관리자 복구","en":"Recovery"}'::jsonb,
    60,
    '{"restrictedTo":"ADMIN.SPACE_GOVERNANCE:MANAGE","requiresReason":true}'::jsonb,
    'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values
   SET label_i18n = CASE code
           WHEN 'SPACE_CREATION' THEN '{"ko":"Space 생성","en":"Space creation"}'::jsonb
           WHEN 'SPACE_ACCESS' THEN '{"ko":"Space 접근","en":"Space access"}'::jsonb
           WHEN 'CONTENT_PUBLICATION' THEN '{"ko":"콘텐츠 게시","en":"Content publication"}'::jsonb
           WHEN 'MEMBERSHIP_CHANGE' THEN '{"ko":"멤버십 변경","en":"Membership change"}'::jsonb
           WHEN 'LIFECYCLE' THEN '{"ko":"수명주기","en":"Lifecycle"}'::jsonb
           WHEN 'SPACE_OWNER_RECOVERY' THEN '{"ko":"Space 소유자 복구","en":"Space owner recovery"}'::jsonb
           ELSE label_i18n
       END,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'SPACE.POLICY_EVALUATION.TYPE';
