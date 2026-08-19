UPDATE spc_app_bindings
   SET launch_target = '/dwaion',
       display_name_ko = 'DWAI·ON 워크스페이스',
       display_name_en = 'DWAI·ON Workspace',
       updated_at = CURRENT_TIMESTAMP
 WHERE app_key = 'ask'
   AND launch_target = '/ask';
