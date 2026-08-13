ALTER TABLE usr_home_preferences
    ADD COLUMN surface_key VARCHAR(80) NOT NULL DEFAULT 'workspace-home';

ALTER TABLE usr_home_preferences
    DROP CONSTRAINT uk_usr_home_preferences_tenant_user;

ALTER TABLE usr_home_preferences
    ADD CONSTRAINT uk_usr_home_preferences_tenant_user_surface
        UNIQUE (tenant_id, user_id, surface_key),
    ADD CONSTRAINT ck_usr_home_preferences_surface_key
        CHECK (surface_key ~ '^[a-z][a-z0-9-]{1,79}$');

COMMENT ON COLUMN usr_home_preferences.surface_key IS
    'Stable product surface identifier. Each user owns an independently versioned layout per surface.';
COMMENT ON COLUMN usr_home_preferences.layout_payload IS
    'Versioned, server-validated widget composition. Executable component code is never persisted.';

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('PLATFORM.HOME_SURFACE', 'dwp-platform-server', 'Personal home surface',
     'Product surfaces that support independently persisted personal composition.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomePreferenceService.SURFACE_CONTRACTS', 'REFERENCE'),
    ('PLATFORM.HOME_PRESENTATION', 'dwp-platform-server', 'Home presentation tone',
     'Accessible presentation treatments available to a personal home surface.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomePreferenceService.PRESENTATIONS', 'REFERENCE'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'dwp-platform-server', 'Home widget size',
     'Responsive semantic size choices accepted by a personal widget placement.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomePreferenceService.WIDGET_SIZES', 'REFERENCE'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'dwp-platform-server', 'HRIS home widget',
     'Permission-aware widgets accepted by the HRIS personal home surface.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomePreferenceService.SURFACE_CONTRACTS[hris-home]', 'REFERENCE')
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.HOME_SURFACE', 'workspace-home', 'Workspace home',
     '{"ko":"개인 워크스페이스 홈","en":"Workspace home"}', 10,
     '{"personalizable":true}'),
    ('PLATFORM.HOME_SURFACE', 'hris-home', 'HRIS home',
     '{"ko":"HRIS 홈","en":"HRIS home"}', 20,
     '{"personalizable":true,"permissionAware":true}'),
    ('PLATFORM.HOME_PRESENTATION', 'balanced', 'Balanced',
     '{"ko":"균형","en":"Balanced"}', 10, '{"default":true}'),
    ('PLATFORM.HOME_PRESENTATION', 'expressive', 'Expressive',
     '{"ko":"생동감","en":"Expressive"}', 20, '{"motion":"subtle"}'),
    ('PLATFORM.HOME_PRESENTATION', 'focused', 'Focused',
     '{"ko":"집중","en":"Focused"}', 30, '{"decoration":"reduced"}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'compact', 'Compact',
     '{"ko":"작게","en":"Compact"}', 10, '{"columns":4}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'medium', 'Medium',
     '{"ko":"보통","en":"Medium"}', 20, '{"columns":6}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'large', 'Large',
     '{"ko":"넓게","en":"Large"}', 30, '{"columns":8}'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'full', 'Full width',
     '{"ko":"전체 너비","en":"Full width"}', 40, '{"columns":12}'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'quick-actions', 'Quick actions',
     '{"ko":"바로가기","en":"Quick actions"}', 10,
     '{"defaultSize":"full","allowedSizes":["medium","large","full"]}'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'people-signals', 'People signals',
     '{"ko":"인사 신호","en":"People signals"}', 20,
     '{"defaultSize":"full","allowedSizes":["large","full"]}'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'attention', 'Needs attention',
     '{"ko":"확인할 일","en":"Needs attention"}', 30,
     '{"defaultSize":"large","allowedSizes":["medium","large","full"]}'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'profile', 'My profile',
     '{"ko":"내 프로필","en":"My profile"}', 40,
     '{"defaultSize":"compact","allowedSizes":["compact","medium"]}'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'team', 'My team',
     '{"ko":"내 팀","en":"My team"}', 50,
     '{"defaultSize":"full","allowedSizes":["medium","large","full"],"audience":"manager"}'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'operations', 'HR operations',
     '{"ko":"HR 운영","en":"HR operations"}', 60,
     '{"defaultSize":"full","allowedSizes":["large","full"],"audience":"operator"}')
ON CONFLICT (code_set_key, code) DO NOTHING;

UPDATE sys_code_sets
   SET source_reference = 'HomePreferenceService.SURFACE_CONTRACTS[workspace-home]',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.HOME_SURFACE', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceController.surfaceKey', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_SURFACE', 'dwp-frontend', 'BEHAVIOR',
     'workspace-composer/surfaceKey', 'CATALOG_LOOKUP'),
    ('PLATFORM.HOME_PRESENTATION', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceDtos.HomeLayoutPayload.presentation', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_PRESENTATION', 'dwp-frontend', 'UI_SELECTION',
     'workspace-composer/presentation', 'CATALOG_LOOKUP'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceDtos.WidgetPreference.size', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_WIDGET_SIZE', 'dwp-frontend', 'UI_SELECTION',
     'workspace-composer/widget-size', 'CATALOG_LOOKUP'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceService.SURFACE_CONTRACTS[hris-home]', 'TYPED_CONTRACT'),
    ('PLATFORM.HRIS_HOME_WIDGET', 'dwp-frontend', 'BEHAVIOR',
     'hris/hris-home-widget-registry', 'CATALOG_LOOKUP')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;
