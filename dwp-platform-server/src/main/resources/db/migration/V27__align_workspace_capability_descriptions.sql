UPDATE adm_registry_entries
   SET description = 'Read-only request plans with an audit trace',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE registry_type = 'APP'
   AND entry_key = 'DWP_ASK';

UPDATE adm_navigation_labels AS label
   SET description = CASE label.locale
           WHEN 'ko' THEN '감사 추적이 포함된 읽기 전용 요청 계획'
           ELSE 'Read-only request plans with an audit trace'
       END,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM adm_navigation_items AS item
 WHERE item.tenant_id = label.tenant_id
   AND item.navigation_item_id = label.navigation_item_id
   AND item.navigation_key = 'ask';

UPDATE adm_workspace_apps
   SET description_ko = '감사 추적이 포함된 읽기 전용 요청 계획을 준비합니다.',
       description_en = 'Prepare read-only request plans with an audit trace.',
       owner_name = 'DWP Platform',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1,
       version = version + 1
 WHERE app_key = 'dwp-ask';

UPDATE adm_workspace_apps
   SET description_ko = '정책 및 업무 가이드 연결을 구성합니다.',
       description_en = 'Configure connections to policies and workplace guides.',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1,
       version = version + 1
 WHERE app_key = 'ref-app-knowledge';

UPDATE wrk_activity_events
   SET event_state = 'COMPLETED',
       title_ko = '참조 요청 계획 준비됨',
       title_en = 'Reference request plan prepared',
       summary_ko = '읽기 전용 계획 계약과 감사 추적 정보가 생성되었습니다.',
       summary_en = 'A read-only plan contract and audit trace were generated.',
       object_type = 'PLAN_PREVIEW',
       object_label_ko = '요청 계획',
       object_label_en = 'Request plan',
       tool_name = 'Reference planner',
       progress = 100
 WHERE audit_reference = 'AUD-WRK-901'
   AND source_system = 'Ask DWP';
