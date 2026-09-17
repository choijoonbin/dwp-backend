-- Publishes the immutable DWAI-ON Home provider contract after owner-side recipient,
-- least-data projection, replay, and activation-gate evidence became available.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM plt_widget_registry_state
         WHERE environment = 'GLOBAL'
           AND migration_mode = 'SHADOW'
           AND runtime_activation_ready = FALSE
    ) THEN
        RAISE EXCEPTION 'DWAI-ON Home provider activation requires SHADOW/non-authoritative Registry';
    END IF;
END $$;

INSERT INTO plt_widget_definition_versions (
    version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key,
    workflow_state, release_state, safety_state, immutable, attestation,
    certification_status, created_by, updated_by)
VALUES (
    '36500000-0000-0000-0000-000000000003',
    '36400000-0000-0000-0000-000000000002', '1.1.0',
    $json$ {"schemaVersion":1,"definitionKey":"dwaion.artifact","owner":{"productKey":"ai.agent-runtime","sourceAppResourceKey":"APP.DWAION_ARTIFACTS"},"renderer":{"kind":"NATIVE","rendererKey":"home.dwaion.artifact","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.ASK:VIEW","APP.DWAION_ARTIFACTS:VIEW"],"placement":{"supportedContexts":["FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["DWAION.ARTIFACT.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.dwaion.artifact"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb,
    'eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54',
    'home.dwaion.artifact', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE,
    '{"source":"DWAION_HOME_SIGNED_OWNER_PROVIDER","fixtureVersion":2,"authProfile":"dwp1-hmac-sha256","recipientBinding":true,"titleProjectionGate":true,"singleUseReplay":true}'::jsonb,
    'PASS', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

UPDATE plt_widget_renderer_bindings
   SET binding_revision = 'eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54',
       updated_at = CURRENT_TIMESTAMP
 WHERE renderer_key = 'home.dwaion.artifact'
   AND owner_product_key = 'ai.agent-runtime'
   AND source_app_resource_key = 'APP.DWAION_ARTIFACTS';

INSERT INTO plt_widget_evidence (
    evidence_id, version_id, evidence_type, evidence_status, manifest_hash,
    evidence_ref, evidence_sha256, reviewed_by)
VALUES
    (md5('dwaion-home-provider-v1.1:MANIFEST')::uuid,
     '36500000-0000-0000-0000-000000000003', 'MANIFEST', 'PASS',
     'eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54',
     'migration:V265:dwaion.artifact',
     'eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54', 1),
    (md5('dwaion-home-provider-v1.1:SECURITY')::uuid,
     '36500000-0000-0000-0000-000000000003', 'SECURITY', 'PASS',
     'eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54',
     'contract:contracts/home-runtime/dwaion-signed-workload.v1.json',
     'dd2ab8edc36e7d52db73d3f6ae954111819ad763742e23f24eb12986faae0482', 1),
    (md5('dwaion-home-provider-v1.1:PRIVACY')::uuid,
     '36500000-0000-0000-0000-000000000003', 'PRIVACY', 'PASS',
     'eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54',
     'git:7092a93ea7446d08af6e45bb88e7225dd9ed4148:tests/test_artifact_home_projection.py',
     '2b61d64f15ccbb14e680289c851221e4a2899517466178bfe061d4e0164d2fb3', 1)
ON CONFLICT (evidence_id) DO NOTHING;

UPDATE plt_widget_release_channels
   SET previous_version_id = current_version_id,
       current_version_id = '36500000-0000-0000-0000-000000000003',
       version = version + 1,
       updated_by = 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE definition_id = '36400000-0000-0000-0000-000000000002'
   AND channel = 'STABLE'
   AND current_version_id <> '36500000-0000-0000-0000-000000000003';

INSERT INTO adm_tenant_widget_policy_revisions (
    policy_revision_id, tenant_id, definition_id, revision_number, policy_state,
    enabled, selector_type, channel, version_id, supported_surface_keys,
    audience_selector, required_widget, locked_configuration, sharing_policy,
    impact_revision, reason_code, reason_text, created_by)
SELECT md5('dwaion-home-provider-v1.1-policy:' || tenant.tenant_id)::uuid,
       tenant.tenant_id, '36400000-0000-0000-0000-000000000002', 2, 'PUBLISHED',
       TRUE, 'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb,
       '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb,
       FALSE, '{}'::jsonb, 'PRIVATE', NULL,
       'DWAION_HOME_SIGNED_OWNER_PROVIDER',
       'Recipient-bound DWAI-ON title projection with signed single-use transport', 1
  FROM sys_service_tenants tenant
 WHERE tenant.lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

UPDATE adm_tenant_widget_policy_heads head
   SET current_revision_id = md5('dwaion-home-provider-v1.1-policy:' || head.tenant_id)::uuid,
       version = version + 1,
       updated_by = 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE definition_id = '36400000-0000-0000-0000-000000000002'
   AND current_revision_id <> md5('dwaion-home-provider-v1.1-policy:' || head.tenant_id)::uuid;

INSERT INTO plt_widget_registry_events (
    event_id, registry_revision, aggregate_type, aggregate_id, event_type,
    actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES (
    md5('dwaion-home-provider-v1.1-event')::uuid, 26, 'DEFINITION',
    '36400000-0000-0000-0000-000000000002', 'OWNER_WIDGET_PROVIDER_ACTIVATED', 1,
    'flyway-v265',
    '{"versionId":"36500000-0000-0000-0000-000000000002","providerState":"UNAVAILABLE"}'::jsonb,
    '{"versionId":"36500000-0000-0000-0000-000000000003","definitionKey":"dwaion.artifact","providerKey":"dwaion","authProfile":"dwp1-hmac-sha256","registryMode":"SHADOW"}'::jsonb,
    (SELECT jsonb_agg(md5('dwaion-home-provider-v1.1:' || evidence_type)::text
                      ORDER BY evidence_type)
       FROM (VALUES ('MANIFEST'), ('SECURITY'), ('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

UPDATE plt_widget_registry_state
   SET registry_revision = GREATEST(registry_revision, 26),
       policy_revision = GREATEST(policy_revision, 26),
       updated_at = CURRENT_TIMESTAMP
 WHERE environment = 'GLOBAL'
   AND migration_mode = 'SHADOW'
   AND runtime_activation_ready = FALSE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM plt_widget_definitions definition
          JOIN plt_widget_definition_versions version
            ON version.definition_id = definition.definition_id
          JOIN plt_widget_release_channels channel
            ON channel.definition_id = definition.definition_id
           AND channel.channel = 'STABLE'
          JOIN plt_widget_renderer_bindings binding
            ON binding.renderer_key = version.renderer_key
         WHERE definition.definition_key = 'dwaion.artifact'
           AND version.semantic_version = '1.1.0'
           AND version.manifest_hash = 'eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54'
           AND version.manifest -> 'requiredAuthorities'
               = '["APP.ASK:VIEW","APP.DWAION_ARTIFACTS:VIEW"]'::jsonb
           AND version.release_state = 'PUBLISHED'
           AND version.safety_state = 'CLEAR'
           AND version.immutable
           AND channel.current_version_id = version.version_id
           AND binding.binding_revision = version.manifest_hash
    ) OR EXISTS (
        SELECT 1
          FROM sys_service_tenants tenant
          LEFT JOIN adm_tenant_widget_policy_heads head
            ON head.tenant_id = tenant.tenant_id
           AND head.definition_id = '36400000-0000-0000-0000-000000000002'
          LEFT JOIN adm_tenant_widget_policy_revisions policy
            ON policy.policy_revision_id = head.current_revision_id
         WHERE tenant.lifecycle_state <> 'RETIRED'
           AND (policy.enabled IS DISTINCT FROM TRUE
             OR policy.policy_state IS DISTINCT FROM 'PUBLISHED'
             OR policy.revision_number IS DISTINCT FROM 2)
    ) THEN
        RAISE EXCEPTION 'Signed DWAI-ON Home provider activation postcondition failed';
    END IF;
END $$;
