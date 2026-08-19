INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-notifications', '알림 센터', 'Notification center',
       '업무 앱에서 도착한 알림을 우선순위에 따라 확인하고 저장, 완료, 나중에 처리합니다.',
       'Triage, save, complete, or snooze notifications from every DWP application.',
       'DWP Shared Experience Platform', 'PRODUCTIVITY', 'NATIVE', '/notifications',
       'notifications', 'APP.NOTIFICATIONS', 'HEALTHY', 35, 'ACTIVE', 1, 1
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
               || '[{"resourceKey":"APP.NOTIFICATIONS","groupKey":"work","sortOrder":35}]'::jsonb),
       version = experience.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(
              COALESCE(experience.launchpad_configuration -> 'placements', '[]'::jsonb)) placement
        WHERE placement ->> 'resourceKey' = 'APP.NOTIFICATIONS');
