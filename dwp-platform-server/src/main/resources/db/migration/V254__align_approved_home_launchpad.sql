-- Expand the governed launchpad to the approved 18 applications. Canonical applications are
-- repaired in place while tenant-specific groups, placements and extension fields are retained.

ALTER TABLE adm_workspace_apps
    ADD COLUMN IF NOT EXISTS required_permission_code VARCHAR(50) NOT NULL DEFAULT 'VIEW',
    ADD COLUMN IF NOT EXISTS badge_source_key VARCHAR(80);
ALTER TABLE adm_workspace_apps
    DROP CONSTRAINT IF EXISTS ck_adm_workspace_apps_permission;
ALTER TABLE adm_workspace_apps
    ADD CONSTRAINT ck_adm_workspace_apps_permission
        CHECK (required_permission_code ~ '^[A-Z][A-Z0-9_.-]{0,49}$');

ALTER TABLE adm_home_experiences
    ALTER COLUMN launchpad_configuration SET DEFAULT
    '{
      "schemaVersion":1,
      "groups":[
        {"groupKey":"work","labels":{"ko":"업무 시작","en":"Start work"},"descriptions":{"ko":"우선순위와 AI 지원 실행","en":"Priorities and AI-assisted action"},"sortOrder":10,"enabled":true},
        {"groupKey":"connect","labels":{"ko":"소통과 협업","en":"Connect and collaborate"},"descriptions":{"ko":"커뮤니케이션과 공동 작업","en":"Communication and shared work"},"sortOrder":20,"enabled":true},
        {"groupKey":"services","labels":{"ko":"구성원과 서비스","en":"People and services"},"descriptions":{"ko":"임직원 지원 및 인물 정보","en":"Employee support and people information"},"sortOrder":30,"enabled":true},
        {"groupKey":"systems","labels":{"ko":"시스템과 통제","en":"Systems and control"},"descriptions":{"ko":"지식, 업무 도구 및 거버넌스","en":"Knowledge, business tools, and governance"},"sortOrder":40,"enabled":true}
      ],
      "placements":[
        {"resourceKey":"APP.WORK","groupKey":"work","sortOrder":10},
        {"resourceKey":"APP.ASK","groupKey":"work","sortOrder":20},
        {"resourceKey":"APP.ACTIVITY","groupKey":"work","sortOrder":30},
        {"resourceKey":"APP.APPROVALS","groupKey":"work","sortOrder":40},
        {"resourceKey":"APP.NOTIFICATIONS","groupKey":"work","sortOrder":50},
        {"resourceKey":"APP.COMMUNICATIONS","groupKey":"connect","sortOrder":10},
        {"resourceKey":"APP.CALENDAR","groupKey":"connect","sortOrder":20},
        {"resourceKey":"APP.MAIL","groupKey":"connect","sortOrder":30},
        {"resourceKey":"APP.SPACES","groupKey":"connect","sortOrder":40},
        {"resourceKey":"APP.WORKPLACE","groupKey":"connect","sortOrder":50},
        {"resourceKey":"APP.MESSAGING","groupKey":"connect","sortOrder":60},
        {"resourceKey":"APP.MEETINGS","groupKey":"connect","sortOrder":70},
        {"resourceKey":"APP.EMPLOYEE_SERVICES","groupKey":"services","sortOrder":10},
        {"resourceKey":"APP.HCM","groupKey":"services","sortOrder":20},
        {"resourceKey":"APP.KNOWLEDGE","groupKey":"systems","sortOrder":10},
        {"resourceKey":"APP.BUSINESS_ERP","groupKey":"systems","sortOrder":20},
        {"resourceKey":"APP.LEGACY_OPERATIONS","groupKey":"systems","sortOrder":30},
        {"resourceKey":"APP.ADMINISTRATION","groupKey":"systems","sortOrder":40}
      ]
    }'::jsonb;

