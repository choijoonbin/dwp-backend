UPDATE adm_registry_entries
   SET name = CASE registry_type
           WHEN 'AGENT' THEN 'DWAI·ON'
           ELSE 'DWAI·ON Workspace'
       END,
       description = CASE registry_type
           WHEN 'AGENT' THEN 'Governed workplace AI with permission-scoped evidence and citation validation'
           ELSE 'AI workspace with evidence, sources, and an audit trace'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1,
       version = version + 1
 WHERE (registry_type = 'APP' AND entry_key = 'DWP_ASK')
    OR (registry_type = 'AGENT' AND entry_key = 'DWP_ASSISTANT');

UPDATE adm_navigation_labels AS label
   SET label = CASE label.locale
           WHEN 'ko' THEN 'DWAI·ON 워크스페이스'
           ELSE 'DWAI·ON Workspace'
       END,
       description = CASE label.locale
           WHEN 'ko' THEN '권한 범위의 업무 근거, 출처 및 감사 증적'
           ELSE 'Permission-scoped work evidence, sources, and audit evidence'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM adm_navigation_items AS item
 WHERE item.tenant_id = label.tenant_id
   AND item.navigation_item_id = label.navigation_item_id
   AND item.navigation_key = 'ask';

UPDATE adm_workspace_apps
   SET name_ko = 'DWAI·ON 워크스페이스',
       name_en = 'DWAI·ON Workspace',
       description_ko = '업무 근거, 출처 및 감사 증적을 함께 확인하는 AI 작업공간입니다.',
       description_en = 'AI workspace for work evidence, sources, and audit evidence.',
       owner_name = 'DWP AI Platform',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1,
       version = version + 1
 WHERE app_key = 'dwp-ask';

UPDATE wrk_activity_events
   SET source_system = 'DWAI·ON'
 WHERE source_system = 'Ask DWP';
