-- Registers the two remaining Flow expressive-mesh contracts without changing Registry authority.
-- Workplace is an existing recipient-bound read model. DWAI.ON remains disabled and fail-closed
-- until its owner publishes an authenticated recipient-bound artifact endpoint.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM plt_widget_registry_state
         WHERE environment = 'GLOBAL'
           AND migration_mode = 'SHADOW'
           AND runtime_activation_ready = FALSE
    ) THEN
        RAISE EXCEPTION 'Flow expressive mesh registration requires SHADOW/non-authoritative Registry';
    END IF;
END $$;

INSERT INTO plt_widget_renderer_bindings (
    renderer_binding_id, renderer_key, kind, owner_product_key,
    source_app_resource_key, minimum_host_api_version, maximum_host_api_version,
    binding_state, binding_revision)
VALUES
    ('36600000-0000-0000-0000-000000000001', 'home.workplace.booking', 'NATIVE',
     'core.workplace', 'APP.WORKPLACE', 1, 1, 'ACTIVE',
     '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e'),
    ('36600000-0000-0000-0000-000000000002', 'home.dwaion.artifact', 'NATIVE',
     'ai.agent-runtime', 'APP.DWAION_ARTIFACTS', 1, 1, 'ACTIVE',
     'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7')
ON CONFLICT (renderer_key) DO NOTHING;

INSERT INTO plt_widget_definitions (
    definition_id, definition_key, legacy_widget_key, owner_product_key, owner_team_key,
    risk_tier, data_classification, definition_state, created_by, updated_by)
VALUES
    ('36400000-0000-0000-0000-000000000001', 'workplace.booking', 'workplace-booking',
     'core.workplace', 'dwp-platform', 'MEDIUM', 'CONFIDENTIAL', 'ACTIVE', 1, 1),
    ('36400000-0000-0000-0000-000000000002', 'dwaion.artifact', 'dwaion-artifact',
     'ai.agent-runtime', 'dwp-agent-runtime', 'HIGH', 'CONFIDENTIAL', 'ACTIVE', 1, 1)
ON CONFLICT (definition_key) DO NOTHING;

INSERT INTO plt_widget_definition_versions (
    version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key,
    workflow_state, release_state, safety_state, immutable, attestation,
    certification_status, created_by, updated_by)