WITH repaired AS (
    SELECT experience.tenant_id,
           (experience.launchpad_configuration - 'schemaVersion' - 'groups' - 'placements')
           || jsonb_build_object(
               'schemaVersion', 1,
               'groups', (
                   SELECT jsonb_agg(candidate.value ORDER BY candidate.position)
                     FROM (
                         VALUES
                           (10, '{"groupKey":"work","labels":{"ko":"업무 시작","en":"Start work"},"descriptions":{"ko":"우선순위와 AI 지원 실행","en":"Priorities and AI-assisted action"},"sortOrder":10,"enabled":true}'::jsonb),
                           (20, '{"groupKey":"connect","labels":{"ko":"소통과 협업","en":"Connect and collaborate"},"descriptions":{"ko":"커뮤니케이션과 공동 작업","en":"Communication and shared work"},"sortOrder":20,"enabled":true}'::jsonb),
                           (30, '{"groupKey":"services","labels":{"ko":"구성원과 서비스","en":"People and services"},"descriptions":{"ko":"임직원 지원 및 인물 정보","en":"Employee support and people information"},"sortOrder":30,"enabled":true}'::jsonb),
                           (40, '{"groupKey":"systems","labels":{"ko":"시스템과 통제","en":"Systems and control"},"descriptions":{"ko":"지식, 업무 도구 및 거버넌스","en":"Knowledge, business tools, and governance"},"sortOrder":40,"enabled":true}'::jsonb)
                         UNION ALL
                         SELECT 10000 + custom.ordinality::integer, custom.value
                           FROM (
                               SELECT DISTINCT ON (group_value ->> 'groupKey')
                                      group_value AS value, ordinality
                                 FROM jsonb_array_elements(COALESCE(
                                      experience.launchpad_configuration -> 'groups', '[]'::jsonb))
                                      WITH ORDINALITY AS existing(group_value, ordinality)
                                WHERE group_value ->> 'groupKey' IS NOT NULL
                                  AND group_value ->> 'groupKey'
                                      NOT IN ('work', 'connect', 'services', 'systems')
                                ORDER BY group_value ->> 'groupKey', ordinality
                           ) custom
                     ) candidate(position, value)
               ),
               'placements', (
                   SELECT jsonb_agg(candidate.value ORDER BY candidate.position)
                     FROM (
                         VALUES
                           (10, '{"resourceKey":"APP.WORK","groupKey":"work","sortOrder":10}'::jsonb),
                           (20, '{"resourceKey":"APP.ASK","groupKey":"work","sortOrder":20}'::jsonb),
                           (30, '{"resourceKey":"APP.ACTIVITY","groupKey":"work","sortOrder":30}'::jsonb),
                           (40, '{"resourceKey":"APP.APPROVALS","groupKey":"work","sortOrder":40}'::jsonb),
                           (50, '{"resourceKey":"APP.NOTIFICATIONS","groupKey":"work","sortOrder":50}'::jsonb),
                           (110, '{"resourceKey":"APP.COMMUNICATIONS","groupKey":"connect","sortOrder":10}'::jsonb),
                           (120, '{"resourceKey":"APP.CALENDAR","groupKey":"connect","sortOrder":20}'::jsonb),
                           (130, '{"resourceKey":"APP.MAIL","groupKey":"connect","sortOrder":30}'::jsonb),
                           (140, '{"resourceKey":"APP.SPACES","groupKey":"connect","sortOrder":40}'::jsonb),
                           (150, '{"resourceKey":"APP.WORKPLACE","groupKey":"connect","sortOrder":50}'::jsonb),
                           (160, '{"resourceKey":"APP.MESSAGING","groupKey":"connect","sortOrder":60}'::jsonb),
                           (170, '{"resourceKey":"APP.MEETINGS","groupKey":"connect","sortOrder":70}'::jsonb),
                           (210, '{"resourceKey":"APP.EMPLOYEE_SERVICES","groupKey":"services","sortOrder":10}'::jsonb),
                           (220, '{"resourceKey":"APP.HCM","groupKey":"services","sortOrder":20}'::jsonb),
                           (310, '{"resourceKey":"APP.KNOWLEDGE","groupKey":"systems","sortOrder":10}'::jsonb),
                           (320, '{"resourceKey":"APP.BUSINESS_ERP","groupKey":"systems","sortOrder":20}'::jsonb),
                           (330, '{"resourceKey":"APP.LEGACY_OPERATIONS","groupKey":"systems","sortOrder":30}'::jsonb),
                           (340, '{"resourceKey":"APP.ADMINISTRATION","groupKey":"systems","sortOrder":40}'::jsonb)
                         UNION ALL
                         SELECT 10000 + custom.ordinality::integer,
                                jsonb_set(custom.value, '{resourceKey}', to_jsonb(custom.resource_key), true)
                           FROM (
                               SELECT DISTINCT ON (canonical_resource_key)
                                      placement_value AS value,
                                      canonical_resource_key AS resource_key,
                                      ordinality
                                 FROM (
                                     SELECT placement_value, ordinality,
                                            CASE upper(trim(placement_value ->> 'resourceKey'))
                                              WHEN 'APP.MAIL_CALENDAR' THEN 'APP.MAIL'
                                              WHEN 'APP.COLLABORATION' THEN 'APP.MESSAGING'
                                              WHEN 'APP.ROOMS' THEN 'APP.WORKPLACE'
                                              WHEN 'APP.HRIS' THEN 'APP.HCM'
                                              ELSE upper(trim(placement_value ->> 'resourceKey'))
                                            END AS canonical_resource_key
                                       FROM jsonb_array_elements(COALESCE(
                                            experience.launchpad_configuration -> 'placements', '[]'::jsonb))
                                            WITH ORDINALITY AS existing(placement_value, ordinality)
                                      WHERE placement_value ->> 'resourceKey' IS NOT NULL
                                 ) normalized
                                WHERE canonical_resource_key NOT IN (
                                      'APP.WORK','APP.ASK','APP.ACTIVITY','APP.APPROVALS','APP.NOTIFICATIONS',
                                      'APP.COMMUNICATIONS','APP.CALENDAR','APP.MAIL','APP.SPACES','APP.WORKPLACE',
                                      'APP.MESSAGING','APP.MEETINGS','APP.EMPLOYEE_SERVICES','APP.HCM',
                                      'APP.KNOWLEDGE','APP.BUSINESS_ERP','APP.LEGACY_OPERATIONS','APP.ADMINISTRATION')
                                ORDER BY canonical_resource_key, ordinality
                           ) custom
                     ) candidate(position, value)
               )) AS configuration
      FROM adm_home_experiences experience
)
UPDATE adm_home_experiences experience
   SET launchpad_configuration = repaired.configuration,
       version = experience.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM repaired
 WHERE repaired.tenant_id = experience.tenant_id
   AND experience.launchpad_configuration IS DISTINCT FROM repaired.configuration;

