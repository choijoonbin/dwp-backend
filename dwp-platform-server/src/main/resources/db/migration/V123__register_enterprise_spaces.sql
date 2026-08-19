INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-spaces', 'Space', 'Spaces',
       '목적별 협업 공간에서 구성원, 콘텐츠, 앱과 AI 컨텍스트를 안전하게 연결합니다.',
       'Connect people, content, apps, and governed AI context in purpose-built collaboration spaces.',
       'DWP Collaboration Platform', 'PRODUCTIVITY', 'NATIVE', '/spaces/home',
       'spaces', 'APP.SPACES', 'HEALTHY', 35, 'ACTIVE', 1, 1
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

UPDATE adm_home_experiences experience
   SET launchpad_configuration = jsonb_set(
           experience.launchpad_configuration,
           '{placements}',
           (experience.launchpad_configuration -> 'placements')
               || '[{"resourceKey":"APP.SPACES","groupKey":"connect","sortOrder":45}]'::jsonb),
       version = experience.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(
              experience.launchpad_configuration -> 'placements') placement
        WHERE placement ->> 'resourceKey' = 'APP.SPACES');