VALUES
    ('36500000-0000-0000-0000-000000000001',
     '36400000-0000-0000-0000-000000000001', '1.0.0',
     $json$ {"schemaVersion":1,"definitionKey":"workplace.booking","owner":{"productKey":"core.workplace","sourceAppResourceKey":"APP.WORKPLACE"},"renderer":{"kind":"NATIVE","rendererKey":"home.workplace.booking","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.WORKPLACE:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["WORKPLACE.BOOKING.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.workplace.booking"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb,
     '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e',
     'home.workplace.booking', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE,
     '{"source":"WAVE6_RECIPIENT_BOUND_PLATFORM_PROVIDER","fixtureVersion":1}'::jsonb,
     'NOT_RUN', 1, 1),
    ('36500000-0000-0000-0000-000000000002',
     '36400000-0000-0000-0000-000000000002', '1.0.0',
     $json$ {"schemaVersion":1,"definitionKey":"dwaion.artifact","owner":{"productKey":"ai.agent-runtime","sourceAppResourceKey":"APP.DWAION_ARTIFACTS"},"renderer":{"kind":"NATIVE","rendererKey":"home.dwaion.artifact","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.DWAION_ARTIFACTS:VIEW"],"placement":{"supportedContexts":["FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["DWAION.ARTIFACT.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.dwaion.artifact"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb,
     'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7',
     'home.dwaion.artifact', 'APPROVED', 'PUBLISHED', 'CLEAR', TRUE,
     '{"source":"WAVE6_FAIL_CLOSED_OWNER_PLACEHOLDER","fixtureVersion":1,"providerState":"UNAVAILABLE"}'::jsonb,
     'NOT_RUN', 1, 1)
ON CONFLICT (definition_id, semantic_version) DO NOTHING;

INSERT INTO plt_widget_evidence (
    evidence_id, version_id, evidence_type, evidence_status, manifest_hash,
    evidence_ref, evidence_sha256, reviewed_by)
VALUES
    (md5('wave6-mesh-evidence:workplace.booking:MANIFEST')::uuid,
     '36500000-0000-0000-0000-000000000001', 'MANIFEST', 'PASS',
     '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e',
     'migration:V302:workplace.booking',
     '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e', 1),
    (md5('wave6-mesh-evidence:workplace.booking:SECURITY')::uuid,
     '36500000-0000-0000-0000-000000000001', 'SECURITY', 'PASS',
     '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e',
     'test:HomeNativeProviderContractTest#workplaceProjectionReadsRecipientBookingsAndProducesAValidatedResult',
     '61bb0aca4f8eafa02bcd67510bccf15cc67439ab6fc356725d6426a800a8a650', 1),
    (md5('wave6-mesh-evidence:workplace.booking:PRIVACY')::uuid,
     '36500000-0000-0000-0000-000000000001', 'PRIVACY', 'PASS',
     '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e',
     'test:HomeNativeProviderContractTest#workplaceProjectionReadsRecipientBookingsAndProducesAValidatedResult',
     '61bb0aca4f8eafa02bcd67510bccf15cc67439ab6fc356725d6426a800a8a650', 1),
    (md5('wave6-mesh-evidence:dwaion.artifact:MANIFEST')::uuid,
     '36500000-0000-0000-0000-000000000002', 'MANIFEST', 'PASS',
     'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7',
     'migration:V302:dwaion.artifact',
     'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7', 1),
    (md5('wave6-mesh-evidence:dwaion.artifact:SECURITY')::uuid,
     '36500000-0000-0000-0000-000000000002', 'SECURITY', 'PASS',
     'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7',
     'test:HomeNativeProviderContractTest#dwaionIsExplicitlyInactiveAndCannotLeakDataDuringWaveFour',
     '61bb0aca4f8eafa02bcd67510bccf15cc67439ab6fc356725d6426a800a8a650', 1),
    (md5('wave6-mesh-evidence:dwaion.artifact:PRIVACY')::uuid,
     '36500000-0000-0000-0000-000000000002', 'PRIVACY', 'PASS',
     'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7',
     'test:HomeNativeProviderContractTest#dwaionIsExplicitlyInactiveAndCannotLeakDataDuringWaveFour',
     '61bb0aca4f8eafa02bcd67510bccf15cc67439ab6fc356725d6426a800a8a650', 1)
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO plt_widget_release_channels (
    release_channel_id, definition_id, channel, current_version_id,
    previous_version_id, version, updated_by)
VALUES
    ('36700000-0000-0000-0000-000000000001',
     '36400000-0000-0000-0000-000000000001', 'STABLE',
     '36500000-0000-0000-0000-000000000001', NULL, 0, 1),
    ('36700000-0000-0000-0000-000000000002',
     '36400000-0000-0000-0000-000000000002', 'STABLE',
     '36500000-0000-0000-0000-000000000002', NULL, 0, 1)
ON CONFLICT (definition_id, channel) DO NOTHING;

INSERT INTO plt_widget_registry_events (
    event_id, registry_revision, aggregate_type, aggregate_id, event_type,
    actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
VALUES
    (md5('wave6-mesh-event:workplace.booking')::uuid, 24, 'DEFINITION',
     '36400000-0000-0000-0000-000000000001', 'OWNER_WIDGET_PROVIDER_SEEDED', 1,
     'flyway-v264', NULL,
     '{"versionId":"36500000-0000-0000-0000-000000000001","definitionKey":"workplace.booking","providerKey":"workplace","registryMode":"SHADOW"}'::jsonb,
     (SELECT jsonb_agg(md5('wave6-mesh-evidence:workplace.booking:' || evidence_type)::text
                       ORDER BY evidence_type)
        FROM (VALUES ('MANIFEST'), ('SECURITY'), ('PRIVACY')) evidence(evidence_type))),
    (md5('wave6-mesh-event:dwaion.artifact')::uuid, 25, 'DEFINITION',
     '36400000-0000-0000-0000-000000000002', 'FAIL_CLOSED_OWNER_SLOT_SEEDED', 1,
     'flyway-v264', NULL,
     '{"versionId":"36500000-0000-0000-0000-000000000002","definitionKey":"dwaion.artifact","providerKey":"dwaion","providerState":"UNAVAILABLE","registryMode":"SHADOW"}'::jsonb,
     (SELECT jsonb_agg(md5('wave6-mesh-evidence:dwaion.artifact:' || evidence_type)::text
                       ORDER BY evidence_type)
        FROM (VALUES ('MANIFEST'), ('SECURITY'), ('PRIVACY')) evidence(evidence_type)))
ON CONFLICT (event_id) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_revisions (
    policy_revision_id, tenant_id, definition_id, revision_number, policy_state,
    enabled, selector_type, channel, version_id, supported_surface_keys,
    audience_selector, required_widget, locked_configuration, sharing_policy,
    impact_revision, reason_code, reason_text, created_by)
SELECT md5('wave4-owner-widget-policy:' || tenant.tenant_id || ':' || definition.definition_id)::uuid,
       tenant.tenant_id, definition.definition_id, 1, 'PUBLISHED',
       definition.definition_key = 'workplace.booking',
       'CHANNEL', 'STABLE', NULL, '["workspace-home"]'::jsonb,
       '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb,
       FALSE, '{}'::jsonb, 'PRIVATE', NULL,
       CASE WHEN definition.definition_key = 'workplace.booking'
            THEN 'WAVE6_WORKPLACE_HOME_PROJECTION' ELSE 'WAVE6_OWNER_PROVIDER_PENDING' END,
       CASE WHEN definition.definition_key = 'workplace.booking'
            THEN 'Wave 6 recipient-bound Workplace booking projection'
            ELSE 'DWAI.ON Home slot stays disabled until its owner provider is available' END,
       1
  FROM sys_service_tenants tenant
  JOIN plt_widget_definitions definition
    ON definition.definition_key IN ('workplace.booking', 'dwaion.artifact')
 WHERE tenant.lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id, revision_number) DO NOTHING;

INSERT INTO adm_tenant_widget_policy_heads (
    policy_head_id, tenant_id, definition_id, current_revision_id, version, updated_by)
SELECT md5('wave4-owner-widget-policy-head:' || tenant.tenant_id || ':' || definition.definition_id)::uuid,
       tenant.tenant_id, definition.definition_id,
       md5('wave4-owner-widget-policy:' || tenant.tenant_id || ':' || definition.definition_id)::uuid,
       0, 1
  FROM sys_service_tenants tenant
  JOIN plt_widget_definitions definition
    ON definition.definition_key IN ('workplace.booking', 'dwaion.artifact')
 WHERE tenant.lifecycle_state <> 'RETIRED'
ON CONFLICT (tenant_id, definition_id) DO NOTHING;

UPDATE plt_widget_registry_state
   SET registry_revision = GREATEST(registry_revision, 25),
       policy_revision = GREATEST(policy_revision, 25),
       updated_at = CURRENT_TIMESTAMP
 WHERE environment = 'GLOBAL'
   AND migration_mode = 'SHADOW'
   AND runtime_activation_ready = FALSE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM (VALUES
            ('workplace.booking', '36400000-0000-0000-0000-000000000001',
             '36500000-0000-0000-0000-000000000001',
             '36600000-0000-0000-0000-000000000001',
             '36700000-0000-0000-0000-000000000001',
             'workplace-booking', 'core.workplace', 'APP.WORKPLACE',
             'home.workplace.booking',
             '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e',
             $manifest$ {"schemaVersion":1,"definitionKey":"workplace.booking","owner":{"productKey":"core.workplace","sourceAppResourceKey":"APP.WORKPLACE"},"renderer":{"kind":"NATIVE","rendererKey":"home.workplace.booking","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.WORKPLACE:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["WORKPLACE.BOOKING.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.workplace.booking"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb),
            ('dwaion.artifact', '36400000-0000-0000-0000-000000000002',
             '36500000-0000-0000-0000-000000000002',
             '36600000-0000-0000-0000-000000000002',
             '36700000-0000-0000-0000-000000000002',
             'dwaion-artifact', 'ai.agent-runtime', 'APP.DWAION_ARTIFACTS',
             'home.dwaion.artifact',
             'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7',
             $manifest$ {"schemaVersion":1,"definitionKey":"dwaion.artifact","owner":{"productKey":"ai.agent-runtime","sourceAppResourceKey":"APP.DWAION_ARTIFACTS"},"renderer":{"kind":"NATIVE","rendererKey":"home.dwaion.artifact","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.DWAION_ARTIFACTS:VIEW"],"placement":{"supportedContexts":["FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium","large"],"defaultHeight":"standard","allowedHeights":["short","standard","tall"]},"configurationContract":null,"dataCapabilities":["DWAION.ARTIFACT.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.dwaion.artifact"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$manifest$::jsonb)
          ) expected(
              definition_key, definition_id, version_id, binding_id, channel_id,
              legacy_key, owner_key, source_key, renderer_key, manifest_hash, manifest)
          LEFT JOIN plt_widget_definitions definition
            ON definition.definition_key = expected.definition_key
          LEFT JOIN plt_widget_definition_versions version
            ON version.definition_id = definition.definition_id
           AND version.semantic_version = '1.0.0'
          LEFT JOIN plt_widget_renderer_bindings binding
            ON binding.renderer_key = expected.renderer_key
          LEFT JOIN plt_widget_release_channels channel
            ON channel.definition_id = definition.definition_id
           AND channel.channel = 'STABLE'
         WHERE definition.definition_id IS DISTINCT FROM expected.definition_id::uuid
            OR definition.legacy_widget_key IS DISTINCT FROM expected.legacy_key
            OR definition.owner_product_key IS DISTINCT FROM expected.owner_key
            OR definition.definition_state IS DISTINCT FROM 'ACTIVE'
            OR version.version_id IS DISTINCT FROM expected.version_id::uuid
            OR version.manifest_hash IS DISTINCT FROM expected.manifest_hash
            OR version.manifest IS DISTINCT FROM expected.manifest
            OR version.renderer_key IS DISTINCT FROM expected.renderer_key
            OR version.release_state IS DISTINCT FROM 'PUBLISHED'
            OR version.safety_state IS DISTINCT FROM 'CLEAR'
            OR binding.renderer_binding_id IS DISTINCT FROM expected.binding_id::uuid
            OR binding.owner_product_key IS DISTINCT FROM expected.owner_key
            OR binding.source_app_resource_key IS DISTINCT FROM expected.source_key
            OR binding.binding_revision IS DISTINCT FROM expected.manifest_hash
            OR binding.binding_state IS DISTINCT FROM 'ACTIVE'
            OR channel.release_channel_id IS DISTINCT FROM expected.channel_id::uuid
            OR channel.current_version_id IS DISTINCT FROM expected.version_id::uuid
    ) OR EXISTS (
        SELECT 1
          FROM (VALUES
            ('workplace.booking', '36500000-0000-0000-0000-000000000001',
             '3da8f665137fd670411f87893af302133cea70fda9a402fce690f85686c2869e'),
            ('dwaion.artifact', '36500000-0000-0000-0000-000000000002',
             'a525bf1c6b926984a0978386f74aeb055c6e81db0813b9ea6b2443384de188e7')
          ) expected(definition_key, version_id, manifest_hash)
          CROSS JOIN (VALUES ('MANIFEST'), ('SECURITY'), ('PRIVACY')) kind(evidence_type)
          LEFT JOIN plt_widget_evidence evidence
            ON evidence.evidence_id = md5(
                'wave6-mesh-evidence:' || expected.definition_key || ':' || kind.evidence_type)::uuid
         WHERE evidence.version_id IS DISTINCT FROM expected.version_id::uuid
            OR evidence.evidence_type IS DISTINCT FROM kind.evidence_type
            OR evidence.evidence_status IS DISTINCT FROM 'PASS'
            OR evidence.manifest_hash IS DISTINCT FROM expected.manifest_hash
            OR evidence.evidence_sha256 IS DISTINCT FROM CASE
                WHEN kind.evidence_type = 'MANIFEST' THEN expected.manifest_hash
                ELSE '61bb0aca4f8eafa02bcd67510bccf15cc67439ab6fc356725d6426a800a8a650'
               END
            OR evidence.evidence_ref IS DISTINCT FROM CASE
                WHEN kind.evidence_type = 'MANIFEST'
                    THEN 'migration:V302:' || expected.definition_key
                WHEN expected.definition_key = 'workplace.booking'
                    THEN 'test:HomeNativeProviderContractTest#workplaceProjectionReadsRecipientBookingsAndProducesAValidatedResult'
                ELSE 'test:HomeNativeProviderContractTest#dwaionIsExplicitlyInactiveAndCannotLeakDataDuringWaveFour'
               END
            OR evidence.expires_at IS NOT NULL
    ) OR EXISTS (
        SELECT 1
          FROM sys_service_tenants tenant
          CROSS JOIN (VALUES
            ('workplace.booking', '36400000-0000-0000-0000-000000000001', TRUE),
            ('dwaion.artifact', '36400000-0000-0000-0000-000000000002', FALSE)
          ) expected(definition_key, definition_id, enabled)
          LEFT JOIN adm_tenant_widget_policy_heads head
            ON head.tenant_id = tenant.tenant_id
           AND head.definition_id = expected.definition_id::uuid
          LEFT JOIN adm_tenant_widget_policy_revisions policy
            ON policy.policy_revision_id = head.current_revision_id
         WHERE tenant.lifecycle_state <> 'RETIRED'
           AND (policy.policy_revision_id IS NULL
             OR policy.enabled IS DISTINCT FROM expected.enabled
             OR policy.policy_state IS DISTINCT FROM 'PUBLISHED'
             OR policy.selector_type IS DISTINCT FROM 'CHANNEL'
             OR policy.channel IS DISTINCT FROM 'STABLE'
             OR policy.supported_surface_keys IS DISTINCT FROM '["workspace-home"]'::jsonb)
    ) OR EXISTS (
        SELECT 1
          FROM (VALUES
            ('workplace.booking', 24), ('dwaion.artifact', 25)
          ) expected(definition_key, revision)
          LEFT JOIN plt_widget_registry_events event
            ON event.event_id = md5('wave6-mesh-event:' || expected.definition_key)::uuid
         WHERE event.registry_revision IS DISTINCT FROM expected.revision
            OR event.evidence_refs IS DISTINCT FROM (
                SELECT jsonb_agg(md5('wave6-mesh-evidence:' || expected.definition_key
                                     || ':' || evidence_type)::text ORDER BY evidence_type)
                  FROM (VALUES ('MANIFEST'), ('SECURITY'), ('PRIVACY')) evidence(evidence_type))
    ) OR EXISTS (
        SELECT 1 FROM plt_widget_registry_state
         WHERE environment = 'GLOBAL'
           AND (migration_mode <> 'SHADOW' OR runtime_activation_ready
                OR registry_revision < 25 OR policy_revision < 25)
    ) THEN
        RAISE EXCEPTION 'Flow expressive mesh provider registration postcondition failed';
    END IF;
END $$;