-- Align the workspace catalog for existing tenants. Authorization continues to be enforced by
-- each APP.* VIEW resource; the catalog does not grant access by itself.
INSERT INTO adm_workspace_apps (
    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
    required_permission_code, badge_source_key, health_state, sort_order,
    lifecycle_state, created_by, updated_by)
SELECT tenant.tenant_id, app.app_key, app.name_ko, app.name_en,
       app.name_ko || ' 업무를 DWP 홈에서 안전하게 시작합니다.',
       'Launch ' || app.name_en || ' safely from DWP Home.',
       app.owner_name, app.category, app.launch_mode, app.launch_target,
       app.icon_key, app.resource_key, app.required_permission_code,
       app.badge_source_key, app.health_state, app.sort_order,
       'ACTIVE', 1, 1
  FROM sys_service_tenants tenant
 CROSS JOIN (VALUES
    ('dwp-work','업무','Work','DWP Platform','PRODUCTIVITY','NATIVE','/work','work','APP.WORK','VIEW',NULL,'HEALTHY',10),
    ('dwp-ask','DWAI·ON','DWAI·ON','DWP AI Platform','KNOWLEDGE','NATIVE','/dwaion','ask','APP.ASK','VIEW',NULL,'MANAGED',20),
    ('dwp-activity','활동','Activity','DWP Platform','PRODUCTIVITY','NATIVE','/activity','activity','APP.ACTIVITY','VIEW',NULL,'HEALTHY',30),
    ('dwp-approvals','전자결재','Approvals','DWP Decision Hub','BUSINESS','NATIVE','/approvals/home','approvals','APP.APPROVALS','VIEW','approvals','HEALTHY',40),
    ('dwp-notifications','알림','Notifications','DWP Platform','PRODUCTIVITY','NATIVE','/notifications/home','notifications','APP.NOTIFICATIONS','VIEW','notifications','HEALTHY',50),
    ('dwp-communications','소식','Newsroom','DWP Communications','PRODUCTIVITY','NATIVE','/communications','communications','APP.COMMUNICATIONS','VIEW','communications','HEALTHY',110),
    ('dwp-calendar','캘린더','Calendar','DWP Workplace','PRODUCTIVITY','NATIVE','/calendar/home','calendar','APP.CALENDAR','VIEW',NULL,'HEALTHY',120),
    ('ref-app-mail','메일','Mail','DWP Workplace','PRODUCTIVITY','NATIVE','/mail/home','mail','APP.MAIL','VIEW',NULL,'HEALTHY',130),
    ('dwp-spaces','Space','Spaces','DWP Collaboration Platform','PRODUCTIVITY','NATIVE','/spaces/home','spaces','APP.SPACES','VIEW','space','HEALTHY',140),
    ('dwp-rooms','근무 공간','Workplace','DWP Workplace','PRODUCTIVITY','NATIVE','/workplace/home','rooms','APP.WORKPLACE','VIEW',NULL,'HEALTHY',150),
    ('dwp-messaging','메신저','Messaging','DWP Collaboration Platform','PRODUCTIVITY','NATIVE','/messages/home','messaging','APP.MESSAGING','VIEW','messaging','HEALTHY',160),
    ('dwp-meetings','화상회의','Meetings','DWP Meeting Platform','PRODUCTIVITY','NATIVE','/meetings/home','meetings','APP.MEETINGS','VIEW',NULL,'HEALTHY',170),
    ('ref-app-service','서비스','Services','Shared Services','SERVICE','NATIVE','/services','services','APP.EMPLOYEE_SERVICES','VIEW',NULL,'HEALTHY',210),
    ('ref-app-people','인사','HR','DWP HCM','PEOPLE','NATIVE','/hr','hcm','APP.HCM','VIEW','hcm','HEALTHY',220),
    ('ref-app-knowledge','지식','Knowledge','Knowledge Office','KNOWLEDGE','DEEP_LINK','/apps?app=ref-app-knowledge','knowledge','APP.KNOWLEDGE','VIEW',NULL,'CONFIGURATION_REQUIRED',310),
    ('ref-app-erp','ERP','Business ERP','Finance Platform','BUSINESS','DEEP_LINK','/apps?app=ref-app-erp','erp','APP.BUSINESS_ERP','VIEW',NULL,'CONFIGURATION_REQUIRED',320),
    ('ref-app-legacy','레거시','Legacy operations','Enterprise Systems','LEGACY','DEEP_LINK','/apps?app=ref-app-legacy','legacy','APP.LEGACY_OPERATIONS','VIEW',NULL,'CONFIGURATION_REQUIRED',330),
    ('dwp-admin','관리','Administration','DWP Platform','BUSINESS','NATIVE','/admin','admin','APP.ADMINISTRATION','VIEW',NULL,'HEALTHY',340)
 ) AS app(app_key,name_ko,name_en,owner_name,category,launch_mode,launch_target,
          icon_key,resource_key,required_permission_code,badge_source_key,
          health_state,sort_order)
