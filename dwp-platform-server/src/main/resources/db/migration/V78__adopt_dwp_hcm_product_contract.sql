-- Preserve /hr and ref-app-people while promoting HCM as the canonical product
-- vocabulary. HRIS remains reserved for external source-system integrations.
UPDATE adm_registry_entries
   SET entry_key = 'DWP_HCM',
       name = 'DWP HCM',
       description = 'Personal HR, organization, and governed workforce operations',
       owner_ref = 'people:hcm',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE registry_type = 'APP'
   AND entry_key = 'DWP_HRIS';

UPDATE adm_navigation_items
   SET navigation_key = 'hcm',
       registry_entry_key = 'DWP_HCM',
       route = '/hr',
       icon_key = 'hcm',
       required_resource_key = 'APP.HCM',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE navigation_key = 'hris';

UPDATE adm_navigation_labels label
   SET label = CASE WHEN LOWER(label.locale) LIKE 'ko%' THEN '인사' ELSE 'HR' END,
       description = CASE
           WHEN LOWER(label.locale) LIKE 'ko%'
               THEN 'DWP HCM에서 나의 인사, 조직 및 권한별 인력 운영을 연결합니다.'
           ELSE 'Connect personal HR, organization, and role-aware workforce operations in DWP HCM.'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM adm_navigation_items item
 WHERE item.tenant_id = label.tenant_id
   AND item.navigation_item_id = label.navigation_item_id
   AND item.navigation_key = 'hcm';

UPDATE adm_workspace_apps
   SET name_ko = '인사',
       name_en = 'HR',
       description_ko = 'DWP HCM에서 나의 인사, 구성원, 조직 및 권한별 인력 운영을 연결합니다.',
       description_en = 'Connect personal HR, people, organization, and role-aware workforce operations in DWP HCM.',
       owner_name = 'DWP HCM',
       icon_key = 'hcm',
       resource_key = 'APP.HCM',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'ref-app-people';

UPDATE adm_home_experiences experience
   SET launchpad_configuration = jsonb_set(
           experience.launchpad_configuration,
           '{placements}',
           COALESCE((
               SELECT jsonb_agg(
                          CASE
                              WHEN placement.value ->> 'resourceKey' = 'APP.HRIS'
                                  THEN jsonb_set(
                                      placement.value,
                                      '{resourceKey}',
                                      to_jsonb('APP.HCM'::text))
                              ELSE placement.value
                          END
                          ORDER BY placement.ordinality)
                 FROM jsonb_array_elements(
                          experience.launchpad_configuration -> 'placements')
                      WITH ORDINALITY AS placement(value, ordinality)
           ), '[]'::jsonb),
           TRUE),
       updated_at = CURRENT_TIMESTAMP
 WHERE experience.launchpad_configuration @> '{"placements":[{"resourceKey":"APP.HRIS"}]}'::jsonb;

-- A canonical row wins if both aliases were ever written for the same user.
DELETE FROM usr_home_preferences legacy
USING usr_home_preferences canonical
 WHERE legacy.tenant_id = canonical.tenant_id
   AND legacy.user_id = canonical.user_id
   AND legacy.surface_key = 'hris-home'
   AND canonical.surface_key = 'hcm-home';

UPDATE usr_home_preferences
   SET surface_key = 'hcm-home',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE surface_key = 'hris-home';

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES (
    'PLATFORM.HCM_HOME_WIDGET', 'dwp-platform-server', 'HCM home widget',
    'Permission-aware widgets accepted by the DWP HCM personal home surface.',
    'SYSTEM', 'TYPED_CONTRACT',
    'HomePreferenceService.SURFACE_CONTRACTS[hcm-home]', 'REFERENCE')
ON CONFLICT (code_set_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    source_reference = EXCLUDED.source_reference,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, behavior_metadata,
    sort_order, predefined, lifecycle_state, introduced_schema_version)
SELECT 'PLATFORM.HCM_HOME_WIDGET', code, display_name, label_i18n,
       behavior_metadata, sort_order, predefined, 'ACTIVE', introduced_schema_version
  FROM sys_code_values
 WHERE code_set_key = 'PLATFORM.HRIS_HOME_WIDGET'
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    behavior_metadata = EXCLUDED.behavior_metadata,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.HCM_HOME_WIDGET', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceService.SURFACE_CONTRACTS[hcm-home]', 'TYPED_CONTRACT'),
    ('PLATFORM.HCM_HOME_WIDGET', 'dwp-frontend', 'BEHAVIOR',
     'hcm/hcm-home-widget-registry', 'CATALOG_LOOKUP')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_sets
   SET lifecycle_state = 'RETIRED',
       description = 'Compatibility catalog replaced by PLATFORM.HCM_HOME_WIDGET.',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HRIS_HOME_WIDGET';

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HRIS_HOME_WIDGET';

UPDATE sys_code_bindings
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HRIS_HOME_WIDGET';

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
VALUES (
    'PLATFORM.HOME_SURFACE', 'hcm-home', 'HCM home',
    '{"ko":"인사 홈","en":"HR home"}', 20,
    '{"personalizable":true,"permissionAware":true}', 'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       behavior_metadata = behavior_metadata || '{"aliasFor":"hcm-home"}'::jsonb,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_SURFACE'
   AND code = 'hris-home';
