package com.dwp.services.platform.home;

import com.dwp.services.platform.provisioning.PlatformTenantProvisioningDtos;
import com.dwp.services.platform.provisioning.PlatformTenantProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
        UUID classicView = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, view_key, name, is_default,
                    schema_version, layout_payload, version)
                VALUES (?, 1, 91001, 'workspace-home', 'default', 'Classic', TRUE,
                        5, '{"widgets":[]}'::jsonb, 0)
                """, classicView);
        jdbc.update("""
                INSERT INTO usr_home_view_device_layouts (
                    device_layout_id, view_id, tenant_id, user_id, device_class, overlay_payload)
                VALUES (?, ?, 1, 91001, 'DESKTOP', '{}'::jsonb)
                """, UUID.randomUUID(), classicView);

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
                String.class, classicView)).isEqualTo("CLASSIC");
        assertThat(jdbc.queryForObject("""
                SELECT device_class FROM usr_home_view_device_layouts WHERE view_id = ?
                """, String.class, classicView)).isEqualTo("DESKTOP_STANDARD");

        UUID flowView = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, mode_key, view_key, name,
                    is_default, schema_version, layout_payload, version)
                VALUES (?, 1, 91001, 'workspace-home', 'FLOW_V1', 'default', 'Flow',
                        TRUE, 5, '{"widgets":[]}'::jsonb, 0)
                """, flowView);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 1 AND user_id = 91001 AND is_default
                """, Integer.class)).isEqualTo(2);
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

        List<AppRow> migratedApps = workspaceApps(jdbc, 1L);
        List<AppRow> newTenantApps = workspaceApps(jdbc, newTenantId);
        assertThat(migratedApps).containsExactlyElementsOf(newTenantApps);
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
        provisioningService.provision(provisionRequest);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM adm_workspace_apps
                 WHERE tenant_id = ? AND app_key = 'tenant-custom'
                """, String.class, newTenantId)).isEqualTo("ACTIVE");
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
