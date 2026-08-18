ALTER TABLE adm_home_experiences
    ADD COLUMN composition_policy JSONB NOT NULL DEFAULT
    '{
      "schemaVersion": 1,
      "personalCustomizationEnabled": true,
      "governedZones": [
        {
          "zoneKey": "workspace-tools",
          "placement": "HERO",
          "visible": true,
          "size": "full",
          "sortOrder": 10
        },
        {
          "zoneKey": "announcements",
          "placement": "CANVAS",
          "visible": true,
          "size": "compact",
          "sortOrder": 20
        }
      ]
    }'::jsonb;

ALTER TABLE adm_home_experiences
    ADD CONSTRAINT ck_adm_home_experiences_composition_policy
        CHECK (
            jsonb_typeof(composition_policy) = 'object'
            AND composition_policy ? 'schemaVersion'
            AND composition_policy ? 'personalCustomizationEnabled'
            AND jsonb_typeof(composition_policy -> 'governedZones') = 'array'
        );

COMMENT ON COLUMN adm_home_experiences.composition_policy IS
    'Tenant-admin governed home zones and the server-enforced personal customization switch.';

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES
    ('PLATFORM.HOME_GOVERNED_ZONE', 'dwp-platform-server', 'Governed home zone',
     'Tenant-admin controlled home zones excluded from personal preference documents.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomeCompositionPolicyRegistry.ZONES',
     'REFERENCE', 'RUNTIME')
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    configuration_level = EXCLUDED.configuration_level,
    validation_source = EXCLUDED.validation_source,
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = EXCLUDED.runtime_visibility,
    lifecycle_state = 'ACTIVE',
    schema_version = GREATEST(sys_code_sets.schema_version, 1),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata, lifecycle_state)
VALUES
    ('PLATFORM.HOME_GOVERNED_ZONE', 'workspace-tools', 'Workspace tools',
     '{"ko":"업무 도구","en":"Workspace tools"}', 10,
     '{"placement":"HERO","defaultVisible":true,"defaultSize":"full","allowedSizes":["full"],"personalPreferenceStored":false}',
     'ACTIVE'),
    ('PLATFORM.HOME_GOVERNED_ZONE', 'announcements', 'Announcements',
     '{"ko":"새로운 소식","en":"Announcements"}', 20,
     '{"placement":"CANVAS","defaultVisible":true,"defaultSize":"compact","allowedSizes":["compact","medium","large","full"],"personalPreferenceStored":false}',
     'ACTIVE')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('PLATFORM.HOME_GOVERNED_ZONE', 'dwp-platform-server', 'API_CONTRACT',
     'HomeExperienceDtos.HomeCompositionPolicy', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_GOVERNED_ZONE', 'dwp-frontend', 'BEHAVIOR',
     'home-composition-policy', 'CATALOG_LOOKUP')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;

UPDATE sys_code_sets
   SET description = 'Personal widgets accepted by the persisted workspace home layout.',
       schema_version = GREATEST(schema_version, 4),
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET';

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('PLATFORM.HOME_WIDGET', 'command-rail', 'Command rail',
     '{"ko":"업무 지휘","en":"Command rail"}', 10,
     '{"canHide":true,"defaultSize":"large","allowedSizes":["large","full"],"owner":"Digital Workplace Product","dataSource":"DWP_HOME_OVERVIEW","freshnessSeconds":30,"privacyClass":"CONFIDENTIAL","retention":"NONE","analyticsKey":"home.command-rail","compositionKind":"PERSONAL"}')
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    sort_order = EXCLUDED.sort_order,
    behavior_metadata = EXCLUDED.behavior_metadata,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values
   SET sort_order = CASE code
           WHEN 'command-rail' THEN 10
           WHEN 'activity' THEN 20
           WHEN 'focus' THEN 30
           WHEN 'schedule' THEN 40
           WHEN 'daily-brief' THEN 50
           ELSE sort_order
       END,
       lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_WIDGET'
   AND code IN ('command-rail', 'activity', 'focus', 'schedule', 'daily-brief');

-- The launchpad is a governed zone. Existing personal app layouts are retired,
-- while existing personal widget order and sizes are preserved.
UPDATE usr_home_preferences AS preference
   SET schema_version = 3,
       layout_payload = jsonb_set(
           jsonb_set(preference.layout_payload, '{appLayout}', 'null'::jsonb, true),
           '{widgets}',
           CASE
               WHEN EXISTS (
                   SELECT 1
                     FROM jsonb_array_elements(preference.layout_payload -> 'widgets') item
                    WHERE item ->> 'widgetKey' = 'command-rail'
               ) THEN preference.layout_payload -> 'widgets'
               ELSE jsonb_build_array(
                   '{"widgetKey":"command-rail","visible":true,"size":"large"}'::jsonb)
                   || (preference.layout_payload -> 'widgets')
           END,
           true),
       updated_at = CURRENT_TIMESTAMP
 WHERE preference.surface_key = 'workspace-home'
   AND jsonb_typeof(preference.layout_payload) = 'object'
   AND jsonb_typeof(preference.layout_payload -> 'widgets') = 'array';
