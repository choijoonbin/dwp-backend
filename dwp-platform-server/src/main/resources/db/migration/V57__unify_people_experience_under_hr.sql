UPDATE adm_registry_entries
   SET entry_key = 'DWP_HRIS',
       name = 'HR',
       description = 'Personal HR, people, organization, and governed workforce operations',
       owner_ref = 'people:hris',
       risk_tier = 'MEDIUM',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE registry_type = 'APP'
   AND entry_key = 'DWP_PEOPLE';

UPDATE adm_registry_entries
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE registry_type = 'APP'
   AND entry_key = 'DWP_WORKFORCE'
   AND lifecycle_state <> 'RETIRED';

UPDATE adm_navigation_items
   SET navigation_key = 'hris',
       registry_entry_key = 'DWP_HRIS',
       route = '/hr',
       icon_key = 'hris',
       required_resource_key = 'APP.HRIS',
       sort_order = 50,
       lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE navigation_key = 'people';

UPDATE adm_navigation_items
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE navigation_key = 'workforce'
   AND lifecycle_state <> 'RETIRED';

UPDATE adm_navigation_labels label
   SET label = CASE label.locale WHEN 'ko' THEN '인사' ELSE 'HR' END,
       description = CASE label.locale
           WHEN 'ko' THEN '나의 인사, 구성원, 조직 및 권한에 따른 인력 운영'
           ELSE 'Personal HR, people, organization, and role-aware workforce operations'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM adm_navigation_items item
 WHERE item.tenant_id = label.tenant_id
   AND item.navigation_item_id = label.navigation_item_id
   AND item.navigation_key = 'hris';

UPDATE adm_workspace_apps
   SET name_ko = '인사',
       name_en = 'HR',
       description_ko = '나의 인사, 구성원, 조직 및 권한에 따른 인력 운영을 제공합니다.',
       description_en = 'Personal HR, people, organization, and role-aware workforce operations.',
       owner_name = 'People Platform',
       launch_target = '/hr',
       icon_key = 'hris',
       resource_key = 'APP.HRIS',
       health_state = 'HEALTHY',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'ref-app-people';

UPDATE adm_workspace_apps
   SET name_ko = '워크플레이스 서비스',
       name_en = 'Workplace services',
       description_ko = 'IT 지원, 업무 공간 접근 및 공용 서비스 요청을 처리합니다.',
       description_en = 'Handle IT help, workplace access, and shared-service requests.',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'ref-app-service';

UPDATE wrk_items
   SET source_route = CASE source_route
       WHEN '/people' THEN '/hr/home'
       WHEN '/people/directory' THEN '/hr/directory'
       WHEN '/people/organization' THEN '/hr/organization'
       WHEN '/workforce' THEN '/hr/operations'
       WHEN '/workforce/overview' THEN '/hr/operations'
       WHEN '/workforce/people' THEN '/hr/operations/people'
       WHEN '/workforce/assignments' THEN '/hr/operations/assignments'
       WHEN '/workforce/organization' THEN '/hr/design/organization'
       WHEN '/workforce/reference-data' THEN '/hr/data/reference'
       WHEN '/workforce/data-operations' THEN '/hr/data/integrations'
       WHEN '/workforce/exports' THEN '/hr/data/exports'
       ELSE source_route
   END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE source_route = '/people'
    OR source_route LIKE '/people/%'
    OR source_route = '/workforce'
    OR source_route LIKE '/workforce/%';

UPDATE wrk_activity_events
   SET source_route = CASE source_route
       WHEN '/people' THEN '/hr/home'
       WHEN '/people/directory' THEN '/hr/directory'
       WHEN '/people/organization' THEN '/hr/organization'
       WHEN '/workforce' THEN '/hr/operations'
       WHEN '/workforce/overview' THEN '/hr/operations'
       WHEN '/workforce/people' THEN '/hr/operations/people'
       WHEN '/workforce/assignments' THEN '/hr/operations/assignments'
       WHEN '/workforce/organization' THEN '/hr/design/organization'
       WHEN '/workforce/reference-data' THEN '/hr/data/reference'
       WHEN '/workforce/data-operations' THEN '/hr/data/integrations'
       WHEN '/workforce/exports' THEN '/hr/data/exports'
       ELSE source_route
   END
 WHERE source_route = '/people'
    OR source_route LIKE '/people/%'
    OR source_route = '/workforce'
    OR source_route LIKE '/workforce/%';
