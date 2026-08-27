INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-meetings', '화상회의', 'Meetings',
       '즉시 회의, 예약, 승인형 대기실과 안전한 실시간 협업을 한곳에서 운영합니다.',
       'Run instant and scheduled meetings with governed admission and secure realtime collaboration.',
       'DWP Realtime Collaboration', 'PRODUCTIVITY', 'NATIVE', '/meetings/home',
       'meetings', 'APP.MEETINGS', 'HEALTHY', 34, 'ACTIVE', 1, 1
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
           COALESCE(experience.launchpad_configuration -> 'placements', '[]'::jsonb)
               || '[{"resourceKey":"APP.MEETINGS","groupKey":"connect","sortOrder":34}]'::jsonb),
       version = experience.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(
              COALESCE(experience.launchpad_configuration -> 'placements', '[]'::jsonb)) placement
        WHERE placement ->> 'resourceKey' = 'APP.MEETINGS');
