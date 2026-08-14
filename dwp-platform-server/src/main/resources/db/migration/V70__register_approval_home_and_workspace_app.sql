INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    health_state, sort_order, lifecycle_state, created_by, updated_by)
SELECT tenant_id, 'dwp-approvals', '전자결재', 'Approvals',
       '결재 요청, 의사결정, 위임 및 처리 상태를 한곳에서 관리합니다.',
       'Manage requests, decisions, delegation, and approval health in one place.',
       'DWP Decision Hub', 'BUSINESS', 'NATIVE', '/approvals/home',
       'approvals', 'APP.APPROVALS', 'HEALTHY', 39, 'ACTIVE', 1, 1
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

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'dwp-platform-server', 'Approval home widget',
     'Permission-aware widgets accepted by the approval decision hub home.',
     'SYSTEM', 'TYPED_CONTRACT',
     'HomePreferenceService.SURFACE_CONTRACTS[approval-home]', 'REFERENCE')
ON CONFLICT (code_set_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.HOME_SURFACE', 'approval-home', 'Approval home',
     '{"ko":"전자결재 홈","en":"Approval home"}', 30,
     '{"personalizable":true,"permissionAware":true}'),
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'decision-pulse', 'Decision pulse',
     '{"ko":"결재 현황","en":"Decision pulse"}', 10,
     '{"defaultSize":"full","allowedSizes":["full"],"canHide":false}'),
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'focus-queue', 'Focus queue',
     '{"ko":"우선 결재함","en":"Focus queue"}', 20,
     '{"defaultSize":"large","allowedSizes":["medium","large","full"]}'),
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'flow', 'Approval flow',
     '{"ko":"결재 흐름","en":"Approval flow"}', 30,
     '{"defaultSize":"medium","allowedSizes":["medium","large","full"]}'),
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'my-requests', 'My requests',
     '{"ko":"내가 올린 결재","en":"My requests"}', 40,
     '{"defaultSize":"medium","allowedSizes":["medium","large","full"]}'),
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'insights', 'Decision insights',
     '{"ko":"의사결정 인사이트","en":"Decision insights"}', 50,
     '{"defaultSize":"medium","allowedSizes":["compact","medium","large"]}'),
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'admin-health', 'Approval operations health',
     '{"ko":"결재 운영 상태","en":"Approval operations health"}', 60,
     '{"defaultSize":"full","allowedSizes":["large","full"],"audience":"administrator"}')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceService.SURFACE_CONTRACTS[approval-home]', 'TYPED_CONTRACT'),
    ('PLATFORM.APPROVAL_HOME_WIDGET', 'dwp-frontend', 'BEHAVIOR',
     'approvals/approval-home-widget-registry', 'CATALOG_LOOKUP')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;
