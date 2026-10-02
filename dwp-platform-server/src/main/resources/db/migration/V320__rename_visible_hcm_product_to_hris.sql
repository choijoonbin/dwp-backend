-- Keep stable technical identifiers (DWP_HCM, APP.HCM, hcm, and /hr), while
-- presenting the product under one user-facing name across existing tenants.
UPDATE adm_registry_entries
   SET name = 'HRIS',
       description = 'HRIS personal HR, organization, and governed workforce operations',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE registry_type = 'APP'
   AND entry_key = 'DWP_HCM'
   AND (name, description) IS DISTINCT FROM (
       'HRIS',
       'HRIS personal HR, organization, and governed workforce operations');

UPDATE adm_navigation_labels label
   SET label = 'HRIS',
       description = CASE
           WHEN LOWER(label.locale) LIKE 'ko%'
               THEN 'HRIS에서 나의 인사, 조직 및 권한별 인력 운영을 연결합니다.'
           ELSE 'Connect personal HR, organization, and role-aware workforce operations in HRIS.'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM adm_navigation_items item
 WHERE item.tenant_id = label.tenant_id
   AND item.navigation_item_id = label.navigation_item_id
   AND item.navigation_key = 'hcm'
   AND (label.label, label.description) IS DISTINCT FROM (
       'HRIS',
       CASE
           WHEN LOWER(label.locale) LIKE 'ko%'
               THEN 'HRIS에서 나의 인사, 조직 및 권한별 인력 운영을 연결합니다.'
           ELSE 'Connect personal HR, organization, and role-aware workforce operations in HRIS.'
       END);

UPDATE adm_workspace_apps
   SET name_ko = 'HRIS',
       name_en = 'HRIS',
       description_ko = 'HRIS 업무를 DWP 홈에서 안전하게 시작합니다.',
       description_en = 'Launch HRIS safely from DWP Home.',
       owner_name = 'HRIS',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'ref-app-people'
   AND (name_ko, name_en, description_ko, description_en, owner_name)
       IS DISTINCT FROM (
           'HRIS',
           'HRIS',
           'HRIS 업무를 DWP 홈에서 안전하게 시작합니다.',
           'Launch HRIS safely from DWP Home.',
           'HRIS');

UPDATE sys_code_sets
   SET display_name = 'HRIS home widget',
       description = 'Permission-aware widgets accepted by the HRIS personal home surface.',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HCM_HOME_WIDGET'
   AND (display_name, description) IS DISTINCT FROM (
       'HRIS home widget',
       'Permission-aware widgets accepted by the HRIS personal home surface.');

UPDATE sys_code_values
   SET display_name = 'HRIS home',
       label_i18n = '{"ko":"HRIS 홈","en":"HRIS home"}'::jsonb,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_SURFACE'
   AND code = 'hcm-home'
   AND (display_name, label_i18n) IS DISTINCT FROM (
       'HRIS home',
       '{"ko":"HRIS 홈","en":"HRIS home"}'::jsonb);
