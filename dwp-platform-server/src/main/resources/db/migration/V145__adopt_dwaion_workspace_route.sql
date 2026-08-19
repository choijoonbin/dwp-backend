UPDATE adm_navigation_items
   SET route = '/dwaion',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE navigation_key = 'ask'
   AND route = '/ask';

UPDATE adm_navigation_labels AS label
   SET label = 'DWAI·ON',
       description = CASE label.locale
           WHEN 'ko' THEN '권한 범위의 업무 맥락과 근거를 연결하는 AI'
           ELSE 'Permission-aware AI connecting work context with evidence'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM adm_navigation_items AS item
 WHERE item.tenant_id = label.tenant_id
   AND item.navigation_item_id = label.navigation_item_id
   AND item.navigation_key = 'ask';

UPDATE adm_workspace_apps
   SET launch_target = '/dwaion',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1,
       version = version + 1
 WHERE app_key = 'dwp-ask'
   AND launch_target = '/ask';

UPDATE wrk_activity_events
   SET source_route = '/dwaion'
 WHERE source_route = '/ask';
