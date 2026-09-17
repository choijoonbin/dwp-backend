-- Five manifests are byte-for-model equivalents of the ADM-009 golden fixture.  The final two
-- are explicit first-party extensions from Wave 1 HomeLayoutPolicy (see the checked-in backend
-- contract fixture).  All seven are published into the control plane, but V294 keeps evaluation
-- SHADOW-only and runtime_activation_ready=false.

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('32000000-0000-0000-0000-000000000001', 'home.command-rail', 'NATIVE', 'core.workspace', 'APP.WORK', 1, 1, 'ACTIVE', 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('30000000-0000-0000-0000-000000000001', 'core.workspace.command-rail', 'command-rail', 'core.workspace', 'dwp-home', 'HIGH', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('31000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"core.workspace.command-rail","owner":{"productKey":"core.workspace","sourceAppResourceKey":"APP.WORK"},"renderer":{"kind":"NATIVE","rendererKey":"home.command-rail","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.WORK:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_GOVERNED"],"policyClass":"GOVERNED","canHide":true,"defaultSize":"large","allowedSizes":["large","full"],"defaultHeight":"short","allowedHeights":["short","standard"]},"configurationContract":null,"dataCapabilities":["HOME.OVERVIEW.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.command-rail"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97', 'home.command-rail', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"LEGACY_UNVERIFIED","fixtureVersion":2}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES ('34000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001', 'MANIFEST', 'PASS', 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97', 'fixture:native-widget-manifests.v1:command-rail', 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('33000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'STABLE', '31000000-0000-0000-0000-000000000001', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000001')::uuid, tenant_id, '30000000-0000-0000-0000-000000000001', 1, 'PUBLISHED', TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb, '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb, TRUE, '{}'::jsonb, 'PRIVATE', NULL, 'LEGACY_BASELINE', 'Wave 3 native widget baseline', 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('native-widget-policy-head:' || tenant_id || ':30000000-0000-0000-0000-000000000001')::uuid, tenant_id, '30000000-0000-0000-0000-000000000001', md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000001')::uuid, 0, 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('32000000-0000-0000-0000-000000000002', 'home.daily-brief', 'NATIVE', 'core.workspace', 'APP.WORK', 1, 1, 'ACTIVE', '9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('30000000-0000-0000-0000-000000000002', 'core.workspace.daily-brief', 'daily-brief', 'core.workspace', 'dwp-home', 'MEDIUM', 'INTERNAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('31000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"core.workspace.daily-brief","owner":{"productKey":"core.workspace","sourceAppResourceKey":"APP.WORK"},"renderer":{"kind":"NATIVE","rendererKey":"home.daily-brief","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.WORK:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"full","allowedSizes":["compact","large","full"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":{"sourceKey":"RECOMMENDATION","fieldKeys":["title","reason","action"],"filterPresets":["RECOMMENDED","NEXT_ACTIONS"],"itemLimit":{"min":1,"max":20}},"dataCapabilities":["HOME.RECOMMENDATIONS.READ"],"actionCapabilities":[],"sharing":{"presetEligible":true},"operations":{"freshnessSeconds":30,"analyticsKey":"home.workday-insights"},"privacy":{"classification":"INTERNAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406', 'home.daily-brief', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"LEGACY_UNVERIFIED","fixtureVersion":2}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES ('34000000-0000-0000-0000-000000000002', '31000000-0000-0000-0000-000000000002', 'MANIFEST', 'PASS', '9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406', 'fixture:native-widget-manifests.v1:daily-brief', '9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('33000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 'STABLE', '31000000-0000-0000-0000-000000000002', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000002')::uuid, tenant_id, '30000000-0000-0000-0000-000000000002', 1, 'PUBLISHED', TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb, '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb, FALSE, '{}'::jsonb, 'PRIVATE', NULL, 'LEGACY_BASELINE', 'Wave 3 native widget baseline', 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('native-widget-policy-head:' || tenant_id || ':30000000-0000-0000-0000-000000000002')::uuid, tenant_id, '30000000-0000-0000-0000-000000000002', md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000002')::uuid, 0, 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('32000000-0000-0000-0000-000000000003', 'home.focus', 'NATIVE', 'core.work', 'APP.WORK', 1, 1, 'ACTIVE', '36d1b02326e4725a235749e173dfdf50a0423ef30f42d7ccab97946ba826d893')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('30000000-0000-0000-0000-000000000003', 'core.work.focus', 'focus', 'core.work', 'dwp-home', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('31000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"core.work.focus","owner":{"productKey":"core.work","sourceAppResourceKey":"APP.WORK"},"renderer":{"kind":"NATIVE","rendererKey":"home.focus","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.WORK:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]},"configurationContract":{"sourceKey":"WORK","fieldKeys":["title","status","priority","dueAt"],"filterPresets":["ASSIGNED_TO_ME","DUE_SOON","HIGH_PRIORITY"],"itemLimit":{"min":1,"max":20}},"dataCapabilities":["WORK.ITEMS.LIST"],"actionCapabilities":[],"sharing":{"presetEligible":true},"operations":{"freshnessSeconds":30,"analyticsKey":"home.focus"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '36d1b02326e4725a235749e173dfdf50a0423ef30f42d7ccab97946ba826d893', 'home.focus', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"LEGACY_UNVERIFIED","fixtureVersion":2}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES ('34000000-0000-0000-0000-000000000003', '31000000-0000-0000-0000-000000000003', 'MANIFEST', 'PASS', '36d1b02326e4725a235749e173dfdf50a0423ef30f42d7ccab97946ba826d893', 'fixture:native-widget-manifests.v1:focus', '36d1b02326e4725a235749e173dfdf50a0423ef30f42d7ccab97946ba826d893', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('33000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', 'STABLE', '31000000-0000-0000-0000-000000000003', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000003')::uuid, tenant_id, '30000000-0000-0000-0000-000000000003', 1, 'PUBLISHED', TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb, '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb, FALSE, '{}'::jsonb, 'PRIVATE', NULL, 'LEGACY_BASELINE', 'Wave 3 native widget baseline', 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('native-widget-policy-head:' || tenant_id || ':30000000-0000-0000-0000-000000000003')::uuid, tenant_id, '30000000-0000-0000-0000-000000000003', md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000003')::uuid, 0, 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('32000000-0000-0000-0000-000000000004', 'home.schedule', 'NATIVE', 'core.calendar', 'APP.CALENDAR', 1, 1, 'ACTIVE', '7f3e090997a213e9d3e6f8184e1458e57382c5f31db79f00fbf678d36f884f5d')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('30000000-0000-0000-0000-000000000004', 'core.calendar.schedule', 'schedule', 'core.calendar', 'dwp-home', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('31000000-0000-0000-0000-000000000004', '30000000-0000-0000-0000-000000000004', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"core.calendar.schedule","owner":{"productKey":"core.calendar","sourceAppResourceKey":"APP.CALENDAR"},"renderer":{"kind":"NATIVE","rendererKey":"home.schedule","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.CALENDAR:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"quarter","allowedSizes":["fifth","quarter","compact","medium"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":{"sourceKey":"CALENDAR","fieldKeys":["title","startAt","endAt","location"],"filterPresets":["TODAY","NEXT_7_DAYS"],"itemLimit":{"min":1,"max":20}},"dataCapabilities":["CALENDAR.EVENTS.LIST"],"actionCapabilities":[],"sharing":{"presetEligible":true},"operations":{"freshnessSeconds":30,"analyticsKey":"home.schedule"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '7f3e090997a213e9d3e6f8184e1458e57382c5f31db79f00fbf678d36f884f5d', 'home.schedule', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"LEGACY_UNVERIFIED","fixtureVersion":2}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES ('34000000-0000-0000-0000-000000000004', '31000000-0000-0000-0000-000000000004', 'MANIFEST', 'PASS', '7f3e090997a213e9d3e6f8184e1458e57382c5f31db79f00fbf678d36f884f5d', 'fixture:native-widget-manifests.v1:schedule', '7f3e090997a213e9d3e6f8184e1458e57382c5f31db79f00fbf678d36f884f5d', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('33000000-0000-0000-0000-000000000004', '30000000-0000-0000-0000-000000000004', 'STABLE', '31000000-0000-0000-0000-000000000004', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000004')::uuid, tenant_id, '30000000-0000-0000-0000-000000000004', 1, 'PUBLISHED', TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb, '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb, FALSE, '{}'::jsonb, 'PRIVATE', NULL, 'LEGACY_BASELINE', 'Wave 3 native widget baseline', 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('native-widget-policy-head:' || tenant_id || ':30000000-0000-0000-0000-000000000004')::uuid, tenant_id, '30000000-0000-0000-0000-000000000004', md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000004')::uuid, 0, 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('32000000-0000-0000-0000-000000000005', 'home.activity', 'NATIVE', 'core.activity', 'APP.ACTIVITY', 1, 1, 'ACTIVE', 'fbab61015ec3b20c2faf9810b1758aebbd7517029baa64cb6b99190815836ca1')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('30000000-0000-0000-0000-000000000005', 'core.activity.activity', 'activity', 'core.activity', 'dwp-home', 'MEDIUM', 'INTERNAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('31000000-0000-0000-0000-000000000005', '30000000-0000-0000-0000-000000000005', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"core.activity.activity","owner":{"productKey":"core.activity","sourceAppResourceKey":"APP.ACTIVITY"},"renderer":{"kind":"NATIVE","rendererKey":"home.activity","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.ACTIVITY:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"quarter","allowedSizes":["fifth","quarter","compact","medium"],"defaultHeight":"tall","allowedHeights":["short","standard","tall"]},"configurationContract":{"sourceKey":"ACTIVITY","fieldKeys":["eventType","title","occurredAt","actor"],"filterPresets":["RECENT","MY_ACTIVITY"],"itemLimit":{"min":1,"max":20}},"dataCapabilities":["ACTIVITY.EVENTS.LIST"],"actionCapabilities":[],"sharing":{"presetEligible":true},"operations":{"freshnessSeconds":30,"analyticsKey":"home.activity"},"privacy":{"classification":"INTERNAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, 'fbab61015ec3b20c2faf9810b1758aebbd7517029baa64cb6b99190815836ca1', 'home.activity', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"LEGACY_UNVERIFIED","fixtureVersion":2}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES ('34000000-0000-0000-0000-000000000005', '31000000-0000-0000-0000-000000000005', 'MANIFEST', 'PASS', 'fbab61015ec3b20c2faf9810b1758aebbd7517029baa64cb6b99190815836ca1', 'fixture:native-widget-manifests.v1:activity', 'fbab61015ec3b20c2faf9810b1758aebbd7517029baa64cb6b99190815836ca1', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('33000000-0000-0000-0000-000000000005', '30000000-0000-0000-0000-000000000005', 'STABLE', '31000000-0000-0000-0000-000000000005', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000005')::uuid, tenant_id, '30000000-0000-0000-0000-000000000005', 1, 'PUBLISHED', TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb, '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb, FALSE, '{}'::jsonb, 'PRIVATE', NULL, 'LEGACY_BASELINE', 'Wave 3 native widget baseline', 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('native-widget-policy-head:' || tenant_id || ':30000000-0000-0000-0000-000000000005')::uuid, tenant_id, '30000000-0000-0000-0000-000000000005', md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000005')::uuid, 0, 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('32000000-0000-0000-0000-000000000006', 'home.focus-balance', 'NATIVE', 'core.work', 'APP.WORK', 1, 1, 'ACTIVE', '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('30000000-0000-0000-0000-000000000006', 'core.work.focus-balance', 'focus-balance', 'core.work', 'dwp-home', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('31000000-0000-0000-0000-000000000006', '30000000-0000-0000-0000-000000000006', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"core.work.focus-balance","owner":{"productKey":"core.work","sourceAppResourceKey":"APP.WORK"},"renderer":{"kind":"NATIVE","rendererKey":"home.focus-balance","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.WORK:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium"],"defaultHeight":"short","allowedHeights":["short","standard"]},"configurationContract":null,"dataCapabilities":["WORK.ITEMS.LIST"],"actionCapabilities":[],"sharing":{"presetEligible":true},"operations":{"freshnessSeconds":30,"analyticsKey":"home.focus-balance"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899', 'home.focus-balance', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"LEGACY_UNVERIFIED","fixtureVersion":2}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES ('34000000-0000-0000-0000-000000000006', '31000000-0000-0000-0000-000000000006', 'MANIFEST', 'PASS', '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899', 'fixture:native-widget-manifests.v1:focus-balance', '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('33000000-0000-0000-0000-000000000006', '30000000-0000-0000-0000-000000000006', 'STABLE', '31000000-0000-0000-0000-000000000006', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000006')::uuid, tenant_id, '30000000-0000-0000-0000-000000000006', 1, 'PUBLISHED', TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb, '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb, FALSE, '{}'::jsonb, 'PRIVATE', NULL, 'LEGACY_BASELINE', 'Wave 3 native widget baseline', 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('native-widget-policy-head:' || tenant_id || ':30000000-0000-0000-0000-000000000006')::uuid, tenant_id, '30000000-0000-0000-0000-000000000006', md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000006')::uuid, 0, 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('32000000-0000-0000-0000-000000000007', 'home.meeting-load', 'NATIVE', 'core.calendar', 'APP.CALENDAR', 1, 1, 'ACTIVE', '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('30000000-0000-0000-0000-000000000007', 'core.calendar.meeting-load', 'meeting-load', 'core.calendar', 'dwp-home', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('31000000-0000-0000-0000-000000000007', '30000000-0000-0000-0000-000000000007', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"core.calendar.meeting-load","owner":{"productKey":"core.calendar","sourceAppResourceKey":"APP.CALENDAR"},"renderer":{"kind":"NATIVE","rendererKey":"home.meeting-load","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.CALENDAR:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium"],"defaultHeight":"short","allowedHeights":["short","standard"]},"configurationContract":null,"dataCapabilities":["CALENDAR.EVENTS.LIST"],"actionCapabilities":[],"sharing":{"presetEligible":true},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meeting-load"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2', 'home.meeting-load', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"LEGACY_UNVERIFIED","fixtureVersion":2}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES ('34000000-0000-0000-0000-000000000007', '31000000-0000-0000-0000-000000000007', 'MANIFEST', 'PASS', '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2', 'fixture:native-widget-manifests.v1:meeting-load', '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('33000000-0000-0000-0000-000000000007', '30000000-0000-0000-0000-000000000007', 'STABLE', '31000000-0000-0000-0000-000000000007', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000007')::uuid, tenant_id, '30000000-0000-0000-0000-000000000007', 1, 'PUBLISHED', TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb, '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb, FALSE, '{}'::jsonb, 'PRIVATE', NULL, 'LEGACY_BASELINE', 'Wave 3 native widget baseline', 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('native-widget-policy-head:' || tenant_id || ':30000000-0000-0000-0000-000000000007')::uuid, tenant_id, '30000000-0000-0000-0000-000000000007', md5('native-widget-policy:' || tenant_id || ':30000000-0000-0000-0000-000000000007')::uuid, 0, 1 FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES ('35000000-0000-0000-0000-000000000001', 2, 'DEFINITION', '30000000-0000-0000-0000-000000000001', 'NATIVE_WIDGET_SEEDED', 1, 'flyway-v257', NULL, '{"versionId":"31000000-0000-0000-0000-000000000001","definitionKey":"core.workspace.command-rail"}'::jsonb, '["34000000-0000-0000-0000-000000000001"]'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES ('35000000-0000-0000-0000-000000000002', 3, 'DEFINITION', '30000000-0000-0000-0000-000000000002', 'NATIVE_WIDGET_SEEDED', 1, 'flyway-v257', NULL, '{"versionId":"31000000-0000-0000-0000-000000000002","definitionKey":"core.workspace.daily-brief"}'::jsonb, '["34000000-0000-0000-0000-000000000002"]'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES ('35000000-0000-0000-0000-000000000003', 4, 'DEFINITION', '30000000-0000-0000-0000-000000000003', 'NATIVE_WIDGET_SEEDED', 1, 'flyway-v257', NULL, '{"versionId":"31000000-0000-0000-0000-000000000003","definitionKey":"core.work.focus"}'::jsonb, '["34000000-0000-0000-0000-000000000003"]'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES ('35000000-0000-0000-0000-000000000004', 5, 'DEFINITION', '30000000-0000-0000-0000-000000000004', 'NATIVE_WIDGET_SEEDED', 1, 'flyway-v257', NULL, '{"versionId":"31000000-0000-0000-0000-000000000004","definitionKey":"core.calendar.schedule"}'::jsonb, '["34000000-0000-0000-0000-000000000004"]'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES ('35000000-0000-0000-0000-000000000005', 6, 'DEFINITION', '30000000-0000-0000-0000-000000000005', 'NATIVE_WIDGET_SEEDED', 1, 'flyway-v257', NULL, '{"versionId":"31000000-0000-0000-0000-000000000005","definitionKey":"core.activity.activity"}'::jsonb, '["34000000-0000-0000-0000-000000000005"]'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES ('35000000-0000-0000-0000-000000000006', 7, 'DEFINITION', '30000000-0000-0000-0000-000000000006', 'NATIVE_WIDGET_SEEDED', 1, 'flyway-v257', NULL, '{"versionId":"31000000-0000-0000-0000-000000000006","definitionKey":"core.work.focus-balance"}'::jsonb, '["34000000-0000-0000-0000-000000000006"]'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES ('35000000-0000-0000-0000-000000000007', 8, 'DEFINITION', '30000000-0000-0000-0000-000000000007', 'NATIVE_WIDGET_SEEDED', 1, 'flyway-v257', NULL, '{"versionId":"31000000-0000-0000-0000-000000000007","definitionKey":"core.calendar.meeting-load"}'::jsonb, '["34000000-0000-0000-0000-000000000007"]'::jsonb)
ON CONFLICT (event_id) DO NOTHING;

UPDATE plt_widget_registry_state SET registry_revision = GREATEST(registry_revision, 8), policy_revision = GREATEST(policy_revision, 8), updated_at = CURRENT_TIMESTAMP WHERE environment = 'GLOBAL';

-- A new tenant is seeded by PlatformTenantProvisioningService.  Missing tenant policy is DENY,
-- so a tenant can never inherit a widget merely because a definition exists.
