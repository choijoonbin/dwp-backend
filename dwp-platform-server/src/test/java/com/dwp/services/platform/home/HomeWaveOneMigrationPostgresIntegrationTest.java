package com.dwp.services.platform.home;

import com.dwp.services.platform.provisioning.PlatformTenantProvisioningDtos;
import com.dwp.services.platform.provisioning.PlatformTenantProvisioningService;
import com.dwp.services.platform.home.personalization.HomeModeV4ActivationCoordinator;
import com.dwp.services.platform.home.personalization.HomeViewCompatibilityBridge;
import com.dwp.services.platform.home.preference.HomeLayoutPolicy;
import com.dwp.services.platform.home.preference.HomePreference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class HomeWaveOneMigrationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migratedDefaultsAndPostMigrationTenantsShareTheCanonicalContracts() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway throughV253 = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("253")
                .cleanDisabled(false)
                .load();
        throughV253.clean();
        throughV253.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        jdbc.update("""
                INSERT INTO adm_home_experiences (tenant_id)
                VALUES (1)
                ON CONFLICT (tenant_id) DO NOTHING
                """);
        jdbc.update("""
                UPDATE adm_home_experiences
                   SET launchpad_configuration = ?::jsonb,
                       composition_policy = ?::jsonb
                 WHERE tenant_id = 1
                """, """
                {
                  "schemaVersion":1,
                  "extensionMarker":"preserve-me",
                  "groups":[
                    {"groupKey":"work","labels":{"ko":"구형","en":"Legacy"},"descriptions":{},"sortOrder":90,"enabled":true},
                    {"groupKey":"custom","labels":{"ko":"사용자","en":"Custom"},"descriptions":{},"sortOrder":50,"enabled":true}
                  ],
                  "placements":[
                    {"resourceKey":"APP.MAIL_CALENDAR","groupKey":"work","sortOrder":1},
                    {"resourceKey":"APP.MAIL","groupKey":"work","sortOrder":2},
                    {"resourceKey":"APP.COLLABORATION","groupKey":"work","sortOrder":3},
                    {"resourceKey":"APP.ROOMS","groupKey":"work","sortOrder":4},
                    {"resourceKey":"APP.HRIS","groupKey":"work","sortOrder":5},
                    {"resourceKey":"APP.CUSTOM","groupKey":"custom","sortOrder":10}
                  ]
                }
                """, """
                {"schemaVersion":3,"experienceVariant":"FLOW_V1","personalCustomizationEnabled":true,"governedZones":[]}
                """);
        jdbc.update("""
                INSERT INTO adm_workspace_apps (
                    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
                    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
                    health_state, sort_order, lifecycle_state)
                VALUES
                    (1, 'tenant-mail-shadow', '구형 메일', 'Legacy mail', '구형', 'Legacy',
                     'Tenant', 'PRODUCTIVITY', 'NATIVE', '/legacy-mail', 'legacy-mail',
                     'APP.MAIL', 'HEALTHY', 131, 'ACTIVE'),
                    (1, 'tenant-lower-hris', '구형 인사', 'Legacy HR', '구형', 'Legacy',
                     'Tenant', 'PRODUCTIVITY', 'NATIVE', '/legacy-hr', 'legacy-hr',
                     ' App.HrIs ', 'HEALTHY', 132, 'ACTIVE'),
                    (1, 'tenant-custom-before-wave1', '테넌트 앱', 'Tenant app', '맞춤', 'Custom',
                     'Tenant', 'BUSINESS', 'DEEP_LINK', '/tenant-app', 'tenant-app',
                     'APP.TENANT_CUSTOM', 'HEALTHY', 900, 'ACTIVE')
                """);
        UUID legacyFlowView = UUID.randomUUID();
        UUID nonWorkspaceView = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, view_key, name, is_default,
                    schema_version, layout_payload, version)
                VALUES (?, 1, 91001, 'workspace-home', 'default', 'Flow before Wave 1', TRUE,
                        5, '{"widgets":[{"widgetKey":"focus","visible":true,"size":"medium","height":"tall"}]}'::jsonb, 3)
                """, legacyFlowView);
        jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, view_key, name, is_default,
                    schema_version, layout_payload, version)
                VALUES (?, 1, 91001, 'hcm-home', 'default', 'HCM home', TRUE,
                        5, '{"widgets":[]}'::jsonb, 0)
                """, nonWorkspaceView);
        jdbc.update("""
                INSERT INTO usr_home_view_device_layouts (
                    device_layout_id, view_id, tenant_id, user_id, device_class, overlay_payload)
                VALUES (?, ?, 1, 91001, 'DESKTOP',
                        '{"widgetOrder":["focus"],"widgetSizes":{"focus":"medium"},"density":"comfortable"}'::jsonb)
                """, UUID.randomUUID(), legacyFlowView);
        jdbc.update("""
                INSERT INTO usr_home_widget_configurations (
                    widget_configuration_id, view_id, tenant_id, user_id,
                    widget_key, configuration_payload)
                VALUES (?, ?, 1, 91001, 'focus', '{"sourceKey":"WORK"}'::jsonb)
                """, UUID.randomUUID(), legacyFlowView);
        UUID sourceRevision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usr_home_view_revisions (
                    revision_id, view_id, tenant_id, user_id, revision_number,
                    schema_version, snapshot, source, change_summary, restorable)
                VALUES (?, ?, 1, 91001, 1, 5,
                        '{"widgets":[{"widgetKey":"focus","visible":true,"size":"medium","height":"tall"}]}'::jsonb,
                        'USER', 'Pre-Wave1 Flow state', TRUE)
                """, sourceRevision, legacyFlowView);
        jdbc.update("""
                INSERT INTO usr_home_composer_proposals (
                    proposal_id, tenant_id, user_id, view_id, state, base_view_version,
                    reason_codes, changes_payload, warnings_payload, before_layout,
                    proposed_layout, creation_command_id, request_fingerprint, expires_at)
                VALUES (?, 1, 91001, ?, 'PREVIEWED', 3,
                        '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                        '{"widgets":[]}'::jsonb, '{"widgets":[]}'::jsonb,
                        ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day')
                """, UUID.randomUUID(), legacyFlowView, UUID.randomUUID(), "a".repeat(64));
        jdbc.update("""
                INSERT INTO usr_home_composer_proposals (
                    proposal_id, tenant_id, user_id, view_id, state, base_view_version,
                    reason_codes, changes_payload, warnings_payload, before_layout,
                    proposed_layout, creation_command_id, request_fingerprint,
                    applied_revision_id, applied_view_version, expires_at)
                VALUES (?, 1, 91001, ?, 'APPLIED', 3,
                        '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                        '{"widgets":[]}'::jsonb, '{"widgets":[]}'::jsonb,
                        ?, ?, ?, 3, CURRENT_TIMESTAMP + INTERVAL '1 day')
                """, UUID.randomUUID(), legacyFlowView, UUID.randomUUID(),
                "b".repeat(64), sourceRevision);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();

        JsonNode migratedLaunchpad = objectMapper.readTree(jdbc.queryForObject("""
                SELECT launchpad_configuration::text
                  FROM adm_home_experiences WHERE tenant_id = 1
                """, String.class));
        List<String> migratedResources = new java.util.ArrayList<>();
        migratedLaunchpad.path("placements").forEach(value ->
                migratedResources.add(value.path("resourceKey").asText()));
        assertThat(migratedResources.subList(0, 18)).containsExactlyElementsOf(
                ApprovedHomeApplicationCatalog.applications().stream()
                        .map(ApprovedHomeApplicationCatalog.Application::resourceKey).toList());
        assertThat(migratedResources).contains("APP.CUSTOM");
        assertThat(migratedResources).doesNotHaveDuplicates()
                .doesNotContain("APP.MAIL_CALENDAR", "APP.COLLABORATION", "APP.ROOMS", "APP.HRIS");
        assertThat(migratedLaunchpad.path("extensionMarker").asText()).isEqualTo("preserve-me");
        assertThat(jdbc.queryForObject("""
                SELECT composition_policy ->> 'schemaVersion'
                  FROM adm_home_experiences WHERE tenant_id = 1
                """, String.class)).isEqualTo("3");
        assertThat(jdbc.queryForObject(
                "SELECT mode_key FROM usr_home_views WHERE view_id = ?",
                String.class, legacyFlowView)).isEqualTo("CLASSIC");
        assertThat(jdbc.queryForObject(
                "SELECT legacy_unscoped FROM usr_home_views WHERE view_id = ?",
                Boolean.class, legacyFlowView)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'workspace-home' AND deleted_at IS NULL
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'workspace-home' AND is_default AND deleted_at IS NULL
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'workspace-home' AND mode_key = 'FLOW_V1'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT device_class FROM usr_home_view_device_layouts WHERE view_id = ?
                """, String.class, legacyFlowView)).isEqualTo("DESKTOP");
        // A mode-unaware pod must still be able to write DESKTOP/MOBILE after the expand-only
        // migration. A partially rolled out Wave1 pod may also have written the canonical peer;
        // activation resolves that collision deterministically in favour of the canonical row.
        jdbc.update("""
                UPDATE usr_home_view_device_layouts
                   SET overlay_payload =
                       '{"widgetOrder":["legacy-desktop"],"density":"comfortable","source":"legacy-alias"}'::jsonb
                 WHERE view_id = ? AND device_class = 'DESKTOP'
                """, legacyFlowView);
        jdbc.update("""
                INSERT INTO usr_home_view_device_layouts (
                    device_layout_id, view_id, tenant_id, user_id,
                    device_class, overlay_payload)
                VALUES (?, ?, 1, 91001, 'MOBILE',
                        '{"widgetOrder":["legacy-mobile"],"density":"compact","source":"legacy-mobile-promoted"}'::jsonb)
                """, UUID.randomUUID(), legacyFlowView);
        jdbc.update("""
                INSERT INTO usr_home_view_device_layouts (
                    device_layout_id, view_id, tenant_id, user_id,
                    device_class, overlay_payload)
                VALUES (?, ?, 1, 91001, 'DESKTOP_STANDARD',
                        '{"widgetOrder":["canonical"],"density":"compact","source":"canonical-wins"}'::jsonb)
                """, UUID.randomUUID(), legacyFlowView);
        assertThat(jdbc.queryForList("""
                SELECT device_class FROM usr_home_view_device_layouts
                 WHERE view_id = ? ORDER BY device_class
                """, String.class, legacyFlowView))
                .containsExactly("DESKTOP", "DESKTOP_STANDARD", "MOBILE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'hcm-home' AND mode_key = 'FLOW_V1'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_composer_proposals
                 WHERE view_id = ? AND state IN ('PREVIEWED', 'APPLIED')
                """, Integer.class, legacyFlowView)).isEqualTo(2);

        HomeModeV4ActivationGate activationGate = new HomeModeV4ActivationGate(true);
        HomeModeV4ActivationCoordinator activation = new HomeModeV4ActivationCoordinator(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                activationGate);

        // Force a failure after the view/revision/device inserts. The transaction must roll back
        // every partial clone and the runtime capability gate must remain closed.
        jdbc.execute("""
                CREATE FUNCTION fail_wave1_widget_clone() RETURNS trigger AS $$
                BEGIN
                    IF EXISTS (
                        SELECT 1 FROM usr_home_views view
                         WHERE view.view_id = NEW.view_id AND view.mode_key = 'FLOW_V1'
                    ) THEN
                        RAISE EXCEPTION 'forced Wave1 activation failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_wave1_widget_clone
                BEFORE INSERT ON usr_home_widget_configurations
                FOR EACH ROW EXECUTE FUNCTION fail_wave1_widget_clone()
                """);

        assertThatThrownBy(() -> activation.run(null))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(activationGate.active()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'workspace-home' AND mode_key = 'FLOW_V1'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT legacy_unscoped FROM usr_home_views WHERE view_id = ?",
                Boolean.class, legacyFlowView)).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT device_class FROM usr_home_view_device_layouts
                 WHERE view_id = ? ORDER BY device_class
                """, String.class, legacyFlowView))
                .as("failed activation rolls back alias deduplication and canonicalization")
                .containsExactly("DESKTOP", "DESKTOP_STANDARD", "MOBILE");
        assertThat(jdbc.queryForObject("""
                SELECT overlay_payload ->> 'source'
                  FROM usr_home_view_device_layouts
                 WHERE view_id = ? AND device_class = 'DESKTOP'
                """, String.class, legacyFlowView)).isEqualTo("legacy-alias");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_composer_proposals
                 WHERE view_id = ? AND state IN ('PREVIEWED', 'APPLIED')
                """, Integer.class, legacyFlowView)).isEqualTo(2);

        jdbc.execute("DROP TRIGGER trg_fail_wave1_widget_clone ON usr_home_widget_configurations");
        jdbc.execute("DROP FUNCTION fail_wave1_widget_clone()");
        activation.run(null);

        assertThat(activationGate.active()).isTrue();
        UUID flowView = jdbc.queryForObject("""
                SELECT view_id FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'workspace-home' AND mode_key = 'FLOW_V1'
                   AND view_key = 'default' AND deleted_at IS NULL
                """, UUID.class);
        assertThat(jdbc.queryForObject(
                "SELECT legacy_source_view_id FROM usr_home_views WHERE view_id = ?",
                UUID.class, flowView)).isEqualTo(legacyFlowView);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views WHERE legacy_unscoped
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_composer_proposals
                 WHERE view_id = ? AND state = 'FAILED'
                """, Integer.class, legacyFlowView)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_composer_proposals
                 WHERE view_id = ? AND state IN ('PREVIEWED', 'APPLIED')
                """, Integer.class, legacyFlowView)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT name FROM usr_home_views WHERE view_id = ?",
                String.class, flowView)).isEqualTo("Flow before Wave 1");
        assertThat(jdbc.queryForObject(
                "SELECT layout_payload::text FROM usr_home_views WHERE view_id = ?",
                String.class, flowView)).isEqualTo(jdbc.queryForObject(
                "SELECT layout_payload::text FROM usr_home_views WHERE view_id = ?",
                String.class, legacyFlowView));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM usr_home_view_device_layouts WHERE view_id = ?",
                Integer.class, flowView)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_view_device_layouts
                 WHERE device_class IN ('DESKTOP', 'MOBILE')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForList("""
                SELECT device_class FROM usr_home_view_device_layouts
                 WHERE view_id = ? ORDER BY device_class
                """, String.class, legacyFlowView))
                .containsExactly("DESKTOP_STANDARD", "MOBILE_STANDARD");
        assertThat(jdbc.queryForList("""
                SELECT device_class FROM usr_home_view_device_layouts
                 WHERE view_id = ? ORDER BY device_class
                """, String.class, flowView))
                .containsExactly("DESKTOP_STANDARD", "MOBILE_STANDARD");
        assertThat(jdbc.queryForObject("""
                SELECT overlay_payload ->> 'source'
                  FROM usr_home_view_device_layouts
                 WHERE view_id = ? AND device_class = 'DESKTOP_STANDARD'
                """, String.class, legacyFlowView)).isEqualTo("canonical-wins");
        assertThat(jdbc.queryForObject("""
                SELECT overlay_payload ->> 'source'
                  FROM usr_home_view_device_layouts
                 WHERE view_id = ? AND device_class = 'DESKTOP_STANDARD'
                """, String.class, flowView)).isEqualTo("canonical-wins");
        assertThat(jdbc.queryForObject("""
                SELECT overlay_payload ->> 'source'
                  FROM usr_home_view_device_layouts
                 WHERE view_id = ? AND device_class = 'MOBILE_STANDARD'
                """, String.class, flowView)).isEqualTo("legacy-mobile-promoted");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM usr_home_widget_configurations WHERE view_id = ?",
                Integer.class, flowView)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT configuration_payload::text FROM usr_home_widget_configurations WHERE view_id = ?",
                String.class, flowView)).isEqualTo(jdbc.queryForObject(
                "SELECT configuration_payload::text FROM usr_home_widget_configurations WHERE view_id = ?",
                String.class, legacyFlowView));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM usr_home_view_revisions WHERE view_id = ?",
                Integer.class, flowView)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT snapshot::text FROM usr_home_view_revisions WHERE view_id = ?",
                String.class, flowView)).isEqualTo(jdbc.queryForObject(
                "SELECT snapshot::text FROM usr_home_view_revisions WHERE view_id = ?",
                String.class, legacyFlowView));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'workspace-home' AND is_default
                """, Integer.class)).isEqualTo(2);
        String classicLayout = jdbc.queryForObject(
                "SELECT layout_payload::text FROM usr_home_views WHERE view_id = ?",
                String.class, legacyFlowView);
        jdbc.update("""
                UPDATE usr_home_views
                   SET layout_payload = '{"widgets":[]}'::jsonb, version = version + 1
                 WHERE view_id = ?
                """, flowView);
        assertThat(jdbc.queryForObject(
                "SELECT layout_payload::text FROM usr_home_views WHERE view_id = ?",
                String.class, legacyFlowView)).isEqualTo(classicLayout);

        activation.run(null);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001
                   AND surface_key = 'workspace-home' AND mode_key = 'FLOW_V1'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_view_revisions WHERE view_id = ?
                """, Integer.class, flowView)).isEqualTo(1);

        HomeViewCompatibilityBridge compatibilityBridge = new HomeViewCompatibilityBridge(
                jdbc, new SimpleMeterRegistry(), objectMapper,
                new HomeLayoutPolicy(objectMapper));
        ReflectionTestUtils.setField(compatibilityBridge, "dualWriteEnabled", true);
        compatibilityBridge.mirrorLegacyPreference(HomePreference.builder()
                .tenantId(1L)
                .userId(92001L)
                .surfaceKey("workspace-home")
                .schemaVersion(5)
                .layoutPayload(objectMapper.readTree("{\"widgets\":[]}"))
                .build());
        assertThat(jdbc.queryForObject("""
                SELECT legacy_unscoped FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 92001
                   AND surface_key = 'workspace-home' AND mode_key = 'CLASSIC'
                """, Boolean.class)).isFalse();
        activation.run(null);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 92001
                   AND surface_key = 'workspace-home' AND mode_key = 'FLOW_V1'
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, mode_key, view_key, name,
                    is_default, schema_version, layout_payload, version)
                VALUES (?, 1, 91001, 'workspace-home', 'FLOW_V1', 'second', 'Second Flow',
                        TRUE, 5, '{"widgets":[]}'::jsonb, 0)
                """, UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        long newTenantId = 91002L;
        UUID providerTenantId = UUID.randomUUID();
        PlatformTenantProvisioningDtos.ProvisionTenantRequest provisionRequest =
                new PlatformTenantProvisioningDtos.ProvisionTenantRequest(
                        providerTenantId, newTenantId, "wave-one-new", "Wave One New Tenant",
                        "local", "POOL", "ko", List.of("core.workspace"));
        PlatformTenantProvisioningService provisioningService =
                new PlatformTenantProvisioningService(
                        jdbc,
                        Path.of(System.getProperty("java.io.tmpdir"), "dwp-home-wave1-test")
                                .toString(),
                        objectMapper);
        provisioningService.provision(provisionRequest);

        List<AppRow> migratedApps = workspaceApps(jdbc, 1L).stream()
                .filter(app -> ApprovedHomeApplicationCatalog.applications().stream()
                        .anyMatch(canonical -> canonical.resourceKey().equals(app.resourceKey())))
                .toList();
        List<AppRow> newTenantApps = workspaceApps(jdbc, newTenantId);
        assertThat(migratedApps).containsExactlyElementsOf(newTenantApps);
        assertThat(migratedApps).hasSize(18);
        assertThat(migratedApps).extracting(AppRow::resourceKey).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM adm_workspace_apps
                 WHERE tenant_id = 1 AND app_key = 'tenant-mail-shadow'
                """, String.class)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM adm_workspace_apps
                 WHERE tenant_id = 1 AND app_key = 'tenant-lower-hris'
                """, String.class)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM adm_workspace_apps
                 WHERE tenant_id = 1 AND app_key = 'tenant-custom-before-wave1'
                """, String.class)).isEqualTo("ACTIVE");
        assertThat(newTenantApps).hasSize(18);
        assertThat(newTenantApps).extracting(AppRow::resourceKey).containsExactlyElementsOf(
                ApprovedHomeApplicationCatalog.applications().stream()
                        .map(ApprovedHomeApplicationCatalog.Application::resourceKey).toList());
        assertThat(newTenantApps).extracting(AppRow::launchTarget)
                .contains("/notifications/home", "/workplace/home");
        assertThat(newTenantApps).filteredOn(app -> "APP.WORKPLACE".equals(app.resourceKey()))
                .extracting(AppRow::iconKey).containsExactly("rooms");
        assertThat(newTenantApps).extracting(AppRow::requiredPermissionCode)
                .containsOnly("VIEW");
        assertThat(newTenantApps).filteredOn(app -> "APP.NOTIFICATIONS".equals(app.resourceKey()))
                .extracting(AppRow::badgeSourceKey).containsExactly("notifications");

        JsonNode newTenantLaunchpad = objectMapper.readTree(jdbc.queryForObject("""
                SELECT launchpad_configuration::text
                  FROM adm_home_experiences WHERE tenant_id = ?
                """, String.class, newTenantId));
        assertThat(newTenantLaunchpad.path("placements")).hasSize(18);

        jdbc.update("""
                INSERT INTO adm_workspace_apps (
                    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
                    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
                    health_state, sort_order, lifecycle_state)
                VALUES (?, 'tenant-custom', '테넌트 앱', 'Tenant app', '맞춤 앱', 'Custom app',
                        'Tenant', 'BUSINESS', 'DEEP_LINK', '/tenant-app', 'tenant-app',
                        'APP.TENANT_CUSTOM', 'HEALTHY', 900, 'ACTIVE')
                """, newTenantId);
        jdbc.update("""
                INSERT INTO adm_workspace_apps (
                    tenant_id, app_key, name_ko, name_en, description_ko, description_en,
                    owner_name, category, launch_mode, launch_target, icon_key, resource_key,
                    health_state, sort_order, lifecycle_state)
                VALUES
                    (?, 'tenant-old-rooms', '구형 공간', 'Old rooms', '구형', 'Legacy',
                     'Tenant', 'BUSINESS', 'NATIVE', '/old-rooms', 'old-rooms',
                     ' app.rooms ', 'HEALTHY', 901, 'ACTIVE'),
                    (?, 'tenant-mail-duplicate', '중복 메일', 'Duplicate mail', '중복', 'Duplicate',
                     'Tenant', 'BUSINESS', 'NATIVE', '/duplicate-mail', 'duplicate-mail',
                     ' app.mail ', 'HEALTHY', 902, 'ACTIVE')
                """, newTenantId, newTenantId);
        provisioningService.provision(provisionRequest);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM adm_workspace_apps
                 WHERE tenant_id = ? AND app_key = 'tenant-custom'
                """, String.class, newTenantId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM adm_workspace_apps
                 WHERE tenant_id = ? AND app_key = 'tenant-old-rooms'
                """, String.class, newTenantId)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM adm_workspace_apps
                 WHERE tenant_id = ? AND app_key = 'tenant-mail-duplicate'
                """, String.class, newTenantId)).isEqualTo("RETIRED");
        List<AppRow> appsWithCustom = workspaceApps(jdbc, newTenantId);

        Long experienceVersion = jdbc.queryForObject(
                "SELECT version FROM adm_home_experiences WHERE tenant_id = 1", Long.class);
        Long catalogVersions = jdbc.queryForObject(
                "SELECT sum(version) FROM adm_workspace_apps", Long.class);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V254__align_approved_home_launchpad.sql"),
                new ClassPathResource("db/migration/V255__scope_home_views_by_mode_and_device.sql"))
                .execute(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM adm_home_experiences WHERE tenant_id = 1", Long.class))
                .isEqualTo(experienceVersion);
        assertThat(jdbc.queryForObject(
                "SELECT sum(version) FROM adm_workspace_apps", Long.class))
                .isEqualTo(catalogVersions);
        assertThat(workspaceApps(jdbc, newTenantId)).containsExactlyElementsOf(appsWithCustom);
    }

    private List<AppRow> workspaceApps(JdbcTemplate jdbc, Long tenantId) {
        return jdbc.query("""
                SELECT app_key, resource_key, launch_target, icon_key,
                       required_permission_code, badge_source_key
                  FROM adm_workspace_apps
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY sort_order, app_key
                """, (row, ignored) -> new AppRow(
                row.getString("app_key"), row.getString("resource_key"),
                row.getString("launch_target"), row.getString("icon_key"),
                row.getString("required_permission_code"),
                row.getString("badge_source_key")), tenantId);
    }

    private record AppRow(
            String appKey,
            String resourceKey,
            String launchTarget,
            String iconKey,
            String requiredPermissionCode,
            String badgeSourceKey) {
    }
}
