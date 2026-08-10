INSERT INTO adm_navigation_items (
    tenant_id,
    navigation_key,
    item_type,
    required_permission_code,
    sort_order,
    lifecycle_state,
    created_by,
    updated_by)
VALUES (1, 'workspace', 'GROUP', 'VIEW', 10, 'ACTIVE', 1, 1)
ON CONFLICT (tenant_id, navigation_key) DO NOTHING;

INSERT INTO adm_navigation_items (
    tenant_id,
    navigation_key,
    item_type,
    parent_navigation_item_id,
    registry_entry_key,
    route,
    icon_key,
    required_resource_key,
    required_permission_code,
    sort_order,
    lifecycle_state,
    created_by,
    updated_by)
SELECT
    1,
    seed.navigation_key,
    'APP',
    parent.navigation_item_id,
    seed.registry_entry_key,
    seed.route,
    seed.icon_key,
    seed.resource_key,
    'VIEW',
    seed.sort_order,
    'ACTIVE',
    1,
    1
FROM adm_navigation_items parent
CROSS JOIN (VALUES
    ('work', 'dwp-work', '/work', 'work', 'APP.WORK', 10),
    ('ask', 'dwp-ask', '/ask', 'ask', 'APP.ASK', 20),
    ('activity', 'dwp-activity', '/activity', 'activity', 'APP.ACTIVITY', 30),
    ('apps', 'dwp-apps', '/apps', 'apps', 'APP.APPS', 40)
) AS seed(navigation_key, registry_entry_key, route, icon_key, resource_key, sort_order)
WHERE parent.tenant_id = 1
  AND parent.navigation_key = 'workspace'
ON CONFLICT (tenant_id, navigation_key) DO NOTHING;

INSERT INTO adm_navigation_labels (
    tenant_id,
    navigation_item_id,
    locale,
    label,
    description,
    created_by,
    updated_by)
SELECT
    item.tenant_id,
    item.navigation_item_id,
    seed.locale,
    seed.label,
    seed.description,
    1,
    1
FROM adm_navigation_items item
JOIN (VALUES
    ('workspace', 'en', 'Workspace', 'Everyday work applications'),
    ('workspace', 'ko', '업무', '일상 업무 애플리케이션'),
    ('work', 'en', 'Work', 'Priorities, approvals, and tasks'),
    ('work', 'ko', '업무', '우선순위, 승인, 할 일'),
    ('ask', 'en', 'Ask DWP', 'Grounded answers and governed actions'),
    ('ask', 'ko', 'DWP에게 묻기', '근거 기반 답변과 통제된 작업'),
    ('activity', 'en', 'Activity', 'Human, system, and agent events'),
    ('activity', 'ko', '활동', '사용자, 시스템, 에이전트 이벤트'),
    ('apps', 'en', 'Apps', 'Available workplace applications'),
    ('apps', 'ko', '앱', '사용 가능한 워크플레이스 앱')
) AS seed(navigation_key, locale, label, description)
  ON seed.navigation_key = item.navigation_key
WHERE item.tenant_id = 1
ON CONFLICT (tenant_id, navigation_item_id, locale) DO UPDATE
SET label = EXCLUDED.label,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
