UPDATE adm_registry_entries
   SET name = 'HRIS',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE registry_type = 'APP'
   AND entry_key = 'DWP_HRIS'
   AND name <> 'HRIS';

UPDATE adm_navigation_labels label
   SET label = 'HRIS',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM adm_navigation_items item
 WHERE item.tenant_id = label.tenant_id
   AND item.navigation_item_id = label.navigation_item_id
   AND item.navigation_key = 'hris'
   AND label.label <> 'HRIS';

UPDATE adm_workspace_apps
   SET name_ko = 'HRIS',
       name_en = 'HRIS',
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'ref-app-people'
   AND (name_ko <> 'HRIS' OR name_en <> 'HRIS');
