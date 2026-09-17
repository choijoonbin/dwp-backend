-- Wave 4 registers owner-service provider contracts while Registry remains SHADOW.
-- Only the content-free notification badge projection is enabled by default; the other
-- owner widgets stay explicitly disabled until Wave 5 renderer certification/placement.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM plt_widget_registry_state
         WHERE environment = 'GLOBAL'
           AND migration_mode = 'SHADOW'
           AND runtime_activation_ready = FALSE
    ) THEN
        RAISE EXCEPTION 'Wave 4 owner widget seed requires SHADOW/non-authoritative Registry';
    END IF;
END $$;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000001', 'home.approval.focus-queue', 'NATIVE', 'core.approvals', 'APP.APPROVALS', 1, 1, 'ACTIVE', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000001', 'approval.focus-queue', 'focus-queue', 'core.approvals', 'dwp-approval', 'HIGH', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000001', '36000000-0000-0000-0000-000000000001', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"approval.focus-queue","owner":{"productKey":"core.approvals","sourceAppResourceKey":"APP.APPROVALS"},"renderer":{"kind":"NATIVE","rendererKey":"home.approval.focus-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["ACTION.APPROVAL_TASK:VIEW","APP.APPROVALS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"large","allowedSizes":["medium","large","full"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["APPROVAL.FOCUS_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.approval.focus-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 'home.approval.focus-queue', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:approval.focus-queue:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000001', 'MANIFEST', 'PASS', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 'fixture:wave4-owner-widget-manifests.v1:approval.focus-queue', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:approval.focus-queue:SECURITY')::uuid, '36100000-0000-0000-0000-000000000001', 'SECURITY', 'PASS', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#security', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:approval.focus-queue:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000001', 'PRIVACY', 'PASS', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#payload-boundary', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000001', '36000000-0000-0000-0000-000000000001', 'STABLE', '36100000-0000-0000-0000-000000000001', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:approval.focus-queue')::uuid, 12, 'DEFINITION', '36000000-0000-0000-0000-000000000001', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000001","definitionKey":"approval.focus-queue","providerKey":"approval","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:approval.focus-queue:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000002', 'home.approval.my-requests', 'NATIVE', 'core.approvals', 'APP.APPROVALS', 1, 1, 'ACTIVE', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000002', 'approval.my-requests', 'my-requests', 'core.approvals', 'dwp-approval', 'HIGH', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000002', '36000000-0000-0000-0000-000000000002', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"approval.my-requests","owner":{"productKey":"core.approvals","sourceAppResourceKey":"APP.APPROVALS"},"renderer":{"kind":"NATIVE","rendererKey":"home.approval.my-requests","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["ACTION.APPROVAL_REQUEST:VIEW","APP.APPROVALS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["medium","large","full"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["APPROVAL.MY_REQUESTS.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.approval.my-requests"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 'home.approval.my-requests', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:approval.my-requests:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000002', 'MANIFEST', 'PASS', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 'fixture:wave4-owner-widget-manifests.v1:approval.my-requests', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:approval.my-requests:SECURITY')::uuid, '36100000-0000-0000-0000-000000000002', 'SECURITY', 'PASS', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#security', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:approval.my-requests:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000002', 'PRIVACY', 'PASS', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#payload-boundary', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000002', '36000000-0000-0000-0000-000000000002', 'STABLE', '36100000-0000-0000-0000-000000000002', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:approval.my-requests')::uuid, 13, 'DEFINITION', '36000000-0000-0000-0000-000000000002', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000002","definitionKey":"approval.my-requests","providerKey":"approval","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:approval.my-requests:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000003', 'home.meetings.next-prep', 'NATIVE', 'core.meetings', 'APP.MEETINGS', 1, 1, 'ACTIVE', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000003', 'meetings.next-prep', 'meeting-next-prep', 'core.meetings', 'dwp-meeting', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000003', '36000000-0000-0000-0000-000000000003', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"meetings.next-prep","owner":{"productKey":"core.meetings","sourceAppResourceKey":"APP.MEETINGS"},"renderer":{"kind":"NATIVE","rendererKey":"home.meetings.next-prep","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MEETINGS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MEETINGS.NEXT_PREP.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meetings.next-prep"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 'home.meetings.next-prep', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:meetings.next-prep:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000003', 'MANIFEST', 'PASS', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 'fixture:wave4-owner-widget-manifests.v1:meetings.next-prep', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:meetings.next-prep:SECURITY')::uuid, '36100000-0000-0000-0000-000000000003', 'SECURITY', 'PASS', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#security', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:meetings.next-prep:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000003', 'PRIVACY', 'PASS', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#payload-boundary', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000003', '36000000-0000-0000-0000-000000000003', 'STABLE', '36100000-0000-0000-0000-000000000003', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:meetings.next-prep')::uuid, 14, 'DEFINITION', '36000000-0000-0000-0000-000000000003', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000003","definitionKey":"meetings.next-prep","providerKey":"meeting","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:meetings.next-prep:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000004', 'home.meetings.followup-candidates', 'NATIVE', 'core.meetings', 'APP.MEETINGS', 1, 1, 'ACTIVE', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000004', 'meetings.followup-candidates', 'meeting-followups', 'core.meetings', 'dwp-meeting', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000004', '36000000-0000-0000-0000-000000000004', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"meetings.followup-candidates","owner":{"productKey":"core.meetings","sourceAppResourceKey":"APP.MEETINGS"},"renderer":{"kind":"NATIVE","rendererKey":"home.meetings.followup-candidates","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MEETINGS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MEETINGS.FOLLOWUP_CANDIDATES.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meetings.followup-candidates"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 'home.meetings.followup-candidates', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:meetings.followup-candidates:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000004', 'MANIFEST', 'PASS', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 'fixture:wave4-owner-widget-manifests.v1:meetings.followup-candidates', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:meetings.followup-candidates:SECURITY')::uuid, '36100000-0000-0000-0000-000000000004', 'SECURITY', 'PASS', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#security', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:meetings.followup-candidates:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000004', 'PRIVACY', 'PASS', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#payload-boundary', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000004', '36000000-0000-0000-0000-000000000004', 'STABLE', '36100000-0000-0000-0000-000000000004', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:meetings.followup-candidates')::uuid, 15, 'DEFINITION', '36000000-0000-0000-0000-000000000004', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000004","definitionKey":"meetings.followup-candidates","providerKey":"meeting","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:meetings.followup-candidates:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000005', 'home.notification.app-badges', 'NATIVE', 'core.notifications', 'APP.NOTIFICATIONS', 1, 1, 'ACTIVE', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000005', 'notification.app-badges', 'application-dock', 'core.notifications', 'dwp-notification', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000005', '36000000-0000-0000-0000-000000000005', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"notification.app-badges","owner":{"productKey":"core.notifications","sourceAppResourceKey":"APP.NOTIFICATIONS"},"renderer":{"kind":"NATIVE","rendererKey":"home.notification.app-badges","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.NOTIFICATIONS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"full","allowedSizes":["full"],"defaultHeight":"short","allowedHeights":["short"]},"configurationContract":null,"dataCapabilities":["NOTIFICATION.APP_BADGES.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.notification.app-badges"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 'home.notification.app-badges', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:notification.app-badges:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000005', 'MANIFEST', 'PASS', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 'fixture:wave4-owner-widget-manifests.v1:notification.app-badges', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:notification.app-badges:SECURITY')::uuid, '36100000-0000-0000-0000-000000000005', 'SECURITY', 'PASS', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#security', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:notification.app-badges:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000005', 'PRIVACY', 'PASS', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#payload-boundary', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000005', '36000000-0000-0000-0000-000000000005', 'STABLE', '36100000-0000-0000-0000-000000000005', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:notification.app-badges')::uuid, 16, 'DEFINITION', '36000000-0000-0000-0000-000000000005', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000005","definitionKey":"notification.app-badges","providerKey":"notification","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:notification.app-badges:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000006', 'home.notification.response-queue', 'NATIVE', 'core.notifications', 'APP.NOTIFICATIONS', 1, 1, 'ACTIVE', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000006', 'notification.response-queue', 'notification-response-queue', 'core.notifications', 'dwp-notification', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000006', '36000000-0000-0000-0000-000000000006', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"notification.response-queue","owner":{"productKey":"core.notifications","sourceAppResourceKey":"APP.NOTIFICATIONS"},"renderer":{"kind":"NATIVE","rendererKey":"home.notification.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.NOTIFICATIONS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["NOTIFICATION.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.notification.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 'home.notification.response-queue', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:notification.response-queue:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000006', 'MANIFEST', 'PASS', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 'fixture:wave4-owner-widget-manifests.v1:notification.response-queue', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:notification.response-queue:SECURITY')::uuid, '36100000-0000-0000-0000-000000000006', 'SECURITY', 'PASS', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#security', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:notification.response-queue:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000006', 'PRIVACY', 'PASS', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#payload-boundary', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000006', '36000000-0000-0000-0000-000000000006', 'STABLE', '36100000-0000-0000-0000-000000000006', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:notification.response-queue')::uuid, 17, 'DEFINITION', '36000000-0000-0000-0000-000000000006', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000006","definitionKey":"notification.response-queue","providerKey":"notification","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:notification.response-queue:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000007', 'home.space.change-feed', 'NATIVE', 'core.spaces', 'APP.SPACES', 1, 1, 'ACTIVE', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000007', 'space.change-feed', 'space-change-feed', 'core.spaces', 'dwp-space', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000007', '36000000-0000-0000-0000-000000000007', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"space.change-feed","owner":{"productKey":"core.spaces","sourceAppResourceKey":"APP.SPACES"},"renderer":{"kind":"NATIVE","rendererKey":"home.space.change-feed","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.SPACES:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]},"configurationContract":null,"dataCapabilities":["SPACE.CHANGE_FEED.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.space.change-feed"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 'home.space.change-feed', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:space.change-feed:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000007', 'MANIFEST', 'PASS', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 'fixture:wave4-owner-widget-manifests.v1:space.change-feed', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:space.change-feed:SECURITY')::uuid, '36100000-0000-0000-0000-000000000007', 'SECURITY', 'PASS', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#security', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:space.change-feed:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000007', 'PRIVACY', 'PASS', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#payload-boundary', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000007', '36000000-0000-0000-0000-000000000007', 'STABLE', '36100000-0000-0000-0000-000000000007', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:space.change-feed')::uuid, 18, 'DEFINITION', '36000000-0000-0000-0000-000000000007', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000007","definitionKey":"space.change-feed","providerKey":"space","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:space.change-feed:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000008', 'home.space.response-queue', 'NATIVE', 'core.spaces', 'APP.SPACES', 1, 1, 'ACTIVE', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000008', 'space.response-queue', 'space-response-queue', 'core.spaces', 'dwp-space', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000008', '36000000-0000-0000-0000-000000000008', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"space.response-queue","owner":{"productKey":"core.spaces","sourceAppResourceKey":"APP.SPACES"},"renderer":{"kind":"NATIVE","rendererKey":"home.space.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.SPACES:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["SPACE.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.space.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 'home.space.response-queue', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:space.response-queue:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000008', 'MANIFEST', 'PASS', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 'fixture:wave4-owner-widget-manifests.v1:space.response-queue', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:space.response-queue:SECURITY')::uuid, '36100000-0000-0000-0000-000000000008', 'SECURITY', 'PASS', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#security', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:space.response-queue:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000008', 'PRIVACY', 'PASS', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#payload-boundary', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000008', '36000000-0000-0000-0000-000000000008', 'STABLE', '36100000-0000-0000-0000-000000000008', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:space.response-queue')::uuid, 19, 'DEFINITION', '36000000-0000-0000-0000-000000000008', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000008","definitionKey":"space.response-queue","providerKey":"space","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:space.response-queue:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000009', 'home.messaging.response-queue', 'NATIVE', 'core.messaging', 'APP.MESSAGING', 1, 1, 'ACTIVE', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000009', 'messaging.response-queue', 'messaging-response-queue', 'core.messaging', 'dwp-messaging', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000009', '36000000-0000-0000-0000-000000000009', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"messaging.response-queue","owner":{"productKey":"core.messaging","sourceAppResourceKey":"APP.MESSAGING"},"renderer":{"kind":"NATIVE","rendererKey":"home.messaging.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MESSAGING:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MESSAGING.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.messaging.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 'home.messaging.response-queue', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:messaging.response-queue:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000009', 'MANIFEST', 'PASS', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 'fixture:wave4-owner-widget-manifests.v1:messaging.response-queue', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:messaging.response-queue:SECURITY')::uuid, '36100000-0000-0000-0000-000000000009', 'SECURITY', 'PASS', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#security', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:messaging.response-queue:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000009', 'PRIVACY', 'PASS', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#payload-boundary', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000009', '36000000-0000-0000-0000-000000000009', 'STABLE', '36100000-0000-0000-0000-000000000009', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:messaging.response-queue')::uuid, 20, 'DEFINITION', '36000000-0000-0000-0000-000000000009', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000009","definitionKey":"messaging.response-queue","providerKey":"messaging","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:messaging.response-queue:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000010', 'home.messaging.change-feed', 'NATIVE', 'core.messaging', 'APP.MESSAGING', 1, 1, 'ACTIVE', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000010', 'messaging.change-feed', 'messaging-change-feed', 'core.messaging', 'dwp-messaging', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000010', '36000000-0000-0000-0000-000000000010', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"messaging.change-feed","owner":{"productKey":"core.messaging","sourceAppResourceKey":"APP.MESSAGING"},"renderer":{"kind":"NATIVE","rendererKey":"home.messaging.change-feed","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MESSAGING:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]},"configurationContract":null,"dataCapabilities":["MESSAGING.CHANGE_FEED.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.messaging.change-feed"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 'home.messaging.change-feed', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:messaging.change-feed:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000010', 'MANIFEST', 'PASS', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 'fixture:wave4-owner-widget-manifests.v1:messaging.change-feed', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:messaging.change-feed:SECURITY')::uuid, '36100000-0000-0000-0000-000000000010', 'SECURITY', 'PASS', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#security', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:messaging.change-feed:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000010', 'PRIVACY', 'PASS', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#payload-boundary', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000010', '36000000-0000-0000-0000-000000000010', 'STABLE', '36100000-0000-0000-0000-000000000010', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:messaging.change-feed')::uuid, 21, 'DEFINITION', '36000000-0000-0000-0000-000000000010', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000010","definitionKey":"messaging.change-feed","providerKey":"messaging","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:messaging.change-feed:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000011', 'home.hr.edu', 'NATIVE', 'core.people', 'APP.HCM', 1, 1, 'ACTIVE', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000011', 'hr.edu', 'hr-education', 'core.people', 'dwp-people', 'HIGH', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000011', '36000000-0000-0000-0000-000000000011', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"hr.edu","owner":{"productKey":"core.people","sourceAppResourceKey":"APP.HCM"},"renderer":{"kind":"NATIVE","rendererKey":"home.hr.edu","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.HCM:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["HR.EDU.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.hr.edu"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 'home.hr.edu', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:hr.edu:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000011', 'MANIFEST', 'PASS', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 'fixture:wave4-owner-widget-manifests.v1:hr.edu', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:hr.edu:SECURITY')::uuid, '36100000-0000-0000-0000-000000000011', 'SECURITY', 'PASS', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#security', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:hr.edu:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000011', 'PRIVACY', 'PASS', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#payload-boundary', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000011', '36000000-0000-0000-0000-000000000011', 'STABLE', '36100000-0000-0000-0000-000000000011', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:hr.edu')::uuid, 22, 'DEFINITION', '36000000-0000-0000-0000-000000000011', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000011","definitionKey":"hr.edu","providerKey":"people","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:hr.edu:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO plt_widget_renderer_bindings (renderer_binding_id, renderer_key, kind, owner_product_key, source_app_resource_key, minimum_host_api_version, maximum_host_api_version, binding_state, binding_revision)
VALUES ('36200000-0000-0000-0000-000000000012', 'home.hr.team-pulse', 'NATIVE', 'core.people', 'APP.HCM', 1, 1, 'ACTIVE', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key, risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES ('36000000-0000-0000-0000-000000000012', 'hr.team-pulse', 'hr-team-pulse', 'core.people', 'dwp-people', 'HIGH', 'RESTRICTED', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key, workflow_state, release_state, safety_state, immutable, attestation, certification_status, created_by, updated_by)
VALUES ('36100000-0000-0000-0000-000000000012', '36000000-0000-0000-0000-000000000012', '1.0.0', $json$ {"schemaVersion":1,"definitionKey":"hr.team-pulse","owner":{"productKey":"core.people","sourceAppResourceKey":"APP.HCM"},"renderer":{"kind":"NATIVE","rendererKey":"home.hr.team-pulse","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.HCM:VIEW","DATA.WORKFORCE:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["HR.TEAM_PULSE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.hr.team-pulse"},"privacy":{"classification":"RESTRICTED","retention":"NONE","recipientContextBinding":true}}$json$::jsonb, '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 'home.hr.team-pulse', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '{"source":"WAVE4_OWNER_PROVIDER_SHADOW","fixtureVersion":1}'::jsonb, 'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:hr.team-pulse:MANIFEST')::uuid, '36100000-0000-0000-0000-000000000012', 'MANIFEST', 'PASS', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 'fixture:wave4-owner-widget-manifests.v1:hr.team-pulse', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:hr.team-pulse:SECURITY')::uuid, '36100000-0000-0000-0000-000000000012', 'SECURITY', 'PASS', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#security', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_evidence (evidence_id, version_id, evidence_type, evidence_status, manifest_hash, evidence_ref, evidence_sha256, reviewed_by)
VALUES (md5('wave4-owner-evidence:hr.team-pulse:PRIVACY')::uuid, '36100000-0000-0000-0000-000000000012', 'PRIVACY', 'PASS', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#payload-boundary', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (release_channel_id, definition_id, channel, current_version_id, previous_version_id, version, updated_by)
VALUES ('36300000-0000-0000-0000-000000000012', '36000000-0000-0000-0000-000000000012', 'STABLE', '36100000-0000-0000-0000-000000000012', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (event_id, registry_revision, aggregate_type, aggregate_id, event_type, actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (md5('wave4-owner-event:hr.team-pulse')::uuid, 23, 'DEFINITION', '36000000-0000-0000-0000-000000000012', 'OWNER_WIDGET_PROVIDER_SEEDED', 1, 'flyway-v261', NULL, '{"versionId":"36100000-0000-0000-0000-000000000012","definitionKey":"hr.team-pulse","providerKey":"people","registryMode":"SHADOW"}'::jsonb, (SELECT jsonb_agg(md5('wave4-owner-evidence:hr.team-pulse:' || evidence_type)::text ORDER BY evidence_type) FROM (VALUES ('MANIFEST'),('SECURITY'),('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (policy_revision_id, tenant_id, definition_id, revision_number, policy_state, enabled, selector_type, channel, version_id, supported_surface_keys, audience_selector, required_widget, locked_configuration, sharing_policy, impact_revision, reason_code, reason_text, created_by)
SELECT md5('wave4-owner-widget-policy:' || tenant.tenant_id || ':' || definition.definition_id)::uuid,
       tenant.tenant_id, definition.definition_id, 1, 'PUBLISHED',
       definition.definition_key = 'notification.app-badges',
       'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb,
       '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb,
       FALSE, '{}'::jsonb, 'PRIVATE', NULL,
       CASE WHEN definition.definition_key = 'notification.app-badges'
            THEN 'WAVE4_APP_BADGE_PROJECTION' ELSE 'WAVE5_RENDERER_PENDING' END,
       CASE WHEN definition.definition_key = 'notification.app-badges'
            THEN 'Wave 4 authoritative app badge projection'
            ELSE 'Wave 4 owner provider registered; renderer activation deferred to Wave 5' END, 1
  FROM sys_service_tenants tenant
  JOIN plt_widget_definitions definition ON definition.definition_key IN (
        'approval.focus-queue',
        'approval.my-requests',
        'meetings.next-prep',
        'meetings.followup-candidates',
        'notification.app-badges',
        'notification.response-queue',
        'space.change-feed',
        'space.response-queue',
        'messaging.response-queue',
        'messaging.change-feed',
        'hr.edu',
        'hr.team-pulse')
 WHERE tenant.lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('wave4-owner-widget-policy-head:' || tenant.tenant_id || ':' || definition.definition_id)::uuid,
       tenant.tenant_id, definition.definition_id,
       md5('wave4-owner-widget-policy:' || tenant.tenant_id || ':' || definition.definition_id)::uuid, 0, 1
  FROM sys_service_tenants tenant
  JOIN plt_widget_definitions definition ON definition.definition_key IN (
        'approval.focus-queue',
        'approval.my-requests',
        'meetings.next-prep',
        'meetings.followup-candidates',
        'notification.app-badges',
        'notification.response-queue',
        'space.change-feed',
        'space.response-queue',
        'messaging.response-queue',
        'messaging.change-feed',
        'hr.edu',
        'hr.team-pulse')
 WHERE tenant.lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

UPDATE plt_widget_registry_state
   SET registry_revision = GREATEST(registry_revision, 23),
       policy_revision = GREATEST(policy_revision, 23),
       updated_at = CURRENT_TIMESTAMP
 WHERE environment = 'GLOBAL'
   AND migration_mode = 'SHADOW'
   AND runtime_activation_ready = FALSE;

DO $$
DECLARE
    definition_count INTEGER;
    version_count INTEGER;
    evidence_count INTEGER;
    binding_count INTEGER;
    channel_count INTEGER;
BEGIN
    SELECT count(*) INTO definition_count FROM plt_widget_definitions WHERE definition_key IN (
        'approval.focus-queue',
        'approval.my-requests',
        'meetings.next-prep',
        'meetings.followup-candidates',
        'notification.app-badges',
        'notification.response-queue',
        'space.change-feed',
        'space.response-queue',
        'messaging.response-queue',
        'messaging.change-feed',
        'hr.edu',
        'hr.team-pulse');
    SELECT count(*) INTO version_count FROM plt_widget_definition_versions version JOIN plt_widget_definitions definition ON definition.definition_id = version.definition_id WHERE definition.definition_key IN (
        'approval.focus-queue',
        'approval.my-requests',
        'meetings.next-prep',
        'meetings.followup-candidates',
        'notification.app-badges',
        'notification.response-queue',
        'space.change-feed',
        'space.response-queue',
        'messaging.response-queue',
        'messaging.change-feed',
        'hr.edu',
        'hr.team-pulse') AND version.semantic_version = '1.0.0' AND version.release_state = 'PUBLISHED' AND version.certification_status = 'NOT_RUN';
    SELECT count(*) INTO evidence_count FROM plt_widget_evidence evidence JOIN plt_widget_definition_versions version ON version.version_id = evidence.version_id JOIN plt_widget_definitions definition ON definition.definition_id = version.definition_id WHERE definition.definition_key IN (
        'approval.focus-queue',
        'approval.my-requests',
        'meetings.next-prep',
        'meetings.followup-candidates',
        'notification.app-badges',
        'notification.response-queue',
        'space.change-feed',
        'space.response-queue',
        'messaging.response-queue',
        'messaging.change-feed',
        'hr.edu',
        'hr.team-pulse') AND evidence.evidence_status = 'PASS' AND evidence.manifest_hash = version.manifest_hash;
    SELECT count(*) INTO binding_count FROM plt_widget_renderer_bindings WHERE renderer_key IN (
        'home.approval.focus-queue',
        'home.approval.my-requests',
        'home.meetings.next-prep',
        'home.meetings.followup-candidates',
        'home.notification.app-badges',
        'home.notification.response-queue',
        'home.space.change-feed',
        'home.space.response-queue',
        'home.messaging.response-queue',
        'home.messaging.change-feed',
        'home.hr.edu',
        'home.hr.team-pulse') AND kind = 'NATIVE' AND binding_state = 'ACTIVE';
    SELECT count(*) INTO channel_count FROM plt_widget_release_channels channel JOIN plt_widget_definitions definition ON definition.definition_id = channel.definition_id WHERE definition.definition_key IN (
        'approval.focus-queue',
        'approval.my-requests',
        'meetings.next-prep',
        'meetings.followup-candidates',
        'notification.app-badges',
        'notification.response-queue',
        'space.change-feed',
        'space.response-queue',
        'messaging.response-queue',
        'messaging.change-feed',
        'hr.edu',
        'hr.team-pulse') AND channel.channel = 'STABLE' AND channel.current_version_id IS NOT NULL;
    IF definition_count <> 12 OR version_count <> 12 OR evidence_count <> 36 OR binding_count <> 12 OR channel_count <> 12 THEN
        RAISE EXCEPTION 'Wave 4 owner widget Registry seed postcondition failed';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM (VALUES
            ('approval.focus-queue', '36000000-0000-0000-0000-000000000001', '36100000-0000-0000-0000-000000000001', '36200000-0000-0000-0000-000000000001', 'home.approval.focus-queue', 'core.approvals', 'APP.APPROVALS', 'focus-queue', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', $manifest$ {"schemaVersion":1,"definitionKey":"approval.focus-queue","owner":{"productKey":"core.approvals","sourceAppResourceKey":"APP.APPROVALS"},"renderer":{"kind":"NATIVE","rendererKey":"home.approval.focus-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["ACTION.APPROVAL_TASK:VIEW","APP.APPROVALS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"large","allowedSizes":["medium","large","full"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["APPROVAL.FOCUS_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.approval.focus-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('approval.my-requests', '36000000-0000-0000-0000-000000000002', '36100000-0000-0000-0000-000000000002', '36200000-0000-0000-0000-000000000002', 'home.approval.my-requests', 'core.approvals', 'APP.APPROVALS', 'my-requests', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', $manifest$ {"schemaVersion":1,"definitionKey":"approval.my-requests","owner":{"productKey":"core.approvals","sourceAppResourceKey":"APP.APPROVALS"},"renderer":{"kind":"NATIVE","rendererKey":"home.approval.my-requests","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["ACTION.APPROVAL_REQUEST:VIEW","APP.APPROVALS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["medium","large","full"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["APPROVAL.MY_REQUESTS.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.approval.my-requests"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('meetings.next-prep', '36000000-0000-0000-0000-000000000003', '36100000-0000-0000-0000-000000000003', '36200000-0000-0000-0000-000000000003', 'home.meetings.next-prep', 'core.meetings', 'APP.MEETINGS', 'meeting-next-prep', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', $manifest$ {"schemaVersion":1,"definitionKey":"meetings.next-prep","owner":{"productKey":"core.meetings","sourceAppResourceKey":"APP.MEETINGS"},"renderer":{"kind":"NATIVE","rendererKey":"home.meetings.next-prep","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MEETINGS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MEETINGS.NEXT_PREP.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meetings.next-prep"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('meetings.followup-candidates', '36000000-0000-0000-0000-000000000004', '36100000-0000-0000-0000-000000000004', '36200000-0000-0000-0000-000000000004', 'home.meetings.followup-candidates', 'core.meetings', 'APP.MEETINGS', 'meeting-followups', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', $manifest$ {"schemaVersion":1,"definitionKey":"meetings.followup-candidates","owner":{"productKey":"core.meetings","sourceAppResourceKey":"APP.MEETINGS"},"renderer":{"kind":"NATIVE","rendererKey":"home.meetings.followup-candidates","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MEETINGS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MEETINGS.FOLLOWUP_CANDIDATES.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meetings.followup-candidates"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('notification.app-badges', '36000000-0000-0000-0000-000000000005', '36100000-0000-0000-0000-000000000005', '36200000-0000-0000-0000-000000000005', 'home.notification.app-badges', 'core.notifications', 'APP.NOTIFICATIONS', 'application-dock', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', $manifest$ {"schemaVersion":1,"definitionKey":"notification.app-badges","owner":{"productKey":"core.notifications","sourceAppResourceKey":"APP.NOTIFICATIONS"},"renderer":{"kind":"NATIVE","rendererKey":"home.notification.app-badges","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.NOTIFICATIONS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"full","allowedSizes":["full"],"defaultHeight":"short","allowedHeights":["short"]},"configurationContract":null,"dataCapabilities":["NOTIFICATION.APP_BADGES.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.notification.app-badges"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, TRUE),
            ('notification.response-queue', '36000000-0000-0000-0000-000000000006', '36100000-0000-0000-0000-000000000006', '36200000-0000-0000-0000-000000000006', 'home.notification.response-queue', 'core.notifications', 'APP.NOTIFICATIONS', 'notification-response-queue', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', $manifest$ {"schemaVersion":1,"definitionKey":"notification.response-queue","owner":{"productKey":"core.notifications","sourceAppResourceKey":"APP.NOTIFICATIONS"},"renderer":{"kind":"NATIVE","rendererKey":"home.notification.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.NOTIFICATIONS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["NOTIFICATION.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.notification.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('space.change-feed', '36000000-0000-0000-0000-000000000007', '36100000-0000-0000-0000-000000000007', '36200000-0000-0000-0000-000000000007', 'home.space.change-feed', 'core.spaces', 'APP.SPACES', 'space-change-feed', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', $manifest$ {"schemaVersion":1,"definitionKey":"space.change-feed","owner":{"productKey":"core.spaces","sourceAppResourceKey":"APP.SPACES"},"renderer":{"kind":"NATIVE","rendererKey":"home.space.change-feed","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.SPACES:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]},"configurationContract":null,"dataCapabilities":["SPACE.CHANGE_FEED.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.space.change-feed"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('space.response-queue', '36000000-0000-0000-0000-000000000008', '36100000-0000-0000-0000-000000000008', '36200000-0000-0000-0000-000000000008', 'home.space.response-queue', 'core.spaces', 'APP.SPACES', 'space-response-queue', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', $manifest$ {"schemaVersion":1,"definitionKey":"space.response-queue","owner":{"productKey":"core.spaces","sourceAppResourceKey":"APP.SPACES"},"renderer":{"kind":"NATIVE","rendererKey":"home.space.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.SPACES:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["SPACE.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.space.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('messaging.response-queue', '36000000-0000-0000-0000-000000000009', '36100000-0000-0000-0000-000000000009', '36200000-0000-0000-0000-000000000009', 'home.messaging.response-queue', 'core.messaging', 'APP.MESSAGING', 'messaging-response-queue', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', $manifest$ {"schemaVersion":1,"definitionKey":"messaging.response-queue","owner":{"productKey":"core.messaging","sourceAppResourceKey":"APP.MESSAGING"},"renderer":{"kind":"NATIVE","rendererKey":"home.messaging.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MESSAGING:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MESSAGING.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.messaging.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('messaging.change-feed', '36000000-0000-0000-0000-000000000010', '36100000-0000-0000-0000-000000000010', '36200000-0000-0000-0000-000000000010', 'home.messaging.change-feed', 'core.messaging', 'APP.MESSAGING', 'messaging-change-feed', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', $manifest$ {"schemaVersion":1,"definitionKey":"messaging.change-feed","owner":{"productKey":"core.messaging","sourceAppResourceKey":"APP.MESSAGING"},"renderer":{"kind":"NATIVE","rendererKey":"home.messaging.change-feed","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MESSAGING:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]},"configurationContract":null,"dataCapabilities":["MESSAGING.CHANGE_FEED.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.messaging.change-feed"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('hr.edu', '36000000-0000-0000-0000-000000000011', '36100000-0000-0000-0000-000000000011', '36200000-0000-0000-0000-000000000011', 'home.hr.edu', 'core.people', 'APP.HCM', 'hr-education', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', $manifest$ {"schemaVersion":1,"definitionKey":"hr.edu","owner":{"productKey":"core.people","sourceAppResourceKey":"APP.HCM"},"renderer":{"kind":"NATIVE","rendererKey":"home.hr.edu","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.HCM:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["HR.EDU.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.hr.edu"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('hr.team-pulse', '36000000-0000-0000-0000-000000000012', '36100000-0000-0000-0000-000000000012', '36200000-0000-0000-0000-000000000012', 'home.hr.team-pulse', 'core.people', 'APP.HCM', 'hr-team-pulse', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', $manifest$ {"schemaVersion":1,"definitionKey":"hr.team-pulse","owner":{"productKey":"core.people","sourceAppResourceKey":"APP.HCM"},"renderer":{"kind":"NATIVE","rendererKey":"home.hr.team-pulse","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.HCM:VIEW","DATA.WORKFORCE:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["HR.TEAM_PULSE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.hr.team-pulse"},"privacy":{"classification":"RESTRICTED","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE)
          ) AS expected(
                definition_key, definition_id, version_id, renderer_binding_id, renderer_key,
                owner_product_key, source_app_resource_key, legacy_widget_key, manifest_hash,
                manifest, enabled_by_default)
          LEFT JOIN plt_widget_definitions definition
            ON definition.definition_key = expected.definition_key
          LEFT JOIN plt_widget_definition_versions version
            ON version.definition_id = definition.definition_id
           AND version.semantic_version = '1.0.0'
          LEFT JOIN plt_widget_renderer_bindings binding
            ON binding.renderer_key = expected.renderer_key
          LEFT JOIN plt_widget_release_channels channel
            ON channel.definition_id = definition.definition_id AND channel.channel = 'STABLE'
         WHERE definition.definition_id IS DISTINCT FROM expected.definition_id::uuid
            OR definition.owner_product_key IS DISTINCT FROM expected.owner_product_key
            OR definition.legacy_widget_key IS DISTINCT FROM expected.legacy_widget_key
            OR definition.definition_state IS DISTINCT FROM 'ACTIVE'
            OR version.version_id IS DISTINCT FROM expected.version_id::uuid
            OR version.manifest_hash IS DISTINCT FROM expected.manifest_hash
            OR version.manifest IS DISTINCT FROM expected.manifest
            OR version.renderer_key IS DISTINCT FROM expected.renderer_key
            OR version.release_state IS DISTINCT FROM 'PUBLISHED'
            OR version.safety_state IS DISTINCT FROM 'CLEAR'
            OR version.certification_status IS DISTINCT FROM 'NOT_RUN'
            OR binding.renderer_binding_id IS DISTINCT FROM expected.renderer_binding_id::uuid
            OR binding.owner_product_key IS DISTINCT FROM expected.owner_product_key
            OR binding.source_app_resource_key IS DISTINCT FROM expected.source_app_resource_key
            OR binding.binding_revision IS DISTINCT FROM expected.manifest_hash
            OR binding.binding_state IS DISTINCT FROM 'ACTIVE'
            OR channel.current_version_id IS DISTINCT FROM expected.version_id::uuid
    ) THEN
        RAISE EXCEPTION 'Wave 4 owner widget Registry exact contract postcondition failed';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM (VALUES
            ('36100000-0000-0000-0000-000000000001', 'MANIFEST', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 'fixture:wave4-owner-widget-manifests.v1:approval.focus-queue', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600'),
            ('36100000-0000-0000-0000-000000000001', 'SECURITY', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#security', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9'),
            ('36100000-0000-0000-0000-000000000001', 'PRIVACY', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#payload-boundary', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9'),
            ('36100000-0000-0000-0000-000000000002', 'MANIFEST', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 'fixture:wave4-owner-widget-manifests.v1:approval.my-requests', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12'),
            ('36100000-0000-0000-0000-000000000002', 'SECURITY', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#security', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9'),
            ('36100000-0000-0000-0000-000000000002', 'PRIVACY', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-approval-server/src/test/java/com/dwp/services/approval/home/ApprovalHomeWidgetProviderControllerTest.java#payload-boundary', '580964872904f99d6d07f76111249fa938aa6dad639150889cb64be2166bdeb9'),
            ('36100000-0000-0000-0000-000000000003', 'MANIFEST', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 'fixture:wave4-owner-widget-manifests.v1:meetings.next-prep', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71'),
            ('36100000-0000-0000-0000-000000000003', 'SECURITY', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#security', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b'),
            ('36100000-0000-0000-0000-000000000003', 'PRIVACY', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#payload-boundary', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b'),
            ('36100000-0000-0000-0000-000000000004', 'MANIFEST', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 'fixture:wave4-owner-widget-manifests.v1:meetings.followup-candidates', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b'),
            ('36100000-0000-0000-0000-000000000004', 'SECURITY', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#security', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b'),
            ('36100000-0000-0000-0000-000000000004', 'PRIVACY', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-meeting-server/src/test/java/com/dwp/services/meeting/videomeeting/home/MeetingHomeWidgetProviderControllerTest.java#payload-boundary', '04c6ce150dc77806e516535b91c645f4331c18957cfdbc76c1e08345f4adf45b'),
            ('36100000-0000-0000-0000-000000000005', 'MANIFEST', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 'fixture:wave4-owner-widget-manifests.v1:notification.app-badges', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930'),
            ('36100000-0000-0000-0000-000000000005', 'SECURITY', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#security', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74'),
            ('36100000-0000-0000-0000-000000000005', 'PRIVACY', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#payload-boundary', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74'),
            ('36100000-0000-0000-0000-000000000006', 'MANIFEST', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 'fixture:wave4-owner-widget-manifests.v1:notification.response-queue', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b'),
            ('36100000-0000-0000-0000-000000000006', 'SECURITY', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#security', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74'),
            ('36100000-0000-0000-0000-000000000006', 'PRIVACY', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-notification-server/src/test/java/com/dwp/services/notification/home/NotificationHomeWidgetProviderControllerTest.java#payload-boundary', 'cf3ca722228977b92d679f490e4208678d0fc6fb3381809c505e2fcf8d667c74'),
            ('36100000-0000-0000-0000-000000000007', 'MANIFEST', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 'fixture:wave4-owner-widget-manifests.v1:space.change-feed', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1'),
            ('36100000-0000-0000-0000-000000000007', 'SECURITY', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#security', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab'),
            ('36100000-0000-0000-0000-000000000007', 'PRIVACY', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#payload-boundary', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab'),
            ('36100000-0000-0000-0000-000000000008', 'MANIFEST', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 'fixture:wave4-owner-widget-manifests.v1:space.response-queue', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457'),
            ('36100000-0000-0000-0000-000000000008', 'SECURITY', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#security', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab'),
            ('36100000-0000-0000-0000-000000000008', 'PRIVACY', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-space-server/src/test/java/com/dwp/services/space/home/SpaceHomeWidgetProviderControllerTest.java#payload-boundary', '2f88dfa2c820313eaed234581c31f7baae4be556b4539c10878220277fd0a7ab'),
            ('36100000-0000-0000-0000-000000000009', 'MANIFEST', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 'fixture:wave4-owner-widget-manifests.v1:messaging.response-queue', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1'),
            ('36100000-0000-0000-0000-000000000009', 'SECURITY', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#security', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc'),
            ('36100000-0000-0000-0000-000000000009', 'PRIVACY', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#payload-boundary', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc'),
            ('36100000-0000-0000-0000-000000000010', 'MANIFEST', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 'fixture:wave4-owner-widget-manifests.v1:messaging.change-feed', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc'),
            ('36100000-0000-0000-0000-000000000010', 'SECURITY', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#security', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc'),
            ('36100000-0000-0000-0000-000000000010', 'PRIVACY', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-messaging-server/src/test/java/com/dwp/services/messaging/home/MessagingHomeWidgetProviderControllerTest.java#payload-boundary', '90d2adb4e477cc5ed47e841d86e3fa602ab2e7b2a9ec34213ccc992e822642cc'),
            ('36100000-0000-0000-0000-000000000011', 'MANIFEST', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 'fixture:wave4-owner-widget-manifests.v1:hr.edu', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8'),
            ('36100000-0000-0000-0000-000000000011', 'SECURITY', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#security', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca'),
            ('36100000-0000-0000-0000-000000000011', 'PRIVACY', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#payload-boundary', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca'),
            ('36100000-0000-0000-0000-000000000012', 'MANIFEST', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 'fixture:wave4-owner-widget-manifests.v1:hr.team-pulse', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df'),
            ('36100000-0000-0000-0000-000000000012', 'SECURITY', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#security', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca'),
            ('36100000-0000-0000-0000-000000000012', 'PRIVACY', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', 'git:6e453915088b0274eb87638cbaa30c468a796d24:dwp-people-server/src/test/java/com/dwp/services/people/home/PeopleHomeWidgetProviderControllerTest.java#payload-boundary', 'd0ae07f2aa44e7813e5fe7771f3747ac08f7cf4b8ff7bfe1f70c6ef6a816b0ca')
          ) AS expected(version_id, evidence_type, manifest_hash, evidence_ref, evidence_sha256)
          LEFT JOIN plt_widget_evidence evidence
            ON evidence.version_id = expected.version_id::uuid
           AND evidence.evidence_type = expected.evidence_type
         WHERE evidence.evidence_id IS NULL
            OR evidence.evidence_status IS DISTINCT FROM 'PASS'
            OR evidence.manifest_hash IS DISTINCT FROM expected.manifest_hash
            OR evidence.evidence_ref IS DISTINCT FROM expected.evidence_ref
            OR evidence.evidence_sha256 IS DISTINCT FROM expected.evidence_sha256
            OR evidence.expires_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Wave 4 owner widget backend evidence postcondition failed';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM sys_service_tenants tenant
          CROSS JOIN (VALUES
            ('approval.focus-queue', '36000000-0000-0000-0000-000000000001', '36100000-0000-0000-0000-000000000001', '36200000-0000-0000-0000-000000000001', 'home.approval.focus-queue', 'core.approvals', 'APP.APPROVALS', 'focus-queue', 'd203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600', $manifest$ {"schemaVersion":1,"definitionKey":"approval.focus-queue","owner":{"productKey":"core.approvals","sourceAppResourceKey":"APP.APPROVALS"},"renderer":{"kind":"NATIVE","rendererKey":"home.approval.focus-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["ACTION.APPROVAL_TASK:VIEW","APP.APPROVALS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"large","allowedSizes":["medium","large","full"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["APPROVAL.FOCUS_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.approval.focus-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('approval.my-requests', '36000000-0000-0000-0000-000000000002', '36100000-0000-0000-0000-000000000002', '36200000-0000-0000-0000-000000000002', 'home.approval.my-requests', 'core.approvals', 'APP.APPROVALS', 'my-requests', 'a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12', $manifest$ {"schemaVersion":1,"definitionKey":"approval.my-requests","owner":{"productKey":"core.approvals","sourceAppResourceKey":"APP.APPROVALS"},"renderer":{"kind":"NATIVE","rendererKey":"home.approval.my-requests","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["ACTION.APPROVAL_REQUEST:VIEW","APP.APPROVALS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["medium","large","full"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["APPROVAL.MY_REQUESTS.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.approval.my-requests"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('meetings.next-prep', '36000000-0000-0000-0000-000000000003', '36100000-0000-0000-0000-000000000003', '36200000-0000-0000-0000-000000000003', 'home.meetings.next-prep', 'core.meetings', 'APP.MEETINGS', 'meeting-next-prep', '12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71', $manifest$ {"schemaVersion":1,"definitionKey":"meetings.next-prep","owner":{"productKey":"core.meetings","sourceAppResourceKey":"APP.MEETINGS"},"renderer":{"kind":"NATIVE","rendererKey":"home.meetings.next-prep","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MEETINGS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MEETINGS.NEXT_PREP.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meetings.next-prep"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('meetings.followup-candidates', '36000000-0000-0000-0000-000000000004', '36100000-0000-0000-0000-000000000004', '36200000-0000-0000-0000-000000000004', 'home.meetings.followup-candidates', 'core.meetings', 'APP.MEETINGS', 'meeting-followups', '9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b', $manifest$ {"schemaVersion":1,"definitionKey":"meetings.followup-candidates","owner":{"productKey":"core.meetings","sourceAppResourceKey":"APP.MEETINGS"},"renderer":{"kind":"NATIVE","rendererKey":"home.meetings.followup-candidates","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MEETINGS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MEETINGS.FOLLOWUP_CANDIDATES.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meetings.followup-candidates"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('notification.app-badges', '36000000-0000-0000-0000-000000000005', '36100000-0000-0000-0000-000000000005', '36200000-0000-0000-0000-000000000005', 'home.notification.app-badges', 'core.notifications', 'APP.NOTIFICATIONS', 'application-dock', '99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930', $manifest$ {"schemaVersion":1,"definitionKey":"notification.app-badges","owner":{"productKey":"core.notifications","sourceAppResourceKey":"APP.NOTIFICATIONS"},"renderer":{"kind":"NATIVE","rendererKey":"home.notification.app-badges","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.NOTIFICATIONS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"full","allowedSizes":["full"],"defaultHeight":"short","allowedHeights":["short"]},"configurationContract":null,"dataCapabilities":["NOTIFICATION.APP_BADGES.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.notification.app-badges"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, TRUE),
            ('notification.response-queue', '36000000-0000-0000-0000-000000000006', '36100000-0000-0000-0000-000000000006', '36200000-0000-0000-0000-000000000006', 'home.notification.response-queue', 'core.notifications', 'APP.NOTIFICATIONS', 'notification-response-queue', '1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b', $manifest$ {"schemaVersion":1,"definitionKey":"notification.response-queue","owner":{"productKey":"core.notifications","sourceAppResourceKey":"APP.NOTIFICATIONS"},"renderer":{"kind":"NATIVE","rendererKey":"home.notification.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.NOTIFICATIONS:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["NOTIFICATION.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.notification.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('space.change-feed', '36000000-0000-0000-0000-000000000007', '36100000-0000-0000-0000-000000000007', '36200000-0000-0000-0000-000000000007', 'home.space.change-feed', 'core.spaces', 'APP.SPACES', 'space-change-feed', '679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1', $manifest$ {"schemaVersion":1,"definitionKey":"space.change-feed","owner":{"productKey":"core.spaces","sourceAppResourceKey":"APP.SPACES"},"renderer":{"kind":"NATIVE","rendererKey":"home.space.change-feed","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.SPACES:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]},"configurationContract":null,"dataCapabilities":["SPACE.CHANGE_FEED.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.space.change-feed"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('space.response-queue', '36000000-0000-0000-0000-000000000008', '36100000-0000-0000-0000-000000000008', '36200000-0000-0000-0000-000000000008', 'home.space.response-queue', 'core.spaces', 'APP.SPACES', 'space-response-queue', 'cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457', $manifest$ {"schemaVersion":1,"definitionKey":"space.response-queue","owner":{"productKey":"core.spaces","sourceAppResourceKey":"APP.SPACES"},"renderer":{"kind":"NATIVE","rendererKey":"home.space.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.SPACES:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["SPACE.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.space.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('messaging.response-queue', '36000000-0000-0000-0000-000000000009', '36100000-0000-0000-0000-000000000009', '36200000-0000-0000-0000-000000000009', 'home.messaging.response-queue', 'core.messaging', 'APP.MESSAGING', 'messaging-response-queue', '3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1', $manifest$ {"schemaVersion":1,"definitionKey":"messaging.response-queue","owner":{"productKey":"core.messaging","sourceAppResourceKey":"APP.MESSAGING"},"renderer":{"kind":"NATIVE","rendererKey":"home.messaging.response-queue","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MESSAGING:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["MESSAGING.RESPONSE_QUEUE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.messaging.response-queue"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('messaging.change-feed', '36000000-0000-0000-0000-000000000010', '36100000-0000-0000-0000-000000000010', '36200000-0000-0000-0000-000000000010', 'home.messaging.change-feed', 'core.messaging', 'APP.MESSAGING', 'messaging-change-feed', 'c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc', $manifest$ {"schemaVersion":1,"definitionKey":"messaging.change-feed","owner":{"productKey":"core.messaging","sourceAppResourceKey":"APP.MESSAGING"},"renderer":{"kind":"NATIVE","rendererKey":"home.messaging.change-feed","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.MESSAGING:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large","full"],"defaultHeight":"tall","allowedHeights":["short","standard","tall","expanded"]},"configurationContract":null,"dataCapabilities":["MESSAGING.CHANGE_FEED.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.messaging.change-feed"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('hr.edu', '36000000-0000-0000-0000-000000000011', '36100000-0000-0000-0000-000000000011', '36200000-0000-0000-0000-000000000011', 'home.hr.edu', 'core.people', 'APP.HCM', 'hr-education', '05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8', $manifest$ {"schemaVersion":1,"definitionKey":"hr.edu","owner":{"productKey":"core.people","sourceAppResourceKey":"APP.HCM"},"renderer":{"kind":"NATIVE","rendererKey":"home.hr.edu","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.HCM:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["HR.EDU.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.hr.edu"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE),
            ('hr.team-pulse', '36000000-0000-0000-0000-000000000012', '36100000-0000-0000-0000-000000000012', '36200000-0000-0000-0000-000000000012', 'home.hr.team-pulse', 'core.people', 'APP.HCM', 'hr-team-pulse', '9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df', $manifest$ {"schemaVersion":1,"definitionKey":"hr.team-pulse","owner":{"productKey":"core.people","sourceAppResourceKey":"APP.HCM"},"renderer":{"kind":"NATIVE","rendererKey":"home.hr.team-pulse","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.HCM:VIEW","DATA.WORKFORCE:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["HR.TEAM_PULSE.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.hr.team-pulse"},"privacy":{"classification":"RESTRICTED","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb, FALSE)
          ) AS expected(
                definition_key, definition_id, version_id, renderer_binding_id, renderer_key,
                owner_product_key, source_app_resource_key, legacy_widget_key, manifest_hash,
                manifest, enabled_by_default)
          LEFT JOIN adm_tenant_widget_policy_heads head
            ON head.tenant_id = tenant.tenant_id
           AND head.definition_id = expected.definition_id::uuid
          LEFT JOIN adm_tenant_widget_policy_revisions policy
            ON policy.policy_revision_id = head.current_revision_id
         WHERE tenant.lifecycle_state <> 'RETIRED'
           AND (policy.policy_revision_id IS NULL
             OR policy.policy_state IS DISTINCT FROM 'PUBLISHED'
             OR policy.enabled IS DISTINCT FROM expected.enabled_by_default
             OR policy.selector_type IS DISTINCT FROM 'CHANNEL'
             OR policy.channel IS DISTINCT FROM 'STABLE'
             OR policy.supported_surface_keys IS DISTINCT FROM '["workspace-home"]'::jsonb)
    ) THEN
        RAISE EXCEPTION 'Wave 4 owner widget tenant policy postcondition failed';
    END IF;
    IF EXISTS (SELECT 1 FROM plt_widget_registry_state WHERE environment = 'GLOBAL' AND (migration_mode <> 'SHADOW' OR runtime_activation_ready)) THEN
        RAISE EXCEPTION 'Wave 4 owner widget seed changed Registry authority';
    END IF;
END $$;
