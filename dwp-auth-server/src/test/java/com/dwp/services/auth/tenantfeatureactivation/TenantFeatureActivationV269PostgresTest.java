package com.dwp.services.auth.tenantfeatureactivation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TenantFeatureActivationV269PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_INTEGRATION_CONTROL_PLANE";
    private static final List<String> ROUTES = List.of(
            "route.hcm.configuration.settings-integrations.page",
            "route.hcm.configuration.dwp-integrations.data",
            "route.hcm.configuration.connector-catalog.data",
            "route.hcm.configuration.connector-definition-create.action",
            "route.hcm.configuration.connector-test.action",
            "route.hcm.configuration.connector-mapping-dry-run.action",
            "route.hcm.configuration.connector-execution-create.action",
            "route.hcm.configuration.connector-execution-detail.data",
            "route.hcm.configuration.connector-definition-validate.action",
            "route.hcm.configuration.connector-definition-submit.action",
            "route.hcm.configuration.connector-definition-approve.action",
            "route.hcm.configuration.connector-definition-activate.action",
            "route.hcm.configuration.connector-mapping-version-create.action",
            "route.hcm.configuration.connector-mapping-version-validate.action",
            "route.hcm.configuration.connector-mapping-version-submit.action",
            "route.hcm.configuration.connector-mapping-version-approve.action",
            "route.hcm.configuration.connector-mapping-version-activate.action",
            "route.hcm.configuration.connector-execution-reconcile.action",
            "route.hcm.configuration.connector-execution-retry.action",
            "route.hcm.configuration.connector-execution-cancel.action");

    @Test
    void cleanInstallAppliesV269WithExactCatalogAndNoAuthority() {
        var source = source();
        flyway(source, "269").clean();
        flyway(source, "269").migrate();

        assertCatalog(new JdbcTemplate(source));
    }

    @Test
    void v268UpgradeAddsOnlyCatalogReadinessAndKeepsEveryAuthorityTableEmpty() {
        var source = source();
        flyway(source, "269").clean();
        flyway(source, "268").migrate();
        var jdbc = new JdbcTemplate(source);
        Map<String, List<Map<String, Object>>> before = zeroAuthoritySnapshot(jdbc);

        flyway(source, "269").migrate();

        assertCatalog(jdbc);
        assertThat(zeroAuthoritySnapshot(jdbc)).isEqualTo(before);
    }

    @Test
    void reapplyingExactV269IsANoopForEveryOwnedTuple() {
        var source = source();
        flyway(source, "269").clean();
        flyway(source, "268").migrate();
        var jdbc = new JdbcTemplate(source);

        executeV269(jdbc);
        Map<String, List<Map<String, Object>>> afterFirstApply = v269Snapshot(jdbc);
        executeV269(jdbc);

        assertThat(v269Snapshot(jdbc)).isEqualTo(afterFirstApply);
        assertCatalog(jdbc);
    }

    @Test
    void conflictingTemplateAbortsAndRollsBackEveryV269Statement() {
        var source = source();
        flyway(source, "269").clean();
        flyway(source, "268").migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO sys_tenant_resource_templates (
                    resource_key, resource_type, display_name, required_entitlement)
                VALUES ('CONFIG.HRIS_INTEGRATION', 'CONFIG',
                        'conflicting display name', 'core.people')
                """);
        Map<String, List<Map<String, Object>>> before = v269Snapshot(jdbc);

        assertThatThrownBy(() -> executeV269(jdbc)).rootCause()
                .hasMessageContaining(
                        "V269 conflict: resource templates differ from the sealed tuples");

        assertThat(v269Snapshot(jdbc)).isEqualTo(before);
    }

    private void assertCatalog(JdbcTemplate jdbc) {
        assertThat(count(jdbc, "sys_product_feature_activation_policies")).isEqualTo(15);
        assertThat(count(jdbc, "sys_product_feature_route_owners")).isEqualTo(91);
        assertThat(count(jdbc, "sys_product_feature_route_bindings")).isEqualTo(91);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 60
                   AND checksum =
                       '405ac7c88d30a5730faa40c730968fffc873bd1a3679e4997532f0ea9ff1c47c'
                   AND auth_seed_artifact =
                       'product-surfaces-v1.bundle-v60.generated.json'
                   AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 60 AND baseline_state = 'OFF'
                   AND lifecycle_state = 'PUBLISHED' AND owner_key = 'SYS'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key FROM sys_product_feature_route_owners
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, String.class, FEATURE)).containsExactlyInAnyOrderElementsOf(ROUTES);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 60 AND subject_type = 'PRODUCT'
                   AND surface_key = 'hcm.management' AND lifecycle_state = 'ACTIVE'
                """, Integer.class, FEATURE)).isEqualTo(20);
        assertThat(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE route_kind = 'PAGE') AS pages,
                       count(*) FILTER (WHERE route_kind = 'DATA') AS data,
                       count(*) FILTER (WHERE route_kind = 'ACTION') AS actions
                  FROM sys_product_feature_route_bindings WHERE feature_key = ?
                """, FEATURE)).containsEntry("pages", 1L)
                .containsEntry("data", 3L).containsEntry("actions", 16L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_templates
                 WHERE resource_key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
                   AND required_entitlement = 'core.people'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_responsibility_catalog
                 WHERE responsibility_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                   AND resource_type = 'APP' AND risk_tier = 'L3'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                   AND product_key = 'hcm' AND product_resource_key = 'APP.HCM'
                   AND audit_policy_exception = FALSE AND risk_tier = 'HIGH'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_app_preset_catalog
                 WHERE preset_code IN ('HRIS_INTEGRATION_AUTHOR',
                        'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                   AND product_key = 'hcm' AND product_resource_key = 'APP.HCM'
                   AND responsibility_code = 'APP_CONFIG_ADMIN'
                   AND risk_tier = 'HIGH' AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_app_preset_duties
                 WHERE preset_code IN ('HRIS_INTEGRATION_AUTHOR',
                        'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_resources
                 WHERE key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE') AND enabled = TRUE
                """, Integer.class)).isEqualTo(2 * count(jdbc, "com_tenants"));
        zeroAuthoritySnapshot(jdbc).forEach((key, rows) ->
                assertThat(rows).as(key).isEmpty());

        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var reader = new TenantFeatureActivationRepository(jdbc);
        for (String route : ROUTES) {
            var decision = reader.evaluate(new TenantFeatureActivationReader.GateRequest(
                    tenant, "hcm", "hcm.management", route, 60,
                    Instant.parse("2026-10-26T00:00:00Z")));
            assertThat(decision.featureKey()).as(route).isEqualTo(FEATURE);
            assertThat(decision.state()).as(route).isEqualTo(
                    TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
            assertThat(decision.reasonCode()).as(route)
                    .isEqualTo("TENANT_APP_ADOPTION_REQUIRED");
            assertThat(decision.preservesAuthority()).as(route).isFalse();
        }
    }

    private Map<String, List<Map<String, Object>>> zeroAuthoritySnapshot(JdbcTemplate jdbc) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("role-templates", """
                SELECT * FROM sys_tenant_role_permission_templates
                 WHERE resource_key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
                """);
        queries.put("role-permissions", """
                SELECT role_permission.* FROM com_role_permissions role_permission
                  JOIN com_resources resource
                    ON resource.resource_id = role_permission.resource_id
                 WHERE resource.key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
                """);
        queries.put("duty-assignments", """
                SELECT * FROM com_admin_scoped_duty_assignments
                 WHERE duty_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                """);
        queries.put("resource-set-members", """
                SELECT * FROM com_admin_resource_set_members
                 WHERE resource_key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
                """);
        queries.put("preset-assignments", """
                SELECT * FROM com_admin_app_preset_assignments
                 WHERE preset_code IN ('HRIS_INTEGRATION_AUTHOR',
                        'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                """);
        queries.put("principal-grants", """
                SELECT grant_record.* FROM com_principal_resource_grants grant_record
                  JOIN com_resources resource
                    ON resource.resource_id = grant_record.resource_id
                 WHERE resource.key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE')
                """);
        queries.put("activation", """
                SELECT * FROM auth_product_feature_activation_changes
                 WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
                """);
        queries.put("enrollment", """
                SELECT * FROM com_tenant_feature_enrollment_changes
                 WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
                """);
        queries.put("effective-duty", """
                SELECT * FROM auth_effective_scoped_duties
                 WHERE duty_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                """);
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        queries.forEach((key, query) -> snapshot.put(key, jdbc.queryForList(query)));
        return snapshot;
    }

    private Map<String, List<Map<String, Object>>> v269Snapshot(JdbcTemplate jdbc) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("templates", """
                SELECT * FROM sys_tenant_resource_templates
                 WHERE resource_key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE') ORDER BY resource_key
                """);
        queries.put("responsibilities", """
                SELECT * FROM sys_admin_responsibility_catalog
                 WHERE responsibility_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                 ORDER BY responsibility_code
                """);
        queries.put("duties", """
                SELECT * FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR') ORDER BY duty_code
                """);
        queries.put("duty-capabilities", """
                SELECT * FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code IN ('HRIS_DUTY_INTEGRATION_AUTHOR',
                        'HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                 ORDER BY duty_code, capability_contract_key
                """);
        queries.put("presets", """
                SELECT * FROM sys_admin_app_preset_catalog
                 WHERE preset_code IN ('HRIS_INTEGRATION_AUTHOR',
                        'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR') ORDER BY preset_code
                """);
        queries.put("preset-duties", """
                SELECT * FROM sys_admin_app_preset_duties
                 WHERE preset_code IN ('HRIS_INTEGRATION_AUTHOR',
                        'HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR')
                 ORDER BY preset_code, duty_code
                """);
        queries.put("tenant-resources", """
                SELECT * FROM com_resources
                 WHERE key IN ('CONFIG.HRIS_INTEGRATION',
                        'ACTION.HRIS_INTEGRATION_CONTROL_PLANE') ORDER BY tenant_id, type, key
                """);
        queries.put("seed-release", """
                SELECT * FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 60
                """);
        queries.put("feature-policy", """
                SELECT * FROM sys_product_feature_activation_policies
                 WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
                """);
        queries.put("route-owners", """
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
                 ORDER BY route_contract_key
                """);
        queries.put("route-bindings", """
                SELECT * FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_INTEGRATION_CONTROL_PLANE'
                 ORDER BY route_contract_key
                """);
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        queries.forEach((key, query) -> snapshot.put(key, jdbc.queryForList(query)));
        snapshot.putAll(zeroAuthoritySnapshot(jdbc));
        return snapshot;
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void executeV269(JdbcTemplate jdbc) {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V269__declare_hris_sys008_v60_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read V269 migration", exception);
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
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
