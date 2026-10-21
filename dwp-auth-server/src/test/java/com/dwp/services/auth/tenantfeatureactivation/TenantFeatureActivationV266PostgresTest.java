package com.dwp.services.auth.tenantfeatureactivation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TenantFeatureActivationV266PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_HRM_DATA_QUALITY_WORKBENCH";
    private static final String DUTY = "HRIS_DUTY_INTEGRATION_EXECUTOR";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.people-data-quality.page",
            "route.hcm.operations.people-data-quality.data",
            "route.hcm.operations.workforce-ingestion.action");
    private static final List<String> ZERO_CHANGE_TABLES = List.of(
            "com_role_permissions",
            "com_admin_role_assignments",
            "com_admin_scoped_duty_assignments",
            "com_admin_app_preset_assignments",
            "auth_product_feature_activation_changes",
            "com_tenant_feature_enrollment_changes");

    @Test
    void cleanInstallAddsOnlyCatalogReadinessAndLeavesTheFeatureOff() {
        var source = source();
        flyway(source, "266").clean();
        flyway(source, "266").migrate();
        assertCatalog(new JdbcTemplate(source));
    }

    @Test
    void v265UpgradePreservesAssignmentsEnrollmentsActivationsAndGrants() {
        var source = source();
        flyway(source, "266").clean();
        flyway(source, "265").migrate();
        var jdbc = new JdbcTemplate(source);
        Map<String, List<Map<String, Object>>> before = ZERO_CHANGE_TABLES.stream()
                .collect(java.util.stream.Collectors.toMap(
                        table -> table,
                        table -> jdbc.queryForList("SELECT * FROM " + table)));
        var priorOwners = jdbc.queryForList("""
                SELECT * FROM sys_product_feature_route_owners
                 ORDER BY route_contract_key
                """);

        flyway(source, "266").migrate();

        assertCatalog(jdbc);
        for (String table : ZERO_CHANGE_TABLES) {
            assertThat(jdbc.queryForList("SELECT * FROM " + table))
                    .as(table).isEqualTo(before.get(table));
        }
        assertThat(jdbc.queryForList("""
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key <> ? ORDER BY route_contract_key
                """, FEATURE)).isEqualTo(priorOwners);
    }

    private void assertCatalog(JdbcTemplate jdbc) {
        assertThat(count(jdbc, "sys_product_feature_activation_policies")).isEqualTo(12);
        assertThat(count(jdbc, "sys_product_feature_route_owners")).isEqualTo(60);
        assertThat(count(jdbc, "sys_product_feature_route_bindings")).isEqualTo(60);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 1 AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM' AND minimum_bundle_version = 57
                   AND baseline_state = 'OFF' AND owner_key = 'HRM'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key FROM sys_product_feature_route_owners
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, String.class, FEATURE)).containsExactlyInAnyOrderElementsOf(ROUTES);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 1 AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM' AND minimum_bundle_version = 57
                   AND subject_type = 'PRODUCT' AND surface_key = 'hcm.operations'
                """, Integer.class, FEATURE)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_responsibility_catalog
                 WHERE responsibility_code = ?
                   AND display_name = 'HRIS workforce ingestion executor'
                   AND resource_type = 'APP' AND risk_tier = 'L3'
                   AND allowed_principal_types = '["USER","GROUP"]'::jsonb
                   AND sort_order = 130 AND lifecycle_state = 'ACTIVE'
                """, Integer.class, DUTY)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code = ? AND product_key = 'hcm'
                   AND legacy_role_code = 'HR_ADMIN'
                   AND product_resource_key = 'APP.HCM'
                   AND resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
                   AND audit_policy_exception = FALSE
                   AND risk_tier = 'HIGH' AND lifecycle_state = 'ACTIVE'
                """, Integer.class, DUTY)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code = ?
                   AND capability_contract_key = 'hcm.integration.workforce-ingestion.execute'
                   AND permission_resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
                   AND permission_code = 'EXECUTE'
                """, Integer.class, DUTY)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_app_preset_catalog
                 WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR'
                   AND product_key = 'hcm' AND product_resource_key = 'APP.HCM'
                   AND responsibility_code = 'APP_CONFIG_ADMIN'
                   AND risk_tier = 'HIGH' AND lifecycle_state = 'ACTIVE'
                   AND version = 1
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_app_preset_duties
                 WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR'
                   AND duty_code = ? AND sort_order = 10
                """, Integer.class, DUTY)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_templates
                 WHERE resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
                   AND resource_type = 'ACTION'
                   AND required_entitlement = 'core.people'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_resources
                 WHERE key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
                   AND type = 'ACTION' AND enabled = TRUE
                """, Integer.class)).isEqualTo(count(jdbc, "com_tenants"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE version = 57 AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                   AND checksum = 'f7d6e2ef92f563747ed062e496638ea83498a33414fc12586c794f517e626d6e'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments
                 WHERE duty_code = ?
                """, Integer.class, DUTY)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE responsibility_code = ?
                """, Integer.class, DUTY)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_feature_activation_changes
                 WHERE feature_key = ?
                """, Integer.class, FEATURE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_tenant_feature_enrollment_changes
                 WHERE feature_key = ?
                """, Integer.class, FEATURE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM com_role_permissions grant_row
                  JOIN com_resources resource
                    ON resource.resource_id = grant_row.resource_id
                   AND resource.tenant_id = grant_row.tenant_id
                 WHERE resource.key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
                """, Integer.class)).isZero();

        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var reader = new TenantFeatureActivationRepository(jdbc);
        for (String route : ROUTES) {
            var decision = reader.evaluate(new TenantFeatureActivationReader.GateRequest(
                    tenant, "hcm", "hcm.operations", route, 57,
                    Instant.parse("2026-10-22T06:00:00Z")));
            assertThat(decision.featureKey()).isEqualTo(FEATURE);
            assertThat(decision.state()).isEqualTo(
                    TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
            assertThat(decision.preservesAuthority()).isFalse();
        }
    }

    @Test
    void reapplyingExactV266IsANoopForEveryOwnedCatalogTuple() {
        var source = source();
        flyway(source, "266").clean();
        flyway(source, "265").migrate();
        var jdbc = new JdbcTemplate(source);

        executeV266(jdbc);
        Map<String, List<Map<String, Object>>> afterFirstApply = v266CatalogSnapshot(jdbc);

        executeV266(jdbc);

        assertThat(v266CatalogSnapshot(jdbc)).isEqualTo(afterFirstApply);
        assertCatalog(jdbc);
    }

    @Test
    void rejectsSupersetChildCatalogDriftAndRollsBackEveryV266Statement() {
        assertV266SupersetFailsClosed(
                jdbc -> {
                    seedExactV266ResourceAndDuty(jdbc);
                    jdbc.update("""
                            INSERT INTO sys_admin_scoped_duty_capabilities (
                                duty_code, capability_contract_key,
                                permission_resource_key, permission_code)
                            VALUES (
                                'HRIS_DUTY_INTEGRATION_EXECUTOR',
                                'hcm.integration.execute',
                                'ACTION.HRIS_INTEGRATION_EXECUTE', 'VIEW')
                            """);
                },
                "V266 conflict: integration-executor capability differs from the sealed tuple");

        assertV266SupersetFailsClosed(
                jdbc -> {
                    seedExactV266ResourceAndDuty(jdbc);
                    jdbc.update("""
                            INSERT INTO sys_admin_app_preset_catalog (
                                preset_code, product_key, product_resource_key,
                                display_name, description, responsibility_code,
                                risk_tier, lifecycle_state, version)
                            VALUES (
                                'HRIS_INTEGRATION_EXECUTOR', 'hcm', 'APP.HCM',
                                'HRIS workforce ingestion executor',
                                'Executes governed workforce ingestion for independently assigned connector and legal-entity populations.',
                                'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1)
                            """);
                    jdbc.update("""
                            INSERT INTO sys_admin_app_preset_duties (
                                preset_code, duty_code, sort_order)
                            VALUES (
                                'HRIS_INTEGRATION_EXECUTOR',
                                'HRIS_DUTY_CONTRACT_ISSUER', 20)
                            """);
                },
                "V266 conflict: integration-executor preset duty differs from the sealed tuple");
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void executeV266(JdbcTemplate jdbc) {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V266__declare_hris_hrm012_v57_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read V266 migration", exception);
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    private void assertV266SupersetFailsClosed(
            Consumer<JdbcTemplate> seedSuperset,
            String expectedFailure) {
        var source = source();
        flyway(source, "266").clean();
        flyway(source, "265").migrate();
        var jdbc = new JdbcTemplate(source);
        seedSuperset.accept(jdbc);
        Map<String, List<Map<String, Object>>> before = v266CatalogSnapshot(jdbc);

        assertThatThrownBy(() -> executeV266(jdbc))
                .rootCause()
                .hasMessageContaining(expectedFailure);

        assertThat(v266CatalogSnapshot(jdbc)).isEqualTo(before);
    }

    private void seedExactV266ResourceAndDuty(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sys_tenant_resource_templates (
                    resource_key, resource_type, display_name,
                    required_entitlement, lifecycle_state)
                VALUES (
                    'ACTION.HRIS_INTEGRATION_EXECUTE', 'ACTION',
                    'HRIS workforce ingestion execution', 'core.people', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO sys_admin_scoped_duty_catalog (
                    duty_code, product_key, legacy_role_code,
                    product_resource_key, resource_key, audit_policy_exception,
                    risk_tier, lifecycle_state, version)
                VALUES (
                    'HRIS_DUTY_INTEGRATION_EXECUTOR', 'hcm', 'HR_ADMIN',
                    'APP.HCM', 'ACTION.HRIS_INTEGRATION_EXECUTE', FALSE,
                    'HIGH', 'ACTIVE', 0)
                """);
    }

    private Map<String, List<Map<String, Object>>> v266CatalogSnapshot(JdbcTemplate jdbc) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("permission", """
                SELECT * FROM com_permissions
                 WHERE code = 'EXECUTE'
                """);
        queries.put("resource-template", """
                SELECT * FROM sys_tenant_resource_templates
                 WHERE resource_key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
                """);
        queries.put("responsibility", """
                SELECT * FROM sys_admin_responsibility_catalog
                 WHERE responsibility_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
                """);
        queries.put("duty", """
                SELECT * FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
                """);
        queries.put("duty-capability", """
                SELECT * FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code = 'HRIS_DUTY_INTEGRATION_EXECUTOR'
                """);
        queries.put("preset", """
                SELECT * FROM sys_admin_app_preset_catalog
                 WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR'
                """);
        queries.put("preset-duty", """
                SELECT * FROM sys_admin_app_preset_duties
                 WHERE preset_code = 'HRIS_INTEGRATION_EXECUTOR'
                """);
        queries.put("tenant-resources", """
                SELECT * FROM com_resources
                 WHERE type = 'ACTION' AND key = 'ACTION.HRIS_INTEGRATION_EXECUTE'
                 ORDER BY tenant_id, resource_id
                """);
        queries.put("seed-release", """
                SELECT * FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 57
                """);
        queries.put("feature-policy", """
                SELECT * FROM sys_product_feature_activation_policies
                 WHERE feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH'
                 ORDER BY policy_version
                """);
        queries.put("route-owners", """
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH'
                 ORDER BY route_contract_key
                """);
        queries.put("route-bindings", """
                SELECT * FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_HRM_DATA_QUALITY_WORKBENCH'
                 ORDER BY route_contract_key, policy_version
                """);
        queries.put("effective-duty-view", """
                SELECT pg_get_viewdef('auth_effective_scoped_duties'::regclass, TRUE)
                    AS definition
                """);

        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        queries.forEach((key, query) -> snapshot.put(key, jdbc.queryForList(query)));
        return snapshot;
    }

    private PGSimpleDataSource source() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private Flyway flyway(PGSimpleDataSource source, String target) {
        return Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).target(target).load();
    }
}
