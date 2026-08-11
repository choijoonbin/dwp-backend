INSERT INTO adm_registry_entries (
    tenant_id, registry_type, entry_key, revision, name, description,
    owner_ref, risk_tier, artifact_version, lifecycle_state, created_by, updated_by)
SELECT parent.tenant_id, 'APP', seed.entry_key, 1, seed.name, seed.description,
       seed.owner_ref, seed.risk_tier, '1.0.0', 'ACTIVE', 1, 1
  FROM adm_navigation_items parent
 CROSS JOIN (VALUES
    ('DWP_PEOPLE', 'People',
     'Find colleagues and explore reporting relationships',
     'people:directory', 'LOW'),
    ('DWP_WORKFORCE', 'Workforce management',
     'Govern workforce data, positions, organization design, and HRIS operations',
     'people:workforce', 'HIGH')
) AS seed(entry_key, name, description, owner_ref, risk_tier)
 WHERE parent.navigation_key = 'workspace'
ON CONFLICT (tenant_id, registry_type, entry_key, revision) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    owner_ref = EXCLUDED.owner_ref,
    risk_tier = EXCLUDED.risk_tier,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO adm_navigation_items (
    tenant_id, navigation_key, item_type, parent_navigation_item_id,
    registry_entry_key, route, icon_key, required_resource_key,
    required_permission_code, sort_order, lifecycle_state, created_by, updated_by)
SELECT parent.tenant_id, seed.navigation_key, 'APP', parent.navigation_item_id,
       seed.registry_key, seed.route, seed.icon_key, seed.resource_key,
       'VIEW', seed.sort_order, 'ACTIVE', 1, 1
  FROM adm_navigation_items parent
 CROSS JOIN (VALUES
    ('people', 'DWP_PEOPLE', '/people', 'people', 'APP.PEOPLE_DIRECTORY', 50),
    ('workforce', 'DWP_WORKFORCE', '/workforce', 'workforce', 'APP.WORKFORCE_MANAGEMENT', 60)
) AS seed(navigation_key, registry_key, route, icon_key, resource_key, sort_order)
 WHERE parent.navigation_key = 'workspace'
ON CONFLICT (tenant_id, navigation_key) DO UPDATE SET
    parent_navigation_item_id = EXCLUDED.parent_navigation_item_id,
    registry_entry_key = EXCLUDED.registry_entry_key,
    route = EXCLUDED.route,
    icon_key = EXCLUDED.icon_key,
    required_resource_key = EXCLUDED.required_resource_key,
    required_permission_code = EXCLUDED.required_permission_code,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

INSERT INTO adm_navigation_labels (
    tenant_id, navigation_item_id, locale, label, description, created_by, updated_by)
SELECT item.tenant_id, item.navigation_item_id, seed.locale,
       seed.label, seed.description, 1, 1
  FROM adm_navigation_items item
  JOIN (VALUES
    ('people', 'en', 'People', 'Find colleagues and explore reporting relationships'),
    ('people', 'ko', '구성원', '동료를 찾고 보고 체계를 탐색합니다'),
    ('workforce', 'en', 'Workforce management', 'Workforce, positions, organization design, and HRIS operations'),
    ('workforce', 'ko', '인력 운영', '인력, 포지션, 조직 설계 및 HRIS 데이터 운영')
) AS seed(navigation_key, locale, label, description)
    ON seed.navigation_key = item.navigation_key
ON CONFLICT (tenant_id, navigation_item_id, locale) DO UPDATE SET
    label = EXCLUDED.label,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
