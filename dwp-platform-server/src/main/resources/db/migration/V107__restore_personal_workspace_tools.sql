-- Workspace tools are member-owned. Tenant administration still controls the
-- entitled app catalog and default placement, but not a member's app arrangement.
ALTER TABLE adm_home_experiences
    ALTER COLUMN composition_policy SET DEFAULT
    '{
      "schemaVersion": 1,
      "personalCustomizationEnabled": true,
      "governedZones": [
        {
          "zoneKey": "announcements",
          "placement": "CANVAS",
          "visible": true,
          "size": "compact",
          "sortOrder": 20
        }
      ]
    }'::jsonb;

UPDATE adm_home_experiences AS experience
   SET composition_policy = jsonb_set(
           experience.composition_policy,
           '{governedZones}',
           (
               SELECT COALESCE(jsonb_agg(zone), '[]'::jsonb)
                 FROM jsonb_array_elements(
                          experience.composition_policy -> 'governedZones') AS zone
                WHERE zone ->> 'zoneKey' <> 'workspace-tools'
           ),
           true),
       updated_at = CURRENT_TIMESTAMP
 WHERE jsonb_typeof(experience.composition_policy -> 'governedZones') = 'array'
   AND EXISTS (
       SELECT 1
         FROM jsonb_array_elements(experience.composition_policy -> 'governedZones') AS zone
        WHERE zone ->> 'zoneKey' = 'workspace-tools'
   );

UPDATE sys_code_values
   SET lifecycle_state = 'RETIRED',
       behavior_metadata = behavior_metadata
           || '{"compositionKind":"PERSONAL","preferencePath":"layout.appLayout"}'::jsonb,
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_GOVERNED_ZONE'
   AND code = 'workspace-tools';

UPDATE sys_code_sets
   SET description = 'Tenant-admin controlled home zones excluded from personal preference documents.',
       schema_version = GREATEST(schema_version, 2),
       updated_at = CURRENT_TIMESTAMP
 WHERE code_set_key = 'PLATFORM.HOME_GOVERNED_ZONE';

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility)
VALUES
    ('PLATFORM.HOME_PERSONAL_ZONE', 'dwp-platform-server', 'Personal home zone',
     'Member-owned home zones persisted in the personal home preference document.',
     'SYSTEM', 'TYPED_CONTRACT', 'HomePreferenceService.workspaceContract',
     'REFERENCE', 'RUNTIME')
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
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
    ('PLATFORM.HOME_PERSONAL_ZONE', 'workspace-tools', 'Workspace tools',
     '{"ko":"업무 도구","en":"Workspace tools"}', 10,
     '{"placement":"HERO","preferencePath":"layout.appLayout","capabilities":["ORDER","MOVE","FOLDER","HIDE"],"entitlementFiltered":true}',
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
    ('PLATFORM.HOME_PERSONAL_ZONE', 'dwp-platform-server', 'API_CONTRACT',
     'HomePreferenceDtos.HomeLayoutPayload.appLayout', 'TYPED_CONTRACT'),
    ('PLATFORM.HOME_PERSONAL_ZONE', 'dwp-frontend', 'BEHAVIOR',
     'app-launchpad-model', 'CATALOG_LOOKUP')
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference) DO NOTHING;

UPDATE usr_home_preferences
   SET schema_version = 4,
       updated_at = CURRENT_TIMESTAMP
 WHERE schema_version < 4;