ON CONFLICT (tenant_id, app_key) DO UPDATE SET
    name_ko = EXCLUDED.name_ko,
    name_en = EXCLUDED.name_en,
    description_ko = EXCLUDED.description_ko,
    description_en = EXCLUDED.description_en,
    owner_name = EXCLUDED.owner_name,
    category = EXCLUDED.category,
    launch_mode = EXCLUDED.launch_mode,
    launch_target = EXCLUDED.launch_target,
    icon_key = EXCLUDED.icon_key,
    resource_key = EXCLUDED.resource_key,
    required_permission_code = EXCLUDED.required_permission_code,
    badge_source_key = EXCLUDED.badge_source_key,
    health_state = EXCLUDED.health_state,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    version = adm_workspace_apps.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1
WHERE (adm_workspace_apps.name_ko, adm_workspace_apps.name_en,
       adm_workspace_apps.description_ko, adm_workspace_apps.description_en,
       adm_workspace_apps.owner_name, adm_workspace_apps.category,
       adm_workspace_apps.launch_mode, adm_workspace_apps.launch_target,
       adm_workspace_apps.icon_key, adm_workspace_apps.resource_key,
       adm_workspace_apps.required_permission_code, adm_workspace_apps.badge_source_key,
       adm_workspace_apps.health_state, adm_workspace_apps.sort_order,
       adm_workspace_apps.lifecycle_state)
      IS DISTINCT FROM
      (EXCLUDED.name_ko, EXCLUDED.name_en,
       EXCLUDED.description_ko, EXCLUDED.description_en,
       EXCLUDED.owner_name, EXCLUDED.category,
       EXCLUDED.launch_mode, EXCLUDED.launch_target,
       EXCLUDED.icon_key, EXCLUDED.resource_key,
       EXCLUDED.required_permission_code, EXCLUDED.badge_source_key,
       EXCLUDED.health_state, EXCLUDED.sort_order, 'ACTIVE');

