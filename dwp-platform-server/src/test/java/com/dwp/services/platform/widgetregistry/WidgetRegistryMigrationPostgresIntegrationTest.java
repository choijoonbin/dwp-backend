package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.platform.provisioning.PlatformTenantProvisioningDtos;
import com.dwp.services.platform.provisioning.PlatformTenantProvisioningService;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryManifestContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WidgetRegistryMigrationPostgresIntegrationTest {
    private static final Path FIXTURE =
            Path.of("../contracts/widget-registry/native-widget-manifests.v1.json");
    private static final Path SEED = Path.of(
            "src/main/resources/db/migration/V257__seed_native_home_widget_registry.sql");
    private static final Path CANONICAL_CORRECTION = Path.of(
            "src/main/resources/db/migration/V260__correct_native_home_widget_manifest_parity.sql");
    private static final Path OWNER_FIXTURE =
            Path.of("../contracts/widget-registry/wave4-owner-widget-manifests.v1.json");
    private static final Path OWNER_SEED = Path.of(
            "src/main/resources/db/migration/V261__seed_wave4_owner_home_widget_providers.sql");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        jdbc = new JdbcTemplate(source);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void seedsNativeAndOwnerProviderContractsWithoutPromotingRegistryAuthority() throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(FIXTURE));
        assertThat(fixture.path("sourceGoldenFixture").path("exactFixtureCount").asInt()).isEqualTo(5);
        assertThat(fixture.path("sourceGoldenFixture").path("extensionCount").asInt()).isEqualTo(2);
        assertThat(fixture.path("fixtures")).hasSize(7);

        for (JsonNode expected : fixture.path("fixtures")) {
            JsonNode manifest = expected.path("manifest");
            String definitionKey = manifest.path("definitionKey").asText();
            String semanticVersion = expected.path("semanticVersion").asText();
            String rendererKey = manifest.path("renderer").path("rendererKey").asText();
            String expectedHash = expected.path("expectedSha256").asText();
            assertThat(WidgetRegistryManifestContract.validate(manifest).manifestHash())
                    .as(definitionKey)
                    .isEqualTo(expectedHash);
            var persisted = jdbc.queryForMap("""
                    SELECT d.legacy_widget_key, d.owner_product_key AS definition_owner,
                           v.semantic_version, v.manifest_hash, v.certification_status,
                           v.manifest::text AS manifest_json,
                           v.attestation ->> 'source' AS attestation_source,
                           b.kind, b.binding_state, b.renderer_key,
                           b.owner_product_key AS binding_owner,
                           b.source_app_resource_key AS binding_source
                      FROM plt_widget_definitions d
                      JOIN plt_widget_definition_versions v ON v.definition_id = d.definition_id
                      JOIN plt_widget_renderer_bindings b ON b.renderer_key = v.renderer_key
                     WHERE d.definition_key = ? AND v.semantic_version = ?
                    """, definitionKey, semanticVersion);
            assertThat(persisted)
                    .containsEntry("legacy_widget_key", expected.path("legacyWidgetKey").asText())
                    .containsEntry("definition_owner", manifest.path("owner").path("productKey").asText())
                    .containsEntry("semantic_version", semanticVersion)
                    .containsEntry("manifest_hash", expectedHash)
                    .containsEntry("certification_status", "NOT_RUN")
                    .containsEntry("attestation_source", "LEGACY_UNVERIFIED")
                    .containsEntry("kind", "NATIVE")
                    .containsEntry("binding_state", "ACTIVE")
                    .containsEntry("renderer_key", rendererKey)
                    .containsEntry("binding_owner", manifest.path("owner").path("productKey").asText())
                    .containsEntry("binding_source",
                            manifest.path("owner").path("sourceAppResourceKey").asText());
            assertThat(objectMapper.readTree((String) persisted.get("manifest_json")))
                    .as(definitionKey + " persisted manifest")
                    .isEqualTo(manifest);
        }

        assertOwnerProviderFixturePersisted();

        assertThat(count("plt_widget_definitions")).isEqualTo(19);
        assertThat(count("plt_widget_definition_versions")).isEqualTo(22);
        assertThat(count("plt_widget_renderer_bindings")).isEqualTo(19);
        assertThat(count("plt_widget_release_channels")).isEqualTo(19);
        assertThat(count("plt_widget_evidence")).isEqualTo(46);
        long activeTenants = jdbc.queryForObject(
                "SELECT count(*) FROM sys_service_tenants WHERE lifecycle_state <> 'RETIRED'",
                Long.class);
        assertThat(count("adm_tenant_widget_policy_heads")).isEqualTo(activeTenants * 19);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM adm_tenant_widget_policy_revisions
                 WHERE audience_selector = '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}'::jsonb
                """, Long.class)).isEqualTo(activeTenants * 19);
        assertThat(jdbc.queryForMap("""
                SELECT migration_mode, runtime_activation_ready, registry_revision
                  FROM plt_widget_registry_state WHERE environment = 'GLOBAL'
                """))
                .containsEntry("migration_mode", "SHADOW")
                .containsEntry("runtime_activation_ready", false)
                .containsEntry("registry_revision", 23L);

        assertThat(jdbc.queryForList("""
                SELECT d.legacy_widget_key, v.semantic_version
                  FROM plt_widget_release_channels c
                  JOIN plt_widget_definitions d ON d.definition_id = c.definition_id
                  JOIN plt_widget_definition_versions v ON v.version_id = c.current_version_id
                 WHERE d.legacy_widget_key IN ('command-rail', 'focus-balance', 'meeting-load')
                 ORDER BY d.legacy_widget_key
                """))
                .hasSize(3)
                .allSatisfy(row -> assertThat(row).containsEntry("semantic_version", "1.0.1"));

        assertThat(jdbc.queryForList("""
                SELECT semantic_version, release_state, replacement_version_id::text AS replacement
                  FROM plt_widget_definition_versions
                 WHERE version_id IN (
                    '31000000-0000-0000-0000-000000000001',
                    '31000000-0000-0000-0000-000000000006',
                    '31000000-0000-0000-0000-000000000007')
                 ORDER BY version_id
                """))
                .hasSize(3)
                .allSatisfy(row -> {
                    assertThat(row).containsEntry("semantic_version", "1.0.0")
                            .containsEntry("release_state", "BLOCKED");
                    assertThat(row.get("replacement")).as("replacement version").isNotNull();
                });
    }

    @Test
    void seedScriptIsIdempotentAndCannotRewriteCustomizedRows() throws Exception {
        List<Long> before = stateVector();
        jdbc.execute(Files.readString(SEED));
        jdbc.execute(Files.readString(CANONICAL_CORRECTION));
        jdbc.execute(Files.readString(OWNER_SEED));
        assertThat(stateVector()).containsExactlyElementsOf(before);
    }

    @Test
    void correctionRerunRejectsAPartialOrTamperedFinalState() throws Exception {
        jdbc.update("""
                UPDATE plt_widget_renderer_bindings
                   SET source_app_resource_key = 'APP.WORK'
                 WHERE renderer_key = 'home.focus-balance'
                """);

        try {
            assertThatThrownBy(() -> jdbc.execute(Files.readString(CANONICAL_CORRECTION)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("Wave 3 native manifest correction precondition mismatch");
        } finally {
            jdbc.update("""
                    UPDATE plt_widget_renderer_bindings
                       SET source_app_resource_key = 'APP.CALENDAR'
                     WHERE renderer_key = 'home.focus-balance'
                    """);
        }
    }

    @Test
    void postMigrationTenantProvisioningSeedsBaselineAndOwnerShadowPoliciesIdempotently() {
        long tenantId = 92570L;
        var request = new PlatformTenantProvisioningDtos.ProvisionTenantRequest(
                UUID.fromString("92570000-0000-0000-0000-000000000001"), tenantId,
                "wave-three-new", "Wave Three New Tenant", "local", "POOL", "ko",
                List.of("core.workspace"));
        var provisioning = new PlatformTenantProvisioningService(
                jdbc, Path.of(System.getProperty("java.io.tmpdir"), "dwp-wave3-provisioning").toString(),
                objectMapper);

        provisioning.provision(request);
        provisioning.provision(request);

        assertThat(jdbc.queryForList("""
                SELECT d.legacy_widget_key
                  FROM adm_tenant_widget_policy_heads h
                  JOIN plt_widget_definitions d ON d.definition_id = h.definition_id
                 WHERE h.tenant_id = ?
                 ORDER BY d.legacy_widget_key
                """, String.class, tenantId)).containsExactly(
                        "activity", "application-dock", "command-rail", "daily-brief", "focus",
                        "focus-balance", "focus-queue", "hr-education", "hr-team-pulse",
                        "meeting-followups", "meeting-load", "meeting-next-prep",
                        "messaging-change-feed", "messaging-response-queue", "my-requests",
                        "notification-response-queue", "schedule", "space-change-feed",
                        "space-response-queue");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM adm_tenant_widget_policy_revisions r
                 WHERE r.tenant_id = ? AND r.revision_number = 1
                   AND r.policy_state = 'PUBLISHED' AND r.enabled
                """, Integer.class, tenantId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM adm_tenant_widget_policy_revisions r
                 WHERE r.tenant_id = ? AND r.revision_number = 1
                   AND r.policy_state = 'PUBLISHED' AND NOT r.enabled
                """, Integer.class, tenantId)).isEqualTo(11);
        assertThat(jdbc.queryForObject("""
                SELECT required_widget
                  FROM adm_tenant_widget_policy_revisions r
                  JOIN plt_widget_definitions d ON d.definition_id = r.definition_id
                 WHERE r.tenant_id = ? AND d.legacy_widget_key = 'command-rail'
                """, Boolean.class, tenantId)).isTrue();
    }

    private void assertOwnerProviderFixturePersisted() throws Exception {
        JsonNode fixture = objectMapper.readTree(Files.readString(OWNER_FIXTURE));
        assertThat(fixture.path("fixtures")).hasSize(12);
        for (JsonNode expected : fixture.path("fixtures")) {
            JsonNode manifest = expected.path("manifest");
            String definitionKey = manifest.path("definitionKey").asText();
            String hash = expected.path("expectedSha256").asText();
            assertThat(WidgetRegistryManifestContract.validate(manifest).manifestHash())
                    .as(definitionKey).isEqualTo(hash);
            assertThat(jdbc.queryForMap("""
                    SELECT d.definition_id::text AS definition_id,
                           d.legacy_widget_key, d.owner_product_key,
                           v.version_id::text AS version_id, v.manifest_hash,
                           v.certification_status, v.attestation ->> 'source' AS source,
                           b.renderer_binding_id::text AS renderer_binding_id,
                           b.binding_revision, b.source_app_resource_key
                      FROM plt_widget_definitions d
                      JOIN plt_widget_definition_versions v ON v.definition_id = d.definition_id
                      JOIN plt_widget_renderer_bindings b ON b.renderer_key = v.renderer_key
                     WHERE d.definition_key = ? AND v.semantic_version = '1.0.0'
                    """, definitionKey))
                    .containsEntry("definition_id", expected.path("definitionId").asText())
                    .containsEntry("legacy_widget_key", expected.path("legacyWidgetKey").asText())
                    .containsEntry("owner_product_key",
                            manifest.path("owner").path("productKey").asText())
                    .containsEntry("version_id", expected.path("versionId").asText())
                    .containsEntry("manifest_hash", hash)
                    .containsEntry("certification_status", "NOT_RUN")
                    .containsEntry("source", "WAVE4_OWNER_PROVIDER_SHADOW")
                    .containsEntry("renderer_binding_id",
                            expected.path("rendererBindingId").asText())
                    .containsEntry("binding_revision", hash)
                    .containsEntry("source_app_resource_key",
                            manifest.path("owner").path("sourceAppResourceKey").asText());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM plt_widget_evidence e
                    JOIN plt_widget_definition_versions v ON v.version_id = e.version_id
                    JOIN plt_widget_definitions d ON d.definition_id = v.definition_id
                     WHERE d.definition_key = ? AND e.evidence_status = 'PASS'
                       AND e.evidence_type IN ('MANIFEST', 'SECURITY', 'PRIVACY')
                    """, Integer.class, definitionKey)).isEqualTo(3);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM plt_widget_evidence e
                JOIN plt_widget_definition_versions v ON v.version_id = e.version_id
                JOIN plt_widget_definitions d ON d.definition_id = v.definition_id
                 WHERE d.definition_key IN (
                    'approval.focus-queue', 'approval.my-requests',
                    'meetings.next-prep', 'meetings.followup-candidates',
                    'notification.app-badges', 'notification.response-queue',
                    'space.change-feed', 'space.response-queue',
                    'messaging.response-queue', 'messaging.change-feed',
                    'hr.edu', 'hr.team-pulse')
                   AND e.evidence_type IN ('A11Y', 'PERFORMANCE', 'LOCALIZATION')
                """, Integer.class)).isZero();
    }

    @Test
    void databaseRejectsRemoteCodeInvalidKeysAndInvalidSemanticVersions() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO plt_widget_renderer_bindings (
                    renderer_binding_id, renderer_key, kind, owner_product_key,
                    source_app_resource_key, minimum_host_api_version,
                    maximum_host_api_version, binding_state, binding_revision)
                VALUES (?, 'https://evil.invalid/widget.js', 'NATIVE', 'evil', 'APP.WORK',
                        1, 1, 'ACTIVE', ?)
                """, UUID.randomUUID(), "a".repeat(64)))
                .isInstanceOf(DataAccessException.class);

        UUID definitionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plt_widget_definitions (
                    definition_id, definition_key, owner_product_key, owner_team_key,
                    risk_tier, data_classification, definition_state)
                VALUES (?, 'a-b.c1', 'core.work', 'home', 'LOW', 'INTERNAL', 'ACTIVE')
                """, definitionId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO plt_widget_definitions (
                    definition_id, definition_key, owner_product_key, owner_team_key,
                    risk_tier, data_classification, definition_state)
                VALUES (?, 'Bad..Key', 'core.work', 'home', 'LOW', 'INTERNAL', 'ACTIVE')
                """, UUID.randomUUID())).isInstanceOf(DataAccessException.class);

        insertVersion(definitionId, "1.2.3-alpha.1+build.5", "b".repeat(64));
        for (String invalid : List.of("01.2.3", "1.02.3", "1.2.03", "1.2.3-01", "1.2.3-alpha..1")) {
            assertThatThrownBy(() -> insertVersion(definitionId, invalid,
                    Integer.toHexString(invalid.hashCode()).replace("-", "0").repeat(64).substring(0, 64)))
                    .as(invalid)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void databaseRejectsOpenEndedAudienceSelectors() {
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE adm_tenant_widget_policy_revisions
                   SET audience_selector = '{"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[],"query":"*"}'::jsonb
                 WHERE policy_revision_id = (
                    SELECT policy_revision_id FROM adm_tenant_widget_policy_revisions LIMIT 1)
                """)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void immutableContentEvidenceLedgerReceiptsAndActivationInterlockAreDatabaseEnforced() {
        UUID seededVersion = UUID.fromString("31000000-0000-0000-0000-000000000001");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE plt_widget_definition_versions SET manifest = '{"changed":true}'::jsonb
                 WHERE version_id = ?
                """, seededVersion)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE plt_widget_evidence SET evidence_status = 'FAIL'
                 WHERE evidence_id = '34000000-0000-0000-0000-000000000001'
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM plt_widget_registry_events
                 WHERE event_id = '35000000-0000-0000-0000-000000000001'
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE plt_widget_registry_state
                   SET migration_mode = 'AUTHORITATIVE', runtime_activation_ready = TRUE
                 WHERE environment = 'GLOBAL'
                """)).isInstanceOf(DataAccessException.class);
    }

    private static void insertVersion(UUID definitionId, String semanticVersion, String hash) {
        jdbc.update("""
                INSERT INTO plt_widget_definition_versions (
                    version_id, definition_id, semantic_version, manifest, manifest_hash,
                    renderer_key, workflow_state, release_state, safety_state, immutable,
                    attestation, certification_status)
                VALUES (?, ?, ?, '{}'::jsonb, ?, 'home.focus', 'DRAFT', 'UNPUBLISHED',
                        'CLEAR', FALSE, '{}'::jsonb, 'NOT_RUN')
                """, UUID.randomUUID(), definitionId, semanticVersion, hash);
    }

    private static long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static List<Long> stateVector() {
        return List.of(
                count("plt_widget_definitions"), count("plt_widget_definition_versions"),
                count("plt_widget_renderer_bindings"), count("plt_widget_release_channels"),
                count("plt_widget_evidence"), count("adm_tenant_widget_policy_revisions"),
                count("adm_tenant_widget_policy_heads"), count("plt_widget_registry_events"),
                jdbc.queryForObject("SELECT sum(version) FROM plt_widget_definitions", Long.class),
                jdbc.queryForObject("SELECT sum(version) FROM plt_widget_renderer_bindings", Long.class),
                jdbc.queryForObject("SELECT sum(version) FROM plt_widget_release_channels", Long.class));
    }
}
