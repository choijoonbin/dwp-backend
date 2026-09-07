UPDATE adm_workspace_apps
   SET launch_target = '/workplace/home',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'dwp-rooms'
   AND launch_mode = 'NATIVE'
   AND launch_target IN ('/workplace/explore', '/rooms/find');