-- A tenant may already contain a differently named catalog row for one of the
-- approved resources. Keep that historical row for audit/reference, but retire
-- it so each canonical resource has exactly one active launch winner.
UPDATE adm_workspace_apps existing
   SET lifecycle_state = 'RETIRED',
       health_state = 'CONFIGURATION_REQUIRED',
       version = existing.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM (VALUES
    ('dwp-work','APP.WORK'),
    ('dwp-ask','APP.ASK'),
    ('dwp-activity','APP.ACTIVITY'),
    ('dwp-approvals','APP.APPROVALS'),
    ('dwp-notifications','APP.NOTIFICATIONS'),
    ('dwp-communications','APP.COMMUNICATIONS'),
    ('dwp-calendar','APP.CALENDAR'),
    ('ref-app-mail','APP.MAIL'),
    ('dwp-spaces','APP.SPACES'),
    ('dwp-rooms','APP.WORKPLACE'),
    ('dwp-messaging','APP.MESSAGING'),
    ('dwp-meetings','APP.MEETINGS'),
    ('ref-app-service','APP.EMPLOYEE_SERVICES'),
    ('ref-app-people','APP.HCM'),
    ('ref-app-knowledge','APP.KNOWLEDGE'),
    ('ref-app-erp','APP.BUSINESS_ERP'),
    ('ref-app-legacy','APP.LEGACY_OPERATIONS'),
    ('dwp-admin','APP.ADMINISTRATION')
  ) AS approved(app_key, resource_key)
 WHERE upper(trim(existing.resource_key)) = approved.resource_key
   AND existing.app_key <> approved.app_key
   AND (existing.lifecycle_state <> 'RETIRED'
        OR existing.health_state <> 'CONFIGURATION_REQUIRED');

UPDATE adm_workspace_apps
   SET lifecycle_state = 'RETIRED',
       health_state = 'CONFIGURATION_REQUIRED',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE (app_key = 'ref-app-collaboration'
    OR upper(trim(resource_key)) IN (
        'APP.COLLABORATION', 'APP.MAIL_CALENDAR', 'APP.ROOMS', 'APP.HRIS'))
   AND (lifecycle_state <> 'RETIRED' OR health_state <> 'CONFIGURATION_REQUIRED');

COMMENT ON COLUMN adm_home_experiences.launchpad_configuration IS
    'Home launchpad v1: approved 18-app core contract plus preserved tenant extension groups and placements.';

COMMENT ON COLUMN adm_workspace_apps.required_permission_code IS
    'Permission suffix combined with resource_key to authorize launch and access requests.';
COMMENT ON COLUMN adm_workspace_apps.badge_source_key IS
    'Optional client badge-count source for the canonical Home launchpad application.';
