INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-messaging', '메신저', 'Messenger',
       '조직, Space, 업무 맥락을 연결해 빠르게 대화하고 후속 조치를 이어갑니다.',
       'Connect organization, Space, and work context for fast conversations and follow-up actions.',
       'DWP Collaboration Platform', 'PRODUCTIVITY', 'NATIVE', '/messages/home',
       'messaging', 'APP.MESSAGING', 'HEALTHY', 32, 'ACTIVE', 1, 1
  FROM sys_service_tenants
ON CONFLICT (tenant_id, app_key) DO UPDATE SET
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    owner_name = EXCLUDED.owner_name,
    category = EXCLUDED.category,
    launch_mode = EXCLUDED.launch_mode,
    launch_target = EXCLUDED.launch_target,
    icon_key = EXCLUDED.icon_key,
    resource_key = EXCLUDED.resource_key,
    health_state = EXCLUDED.health_state,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    version = adm_workspace_apps.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

UPDATE adm_workspace_apps
   SET lifecycle_state = 'RETIRED',
       health_state = 'CONFIGURATION_REQUIRED',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'ref-app-collaboration'
   AND resource_key = 'APP.COLLABORATION';

UPDATE adm_home_experiences
   SET launchpad_configuration = REPLACE(
           launchpad_configuration::text,
           'APP.COLLABORATION',
           'APP.MESSAGING')::jsonb,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE launchpad_configuration::text LIKE '%APP.COLLABORATION%';

UPDATE adm_home_experiences experience
   SET launchpad_configuration = jsonb_set(
           experience.launchpad_configuration,
           '{placements}',
           (experience.launchpad_configuration -> 'placements')
               || '[{"resourceKey":"APP.MESSAGING","groupKey":"connect","sortOrder":32}]'::jsonb),
       version = experience.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(
              experience.launchpad_configuration -> 'placements') placement
        WHERE placement ->> 'resourceKey' = 'APP.MESSAGING');
