INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-rooms', '회의실', 'Rooms',
       '실시간 가용성을 확인하고 회의실 예약, 참석자 초대와 운영 승인을 관리합니다.',
       'Find live availability and manage room bookings, invitations, and operational approvals.',
       'DWP Workplace', 'PRODUCTIVITY', 'NATIVE', '/rooms/find',
       'rooms', 'APP.ROOMS', 'HEALTHY', 39, 'ACTIVE', 1, 1
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
               || '[{"resourceKey":"APP.ROOMS","groupKey":"connect","sortOrder":38}]'::jsonb),
       version = experience.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE NOT EXISTS (
       SELECT 1
         FROM jsonb_array_elements(
              experience.launchpad_configuration -> 'placements') placement
        WHERE placement ->> 'resourceKey' = 'APP.ROOMS');
