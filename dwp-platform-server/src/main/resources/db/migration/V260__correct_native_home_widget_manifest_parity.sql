-- Forward-only canonical correction for three native Home manifests seeded by V257.
-- The immutable 1.0.0 rows and their evidence stay as history. Corrected 1.0.1 rows become
-- STABLE, and the superseded rows become BLOCKED with an explicit replacement link.
-- This migration accepts only the exact V257 pre-state or the exact V260 final state. Any
-- partial or operator-modified state aborts instead of being overwritten or silently accepted.
-- The registry remains SHADOW-only and runtime_activation_ready remains false.

DO $migration$
DECLARE
    command_rail_manifest JSONB := $json$ {"schemaVersion":1,"definitionKey":"core.workspace.command-rail","owner":{"productKey":"core.workspace","sourceAppResourceKey":"APP.WORK"},"renderer":{"kind":"NATIVE","rendererKey":"home.command-rail","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.WORK:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"large","allowedSizes":["large","full"],"defaultHeight":"short","allowedHeights":["short","standard"]},"configurationContract":null,"dataCapabilities":["HOME.OVERVIEW.READ"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.command-rail"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb;
    focus_balance_manifest JSONB := $json$ {"schemaVersion":1,"definitionKey":"core.work.focus-balance","owner":{"productKey":"core.calendar","sourceAppResourceKey":"APP.CALENDAR"},"renderer":{"kind":"NATIVE","rendererKey":"home.focus-balance","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.CALENDAR:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium"],"defaultHeight":"short","allowedHeights":["short","standard"]},"configurationContract":null,"dataCapabilities":["CALENDAR.EVENTS.LIST"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.focus-balance"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb;
    meeting_load_manifest JSONB := $json$ {"schemaVersion":1,"definitionKey":"core.calendar.meeting-load","owner":{"productKey":"core.calendar","sourceAppResourceKey":"APP.CALENDAR"},"renderer":{"kind":"NATIVE","rendererKey":"home.meeting-load","minimumHostApiVersion":1},"supportedSurfaces":["workspace-home"],"requiredAuthorities":["APP.CALENDAR:VIEW"],"placement":{"supportedContexts":["CLASSIC_PERSONAL","FLOW_PERSONAL"],"policyClass":"PERSONAL","canHide":true,"defaultSize":"medium","allowedSizes":["quarter","compact","medium"],"defaultHeight":"short","allowedHeights":["short","standard"]},"configurationContract":null,"dataCapabilities":["CALENDAR.EVENTS.LIST"],"actionCapabilities":[],"sharing":{"presetEligible":false},"operations":{"freshnessSeconds":30,"analyticsKey":"home.meeting-load"},"privacy":{"classification":"CONFIDENTIAL","retention":"NONE","recipientContextBinding":true}}$json$::jsonb;
    base_revision BIGINT;
    old_state_count INTEGER;
    final_state_count INTEGER;
    event_count INTEGER;
    collision_count INTEGER;
    affected INTEGER;
    final_event_revision BIGINT;
BEGIN
    SELECT registry_revision INTO base_revision
      FROM plt_widget_registry_state
     WHERE environment = 'GLOBAL'
       AND migration_mode = 'SHADOW'
       AND runtime_activation_ready = FALSE
     FOR UPDATE;
    IF base_revision IS NULL THEN
        RAISE EXCEPTION 'Wave 3 correction requires the locked SHADOW registry state';
    END IF;

    SELECT count(*) INTO final_state_count
      FROM (VALUES
        ('command-rail', '30000000-0000-0000-0000-000000000001', 'core.workspace.command-rail', 'core.workspace', '31000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000101', 'home.command-rail', 'APP.WORK', '36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13', '32000000-0000-0000-0000-000000000001', '34000000-0000-0000-0000-000000000101', '35000000-0000-0000-0000-000000000101', command_rail_manifest),
        ('focus-balance', '30000000-0000-0000-0000-000000000006', 'core.work.focus-balance', 'core.calendar', '31000000-0000-0000-0000-000000000006', '31000000-0000-0000-0000-000000000106', 'home.focus-balance', 'APP.CALENDAR', '5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436', '32000000-0000-0000-0000-000000000006', '34000000-0000-0000-0000-000000000106', '35000000-0000-0000-0000-000000000106', focus_balance_manifest),
        ('meeting-load', '30000000-0000-0000-0000-000000000007', 'core.calendar.meeting-load', 'core.calendar', '31000000-0000-0000-0000-000000000007', '31000000-0000-0000-0000-000000000107', 'home.meeting-load', 'APP.CALENDAR', '90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3', '32000000-0000-0000-0000-000000000007', '34000000-0000-0000-0000-000000000107', '35000000-0000-0000-0000-000000000107', meeting_load_manifest)
      ) AS expected(
          legacy_key, definition_id, definition_key, owner_key, old_version_id,
          new_version_id, renderer_key, source_key, manifest_hash, binding_id,
          evidence_id, event_id, manifest)
      JOIN plt_widget_definitions d
        ON d.definition_id = expected.definition_id::uuid
       AND d.definition_key = expected.definition_key
       AND d.legacy_widget_key = expected.legacy_key
       AND d.owner_product_key = expected.owner_key
       AND d.definition_state = 'ACTIVE'
      JOIN plt_widget_definition_versions old_version
        ON old_version.version_id = expected.old_version_id::uuid
       AND old_version.definition_id = d.definition_id
       AND old_version.semantic_version = '1.0.0'
       AND ((expected.legacy_key = 'command-rail'
               AND old_version.manifest_hash = 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97')
            OR (expected.legacy_key = 'focus-balance'
               AND old_version.manifest_hash = '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899')
            OR (expected.legacy_key = 'meeting-load'
               AND old_version.manifest_hash = '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2'))
       AND old_version.workflow_state = 'APPROVED'
       AND old_version.release_state = 'BLOCKED'
       AND old_version.safety_state = 'CLEAR'
       AND old_version.immutable
       AND old_version.replacement_version_id = expected.new_version_id::uuid
       AND old_version.attestation ->> 'source' = 'LEGACY_UNVERIFIED'
       AND old_version.certification_status = 'NOT_RUN'
      JOIN plt_widget_definition_versions new_version
        ON new_version.version_id = expected.new_version_id::uuid
       AND new_version.definition_id = d.definition_id
       AND new_version.semantic_version = '1.0.1'
       AND new_version.manifest = expected.manifest
       AND new_version.manifest_hash = expected.manifest_hash
       AND new_version.renderer_key = expected.renderer_key
       AND new_version.workflow_state = 'APPROVED'
       AND new_version.release_state = 'PUBLISHED'
       AND new_version.safety_state = 'CLEAR'
       AND new_version.immutable
       AND new_version.predecessor_version_id = old_version.version_id
       AND new_version.attestation ->> 'source' = 'LEGACY_UNVERIFIED'
       AND new_version.attestation ->> 'fixtureVersion' = '3'
       AND new_version.certification_status = 'NOT_RUN'
      JOIN plt_widget_renderer_bindings binding
        ON binding.renderer_binding_id = expected.binding_id::uuid
       AND binding.renderer_key = expected.renderer_key
       AND binding.kind = 'NATIVE'
       AND binding.owner_product_key = expected.owner_key
       AND binding.source_app_resource_key = expected.source_key
       AND binding.binding_state = 'ACTIVE'
       AND binding.binding_revision = expected.manifest_hash
      JOIN plt_widget_release_channels channel
        ON channel.definition_id = d.definition_id
       AND channel.channel = 'STABLE'
       AND channel.current_version_id = new_version.version_id
       AND channel.previous_version_id = old_version.version_id
      JOIN plt_widget_evidence evidence
        ON evidence.evidence_id = expected.evidence_id::uuid
       AND evidence.version_id = new_version.version_id
       AND evidence.evidence_type = 'MANIFEST'
       AND evidence.evidence_status = 'PASS'
       AND evidence.manifest_hash = expected.manifest_hash
       AND evidence.evidence_sha256 = expected.manifest_hash;

    SELECT count(*) INTO event_count
      FROM (VALUES
        ('35000000-0000-0000-0000-000000000101', '30000000-0000-0000-0000-000000000001',
         '31000000-0000-0000-0000-000000000101', '34000000-0000-0000-0000-000000000101',
         '36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13'),
        ('35000000-0000-0000-0000-000000000106', '30000000-0000-0000-0000-000000000006',
         '31000000-0000-0000-0000-000000000106', '34000000-0000-0000-0000-000000000106',
         '5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436'),
        ('35000000-0000-0000-0000-000000000107', '30000000-0000-0000-0000-000000000007',
         '31000000-0000-0000-0000-000000000107', '34000000-0000-0000-0000-000000000107',
         '90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3')
      ) AS expected(event_id, definition_id, version_id, evidence_id, manifest_hash)
      JOIN plt_widget_registry_events event
        ON event.event_id = expected.event_id::uuid
       AND event.aggregate_type = 'DEFINITION'
       AND event.aggregate_id = expected.definition_id
       AND event.event_type = 'NATIVE_WIDGET_MANIFEST_CORRECTED'
       AND event.actor_id = 1
       AND event.correlation_id = 'flyway-v260'
       AND event.after_snapshot ->> 'versionId' = expected.version_id
       AND event.after_snapshot ->> 'manifestHash' = expected.manifest_hash
       AND event.evidence_refs = jsonb_build_array(expected.evidence_id);

    IF final_state_count = 3 AND event_count = 3 THEN
        SELECT max(registry_revision) INTO final_event_revision
          FROM plt_widget_registry_events
         WHERE event_id IN (
             '35000000-0000-0000-0000-000000000101',
             '35000000-0000-0000-0000-000000000106',
             '35000000-0000-0000-0000-000000000107');
        IF base_revision < final_event_revision THEN
            RAISE EXCEPTION 'Wave 3 correction ledger head trails its correction events';
        END IF;
        RETURN;
    END IF;

    SELECT count(*) INTO old_state_count
      FROM (VALUES
        ('command-rail', '30000000-0000-0000-0000-000000000001', 'core.workspace.command-rail', 'core.workspace', '31000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000101', 'home.command-rail', 'APP.WORK', 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97', '32000000-0000-0000-0000-000000000001', '34000000-0000-0000-0000-000000000101', '35000000-0000-0000-0000-000000000101'),
        ('focus-balance', '30000000-0000-0000-0000-000000000006', 'core.work.focus-balance', 'core.work', '31000000-0000-0000-0000-000000000006', '31000000-0000-0000-0000-000000000106', 'home.focus-balance', 'APP.WORK', '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899', '32000000-0000-0000-0000-000000000006', '34000000-0000-0000-0000-000000000106', '35000000-0000-0000-0000-000000000106'),
        ('meeting-load', '30000000-0000-0000-0000-000000000007', 'core.calendar.meeting-load', 'core.calendar', '31000000-0000-0000-0000-000000000007', '31000000-0000-0000-0000-000000000107', 'home.meeting-load', 'APP.CALENDAR', '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2', '32000000-0000-0000-0000-000000000007', '34000000-0000-0000-0000-000000000107', '35000000-0000-0000-0000-000000000107')
      ) AS expected(
          legacy_key, definition_id, definition_key, owner_key, old_version_id,
          new_version_id, renderer_key, source_key, manifest_hash, binding_id,
          evidence_id, event_id)
      JOIN plt_widget_definitions d
        ON d.definition_id = expected.definition_id::uuid
       AND d.definition_key = expected.definition_key
       AND d.legacy_widget_key = expected.legacy_key
       AND d.owner_product_key = expected.owner_key
       AND d.definition_state = 'ACTIVE'
      JOIN plt_widget_definition_versions old_version
        ON old_version.version_id = expected.old_version_id::uuid
       AND old_version.definition_id = d.definition_id
       AND old_version.semantic_version = '1.0.0'
       AND old_version.manifest_hash = expected.manifest_hash
       AND old_version.workflow_state = 'APPROVED'
       AND old_version.release_state = 'PUBLISHED'
       AND old_version.safety_state = 'CLEAR'
       AND old_version.immutable
       AND old_version.replacement_version_id IS NULL
       AND old_version.attestation ->> 'source' = 'LEGACY_UNVERIFIED'
       AND old_version.certification_status = 'NOT_RUN'
      JOIN plt_widget_renderer_bindings binding
        ON binding.renderer_binding_id = expected.binding_id::uuid
       AND binding.renderer_key = expected.renderer_key
       AND binding.kind = 'NATIVE'
       AND binding.owner_product_key = expected.owner_key
       AND binding.source_app_resource_key = expected.source_key
       AND binding.binding_state = 'ACTIVE'
       AND binding.binding_revision = expected.manifest_hash
      JOIN plt_widget_release_channels channel
       ON channel.definition_id = d.definition_id
       AND channel.channel = 'STABLE'
       AND channel.current_version_id = old_version.version_id
       AND channel.previous_version_id IS NULL;

    SELECT count(*) INTO collision_count
      FROM plt_widget_definition_versions
     WHERE version_id IN (
         '31000000-0000-0000-0000-000000000101',
         '31000000-0000-0000-0000-000000000106',
         '31000000-0000-0000-0000-000000000107')
        OR (semantic_version = '1.0.1' AND definition_id IN (
         '30000000-0000-0000-0000-000000000001',
         '30000000-0000-0000-0000-000000000006',
         '30000000-0000-0000-0000-000000000007'));
    collision_count := collision_count
        + (SELECT count(*) FROM plt_widget_evidence WHERE evidence_id IN (
            '34000000-0000-0000-0000-000000000101',
            '34000000-0000-0000-0000-000000000106',
            '34000000-0000-0000-0000-000000000107'))
        + (SELECT count(*) FROM plt_widget_registry_events WHERE event_id IN (
            '35000000-0000-0000-0000-000000000101',
            '35000000-0000-0000-0000-000000000106',
            '35000000-0000-0000-0000-000000000107'));

    IF old_state_count <> 3 OR collision_count <> 0 THEN
        RAISE EXCEPTION 'Wave 3 native manifest correction precondition mismatch (old %, collisions %, final %, events %)',
            old_state_count, collision_count, final_state_count, event_count;
    END IF;

    UPDATE plt_widget_definitions
       SET owner_product_key = 'core.calendar', version = version + 1,
           updated_at = CURRENT_TIMESTAMP, updated_by = 1
     WHERE definition_id = '30000000-0000-0000-0000-000000000006'
       AND definition_key = 'core.work.focus-balance'
       AND owner_product_key = 'core.work';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'focus-balance ownership correction failed'; END IF;

    INSERT INTO plt_widget_definition_versions (
        version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key,
        workflow_state, release_state, safety_state, immutable, predecessor_version_id,
        attestation, certification_status, created_by, updated_by)
    VALUES ('31000000-0000-0000-0000-000000000101', '30000000-0000-0000-0000-000000000001', '1.0.1',
        command_rail_manifest, '36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13', 'home.command-rail',
        'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '31000000-0000-0000-0000-000000000001',
        '{"source":"LEGACY_UNVERIFIED","fixtureVersion":3,"correction":"WAVE3_CANONICAL_PARITY"}'::jsonb,
        'NOT_RUN', 1, 1);

    INSERT INTO plt_widget_evidence (
        evidence_id, version_id, evidence_type, evidence_status, manifest_hash,
        evidence_ref, evidence_sha256, reviewed_by)
    VALUES ('34000000-0000-0000-0000-000000000101', '31000000-0000-0000-0000-000000000101', 'MANIFEST', 'PASS', '36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13',
        'fixture:native-widget-manifests.v1:command-rail:1.0.1', '36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13', 1);

    UPDATE plt_widget_renderer_bindings
       SET owner_product_key = 'core.workspace', source_app_resource_key = 'APP.WORK',
           binding_revision = '36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13', version = version + 1,
           updated_at = CURRENT_TIMESTAMP
     WHERE renderer_binding_id = '32000000-0000-0000-0000-000000000001'
       AND renderer_key = 'home.command-rail'
       AND owner_product_key = 'core.workspace'
       AND source_app_resource_key = 'APP.WORK'
       AND binding_revision = 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'command-rail renderer correction failed'; END IF;

    UPDATE plt_widget_release_channels
       SET previous_version_id = '31000000-0000-0000-0000-000000000001',
           current_version_id = '31000000-0000-0000-0000-000000000101', version = version + 1,
           updated_at = CURRENT_TIMESTAMP, updated_by = 1
     WHERE definition_id = '30000000-0000-0000-0000-000000000001'
       AND channel = 'STABLE'
       AND current_version_id = '31000000-0000-0000-0000-000000000001';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'command-rail stable channel correction failed'; END IF;

    UPDATE plt_widget_definition_versions
       SET release_state = 'BLOCKED', replacement_version_id = '31000000-0000-0000-0000-000000000101',
           version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = 1
     WHERE version_id = '31000000-0000-0000-0000-000000000001'
       AND manifest_hash = 'a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97'
       AND release_state = 'PUBLISHED';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'command-rail superseded version block failed'; END IF;

    INSERT INTO plt_widget_definition_versions (
        version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key,
        workflow_state, release_state, safety_state, immutable, predecessor_version_id,
        attestation, certification_status, created_by, updated_by)
    VALUES ('31000000-0000-0000-0000-000000000106', '30000000-0000-0000-0000-000000000006', '1.0.1',
        focus_balance_manifest, '5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436', 'home.focus-balance',
        'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '31000000-0000-0000-0000-000000000006',
        '{"source":"LEGACY_UNVERIFIED","fixtureVersion":3,"correction":"WAVE3_CANONICAL_PARITY"}'::jsonb,
        'NOT_RUN', 1, 1);

    INSERT INTO plt_widget_evidence (
        evidence_id, version_id, evidence_type, evidence_status, manifest_hash,
        evidence_ref, evidence_sha256, reviewed_by)
    VALUES ('34000000-0000-0000-0000-000000000106', '31000000-0000-0000-0000-000000000106', 'MANIFEST', 'PASS', '5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436',
        'fixture:native-widget-manifests.v1:focus-balance:1.0.1', '5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436', 1);

    UPDATE plt_widget_renderer_bindings
       SET owner_product_key = 'core.calendar', source_app_resource_key = 'APP.CALENDAR',
           binding_revision = '5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436', version = version + 1,
           updated_at = CURRENT_TIMESTAMP
     WHERE renderer_binding_id = '32000000-0000-0000-0000-000000000006'
       AND renderer_key = 'home.focus-balance'
       AND owner_product_key = 'core.work'
       AND source_app_resource_key = 'APP.WORK'
       AND binding_revision = '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'focus-balance renderer correction failed'; END IF;

    UPDATE plt_widget_release_channels
       SET previous_version_id = '31000000-0000-0000-0000-000000000006',
           current_version_id = '31000000-0000-0000-0000-000000000106', version = version + 1,
           updated_at = CURRENT_TIMESTAMP, updated_by = 1
     WHERE definition_id = '30000000-0000-0000-0000-000000000006'
       AND channel = 'STABLE'
       AND current_version_id = '31000000-0000-0000-0000-000000000006';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'focus-balance stable channel correction failed'; END IF;

    UPDATE plt_widget_definition_versions
       SET release_state = 'BLOCKED', replacement_version_id = '31000000-0000-0000-0000-000000000106',
           version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = 1
     WHERE version_id = '31000000-0000-0000-0000-000000000006'
       AND manifest_hash = '10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899'
       AND release_state = 'PUBLISHED';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'focus-balance superseded version block failed'; END IF;

    INSERT INTO plt_widget_definition_versions (
        version_id, definition_id, semantic_version, manifest, manifest_hash, renderer_key,
        workflow_state, release_state, safety_state, immutable, predecessor_version_id,
        attestation, certification_status, created_by, updated_by)
    VALUES ('31000000-0000-0000-0000-000000000107', '30000000-0000-0000-0000-000000000007', '1.0.1',
        meeting_load_manifest, '90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3', 'home.meeting-load',
        'APPROVED', 'PUBLISHED', 'CLEAR', TRUE, '31000000-0000-0000-0000-000000000007',
        '{"source":"LEGACY_UNVERIFIED","fixtureVersion":3,"correction":"WAVE3_CANONICAL_PARITY"}'::jsonb,
        'NOT_RUN', 1, 1);

    INSERT INTO plt_widget_evidence (
        evidence_id, version_id, evidence_type, evidence_status, manifest_hash,
        evidence_ref, evidence_sha256, reviewed_by)
    VALUES ('34000000-0000-0000-0000-000000000107', '31000000-0000-0000-0000-000000000107', 'MANIFEST', 'PASS', '90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3',
        'fixture:native-widget-manifests.v1:meeting-load:1.0.1', '90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3', 1);

    UPDATE plt_widget_renderer_bindings
       SET owner_product_key = 'core.calendar', source_app_resource_key = 'APP.CALENDAR',
           binding_revision = '90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3', version = version + 1,
           updated_at = CURRENT_TIMESTAMP
     WHERE renderer_binding_id = '32000000-0000-0000-0000-000000000007'
       AND renderer_key = 'home.meeting-load'
       AND owner_product_key = 'core.calendar'
       AND source_app_resource_key = 'APP.CALENDAR'
       AND binding_revision = '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'meeting-load renderer correction failed'; END IF;

    UPDATE plt_widget_release_channels
       SET previous_version_id = '31000000-0000-0000-0000-000000000007',
           current_version_id = '31000000-0000-0000-0000-000000000107', version = version + 1,
           updated_at = CURRENT_TIMESTAMP, updated_by = 1
     WHERE definition_id = '30000000-0000-0000-0000-000000000007'
       AND channel = 'STABLE'
       AND current_version_id = '31000000-0000-0000-0000-000000000007';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'meeting-load stable channel correction failed'; END IF;

    UPDATE plt_widget_definition_versions
       SET release_state = 'BLOCKED', replacement_version_id = '31000000-0000-0000-0000-000000000107',
           version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = 1
     WHERE version_id = '31000000-0000-0000-0000-000000000007'
       AND manifest_hash = '17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2'
       AND release_state = 'PUBLISHED';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'meeting-load superseded version block failed'; END IF;

    INSERT INTO plt_widget_registry_events (
        event_id, registry_revision, aggregate_type, aggregate_id, event_type,
        actor_id, correlation_id, before_snapshot, after_snapshot, evidence_refs)
    VALUES
    ('35000000-0000-0000-0000-000000000101', base_revision + 1, 'DEFINITION', '30000000-0000-0000-0000-000000000001',
     'NATIVE_WIDGET_MANIFEST_CORRECTED', 1, 'flyway-v260',
     '{"versionId":"31000000-0000-0000-0000-000000000001","semanticVersion":"1.0.0","manifestHash":"a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97","releaseState":"PUBLISHED"}'::jsonb,
     '{"versionId":"31000000-0000-0000-0000-000000000101","semanticVersion":"1.0.1","manifestHash":"36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13","releaseState":"PUBLISHED","supersededVersionState":"BLOCKED"}'::jsonb,
     '["34000000-0000-0000-0000-000000000101"]'::jsonb),
    ('35000000-0000-0000-0000-000000000106', base_revision + 2, 'DEFINITION', '30000000-0000-0000-0000-000000000006',
     'NATIVE_WIDGET_MANIFEST_CORRECTED', 1, 'flyway-v260',
     '{"versionId":"31000000-0000-0000-0000-000000000006","semanticVersion":"1.0.0","manifestHash":"10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899","releaseState":"PUBLISHED"}'::jsonb,
     '{"versionId":"31000000-0000-0000-0000-000000000106","semanticVersion":"1.0.1","manifestHash":"5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436","releaseState":"PUBLISHED","supersededVersionState":"BLOCKED"}'::jsonb,
     '["34000000-0000-0000-0000-000000000106"]'::jsonb),
    ('35000000-0000-0000-0000-000000000107', base_revision + 3, 'DEFINITION', '30000000-0000-0000-0000-000000000007',
     'NATIVE_WIDGET_MANIFEST_CORRECTED', 1, 'flyway-v260',
     '{"versionId":"31000000-0000-0000-0000-000000000007","semanticVersion":"1.0.0","manifestHash":"17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2","releaseState":"PUBLISHED"}'::jsonb,
     '{"versionId":"31000000-0000-0000-0000-000000000107","semanticVersion":"1.0.1","manifestHash":"90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3","releaseState":"PUBLISHED","supersededVersionState":"BLOCKED"}'::jsonb,
     '["34000000-0000-0000-0000-000000000107"]'::jsonb);

    UPDATE plt_widget_registry_state
       SET registry_revision = base_revision + 3,
           updated_at = CURRENT_TIMESTAMP
     WHERE environment = 'GLOBAL'
       AND migration_mode = 'SHADOW'
       AND runtime_activation_ready = FALSE;
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN RAISE EXCEPTION 'Wave 3 registry ledger advance failed'; END IF;
END
$migration$;
