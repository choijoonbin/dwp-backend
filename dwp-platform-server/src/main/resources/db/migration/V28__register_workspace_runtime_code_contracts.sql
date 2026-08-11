-- Register the workspace runtime machine values introduced in V26.
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.WORK_ITEM.WORK_TYPE', 'dwp-platform-server',
     'Workspace work type', 'Stable type used to classify actionable workspace work.',
     'SYSTEM', 'CHECK', 'wrk_items.work_type', 'REFERENCE'),
    ('PLATFORM.WORK_ITEM.PRIORITY', 'dwp-platform-server',
     'Workspace work priority', 'Priority used to order actionable workspace work.',
     'SYSTEM', 'CHECK', 'wrk_items.priority', 'REFERENCE'),
    ('PLATFORM.WORK_ITEM.LIFECYCLE_STATE', 'dwp-platform-server',
     'Workspace work lifecycle', 'Execution state of an actionable workspace work item.',
     'SYSTEM', 'CHECK', 'wrk_items.lifecycle_state', 'STATE_MACHINE'),
    ('PLATFORM.WORKSPACE_ACTIVITY.ACTOR_KIND', 'dwp-platform-server',
     'Workspace activity actor kind', 'Actor category responsible for a workspace activity event.',
     'SYSTEM', 'CHECK', 'wrk_activity_events.actor_kind', 'OBSERVABILITY'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE', 'dwp-platform-server',
     'Workspace activity event state', 'Outcome or current state of a workspace activity event.',
     'SYSTEM', 'CHECK', 'wrk_activity_events.event_state', 'STATE_MACHINE'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'dwp-platform-server',
     'Workspace application category', 'Catalog category used to group an assigned workspace application.',
     'SYSTEM', 'CHECK', 'adm_workspace_apps.category', 'REFERENCE'),
    ('PLATFORM.WORKSPACE_APP.LAUNCH_MODE', 'dwp-platform-server',
     'Workspace application launch mode', 'Governed launch mechanism for an assigned workspace application.',
     'SYSTEM', 'CHECK', 'adm_workspace_apps.launch_mode', 'PROTOCOL'),
    ('PLATFORM.WORKSPACE_APP.HEALTH_STATE', 'dwp-platform-server',
     'Workspace application health state', 'Connection and operational readiness of a workspace application.',
     'SYSTEM', 'CHECK', 'adm_workspace_apps.health_state', 'OBSERVABILITY'),
    ('PLATFORM.WORKSPACE_APP.LIFECYCLE_STATE', 'dwp-platform-server',
     'Workspace application lifecycle', 'Availability lifecycle of an assigned workspace application.',
     'SYSTEM', 'CHECK', 'adm_workspace_apps.lifecycle_state', 'STATE_MACHINE')
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.WORK_ITEM.WORK_TYPE', 'APPROVAL', 'Approval', '{"ko":"승인","en":"Approval"}', 10, '{}'),
    ('PLATFORM.WORK_ITEM.WORK_TYPE', 'TASK', 'Task', '{"ko":"작업","en":"Task"}', 20, '{}'),
    ('PLATFORM.WORK_ITEM.WORK_TYPE', 'SERVICE', 'Service', '{"ko":"서비스","en":"Service"}', 30, '{}'),
    ('PLATFORM.WORK_ITEM.WORK_TYPE', 'REQUIRED', 'Required', '{"ko":"필수","en":"Required"}', 40, '{}'),
    ('PLATFORM.WORK_ITEM.PRIORITY', 'HIGH', 'High', '{"ko":"높음","en":"High"}', 10, '{}'),
    ('PLATFORM.WORK_ITEM.PRIORITY', 'MEDIUM', 'Medium', '{"ko":"보통","en":"Medium"}', 20, '{}'),
    ('PLATFORM.WORK_ITEM.PRIORITY', 'LOW', 'Low', '{"ko":"낮음","en":"Low"}', 30, '{}'),
    ('PLATFORM.WORK_ITEM.LIFECYCLE_STATE', 'DUE_SOON', 'Due soon', '{"ko":"마감 임박","en":"Due soon"}', 10, '{}'),
    ('PLATFORM.WORK_ITEM.LIFECYCLE_STATE', 'IN_PROGRESS', 'In progress', '{"ko":"진행 중","en":"In progress"}', 20, '{}'),
    ('PLATFORM.WORK_ITEM.LIFECYCLE_STATE', 'WAITING', 'Waiting', '{"ko":"대기","en":"Waiting"}', 30, '{}'),
    ('PLATFORM.WORK_ITEM.LIFECYCLE_STATE', 'COMPLETED', 'Completed', '{"ko":"완료","en":"Completed"}', 40, '{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.ACTOR_KIND', 'AGENT', 'Agent', '{"ko":"에이전트","en":"Agent"}', 10, '{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.ACTOR_KIND', 'PERSON', 'Person', '{"ko":"사용자","en":"Person"}', 20, '{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.ACTOR_KIND', 'SYSTEM', 'System', '{"ko":"시스템","en":"System"}', 30, '{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE', 'RUNNING', 'Running', '{"ko":"실행 중","en":"Running"}', 10, '{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE', 'NEEDS_INPUT', 'Needs input', '{"ko":"입력 필요","en":"Needs input"}', 20, '{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE', 'COMPLETED', 'Completed', '{"ko":"완료","en":"Completed"}', 30, '{}'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE', 'POLICY_BLOCKED', 'Policy blocked', '{"ko":"정책 차단","en":"Policy blocked"}', 40, '{}'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'PRODUCTIVITY', 'Productivity', '{"ko":"생산성","en":"Productivity"}', 10, '{}'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'SERVICE', 'Service', '{"ko":"서비스","en":"Service"}', 20, '{}'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'PEOPLE', 'People', '{"ko":"구성원","en":"People"}', 30, '{}'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'KNOWLEDGE', 'Knowledge', '{"ko":"지식","en":"Knowledge"}', 40, '{}'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'BUSINESS', 'Business', '{"ko":"비즈니스","en":"Business"}', 50, '{}'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'LEGACY', 'Legacy', '{"ko":"레거시","en":"Legacy"}', 60, '{}'),
    ('PLATFORM.WORKSPACE_APP.LAUNCH_MODE', 'NATIVE', 'Native', '{"ko":"내장","en":"Native"}', 10, '{}'),
    ('PLATFORM.WORKSPACE_APP.LAUNCH_MODE', 'SSO', 'Single sign-on', '{"ko":"통합 인증","en":"Single sign-on"}', 20, '{}'),
    ('PLATFORM.WORKSPACE_APP.LAUNCH_MODE', 'DEEP_LINK', 'Deep link', '{"ko":"딥 링크","en":"Deep link"}', 30, '{}'),
    ('PLATFORM.WORKSPACE_APP.HEALTH_STATE', 'HEALTHY', 'Healthy', '{"ko":"정상","en":"Healthy"}', 10, '{}'),
    ('PLATFORM.WORKSPACE_APP.HEALTH_STATE', 'MANAGED', 'Managed', '{"ko":"관리됨","en":"Managed"}', 20, '{}'),
    ('PLATFORM.WORKSPACE_APP.HEALTH_STATE', 'ATTENTION', 'Attention', '{"ko":"확인 필요","en":"Attention"}', 30, '{}'),
    ('PLATFORM.WORKSPACE_APP.HEALTH_STATE', 'CONFIGURATION_REQUIRED', 'Configuration required', '{"ko":"설정 필요","en":"Configuration required"}', 40, '{}'),
    ('PLATFORM.WORKSPACE_APP.LIFECYCLE_STATE', 'ACTIVE', 'Active', '{"ko":"활성","en":"Active"}', 10, '{}'),
    ('PLATFORM.WORKSPACE_APP.LIFECYCLE_STATE', 'RETIRED', 'Retired', '{"ko":"종료","en":"Retired"}', 20, '{}')
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.WORK_ITEM.WORK_TYPE', 'dwp-platform-server', 'DATABASE_COLUMN', 'wrk_items.work_type', 'CHECK'),
    ('PLATFORM.WORK_ITEM.PRIORITY', 'dwp-platform-server', 'DATABASE_COLUMN', 'wrk_items.priority', 'CHECK'),
    ('PLATFORM.WORK_ITEM.LIFECYCLE_STATE', 'dwp-platform-server', 'DATABASE_COLUMN', 'wrk_items.lifecycle_state', 'CHECK'),
    ('PLATFORM.WORKSPACE_ACTIVITY.ACTOR_KIND', 'dwp-platform-server', 'DATABASE_COLUMN', 'wrk_activity_events.actor_kind', 'CHECK'),
    ('PLATFORM.WORKSPACE_ACTIVITY.EVENT_STATE', 'dwp-platform-server', 'DATABASE_COLUMN', 'wrk_activity_events.event_state', 'CHECK'),
    ('PLATFORM.WORKSPACE_APP.CATEGORY', 'dwp-platform-server', 'DATABASE_COLUMN', 'adm_workspace_apps.category', 'CHECK'),
    ('PLATFORM.WORKSPACE_APP.LAUNCH_MODE', 'dwp-platform-server', 'DATABASE_COLUMN', 'adm_workspace_apps.launch_mode', 'CHECK'),
    ('PLATFORM.WORKSPACE_APP.HEALTH_STATE', 'dwp-platform-server', 'DATABASE_COLUMN', 'adm_workspace_apps.health_state', 'CHECK'),
    ('PLATFORM.WORKSPACE_APP.LIFECYCLE_STATE', 'dwp-platform-server', 'DATABASE_COLUMN', 'adm_workspace_apps.lifecycle_state', 'CHECK')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;
