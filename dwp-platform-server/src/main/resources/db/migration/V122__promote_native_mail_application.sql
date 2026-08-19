UPDATE adm_workspace_apps
   SET name_ko = '메일',
       name_en = 'Mail',
       description_ko = '중요 메일을 선별하고 회신, 일정, 업무와 협업 후속 조치를 연결합니다.',
       description_en = 'Triage important mail and connect replies, schedules, work, and collaborative follow-ups.',
       owner_name = 'DWP Workplace',
       category = 'PRODUCTIVITY',
       launch_mode = 'NATIVE',
       launch_target = '/mail/home',
       icon_key = 'mail',
       resource_key = 'APP.MAIL',
       health_state = 'HEALTHY',
       lifecycle_state = 'ACTIVE',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'ref-app-mail';

UPDATE adm_home_experiences
   SET launchpad_configuration = REPLACE(
           launchpad_configuration::text,
           'APP.MAIL_CALENDAR',
           'APP.MAIL')::jsonb,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE launchpad_configuration::text LIKE '%APP.MAIL_CALENDAR%';

COMMENT ON TABLE mail_threads IS
    'Provider-neutral conversation projection owned by the DWP Mail bounded context.';
COMMENT ON COLUMN mail_threads.triage_lane IS
    'User-facing split inbox classification. Provider folders remain independently represented by mail_folders.';
