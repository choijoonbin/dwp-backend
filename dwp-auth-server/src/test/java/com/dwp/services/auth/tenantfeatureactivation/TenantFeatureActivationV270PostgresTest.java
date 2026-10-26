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
class TenantFeatureActivationV270PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_HRM_EXIT_RETURN_WORKBENCH";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.exit-return.page",
            "route.hcm.operations.exit-return.data",
            "route.hcm.operations.separation-case.action");

    @Test
    void cleanInstallAppliesV270WithExactCatalogAndNoAuthority() {
        var source = source();
        flyway(source, "270").clean();
        flyway(source, "270").migrate();

        assertCatalog(new JdbcTemplate(source));
    }

    @Test
    void v269UpgradeAddsOnlyCatalogReadinessAndKeepsEveryAuthorityTableEmpty() {
        var source = source();
        flyway(source, "270").clean();
        flyway(source, "269").migrate();
        var jdbc = new JdbcTemplate(source);
        Map<String, List<Map<String, Object>>> before = zeroAuthoritySnapshot(jdbc);

        flyway(source, "270").migrate();

        assertCatalog(jdbc);
        assertThat(zeroAuthoritySnapshot(jdbc)).isEqualTo(before);
    }

    @Test
    void reapplyingExactV270IsANoopForEveryOwnedTuple() {
        var source = source();
        flyway(source, "270").clean();
        flyway(source, "269").migrate();
        var jdbc = new JdbcTemplate(source);

        executeV270(jdbc);
        Map<String, List<Map<String, Object>>> afterFirstApply = v270Snapshot(jdbc);
        executeV270(jdbc);

        assertThat(v270Snapshot(jdbc)).isEqualTo(afterFirstApply);
        assertCatalog(jdbc);
    }

    @Test
    void conflictingTemplateAbortsAndRollsBackEveryV270Statement() {
        var source = source();
        flyway(source, "270").clean();
        flyway(source, "269").migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO sys_tenant_resource_templates (
                    resource_key, resource_type, display_name, required_entitlement)
                VALUES ('ACTION.HRIS_SEPARATION_CASE', 'ACTION',
                        'conflicting display name', 'core.people')
                """);
        Map<String, List<Map<String, Object>>> before = v270Snapshot(jdbc);

        assertThatThrownBy(() -> executeV270(jdbc)).rootCause()
                .hasMessageContaining(
                        "V270 conflict: separation-case resource template differs from the sealed tuple");

        assertThat(v270Snapshot(jdbc)).isEqualTo(before);
    }

    private void assertCatalog(JdbcTemplate jdbc) {
        assertThat(count(jdbc, "sys_product_feature_activation_policies")).isEqualTo(16);
        assertThat(count(jdbc, "sys_product_feature_route_owners")).isEqualTo(94);
        assertThat(count(jdbc, "sys_product_feature_route_bindings")).isEqualTo(94);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 61
                   AND checksum =
                       'f7cf92a97f2fabf86f257010064a3183acf58f86cc2c9d7692a1555cab55aa24'
                   AND auth_seed_artifact =
                       'product-surfaces-v1.bundle-v61.generated.json'
                   AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 61 AND baseline_state = 'OFF'
                   AND lifecycle_state = 'PUBLISHED' AND owner_key = 'HRM'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key FROM sys_product_feature_route_owners
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, String.class, FEATURE)).containsExactlyInAnyOrderElementsOf(ROUTES);
        assertThat(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE route_kind = 'PAGE') AS pages,
                       count(*) FILTER (WHERE route_kind = 'DATA') AS data,
                       count(*) FILTER (WHERE route_kind = 'ACTION') AS actions
                  FROM sys_product_feature_route_bindings WHERE feature_key = ?
                """, FEATURE)).containsEntry("pages", 1L)
                .containsEntry("data", 1L).containsEntry("actions", 1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 61 AND subject_type = 'PRODUCT'
                   AND surface_key = 'hcm.operations' AND lifecycle_state = 'ACTIVE'
                """, Integer.class, FEATURE)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_templates
                 WHERE resource_key = 'ACTION.HRIS_SEPARATION_CASE'
                   AND resource_type = 'ACTION' AND required_entitlement = 'core.people'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_responsibility_catalog
                 WHERE responsibility_code = 'HRIS_DUTY_SEPARATION_MAKER'
                   AND resource_type = 'APP' AND risk_tier = 'L3'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER'
                   AND product_key = 'hcm' AND product_resource_key = 'APP.HCM'
                   AND resource_key = 'ACTION.HRIS_SEPARATION_CASE'
                   AND audit_policy_exception = FALSE AND risk_tier = 'HIGH'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER'
                   AND capability_contract_key = 'hcm.operations.separation.manage'
                   AND permission_resource_key = 'ACTION.HRIS_SEPARATION_CASE'
                   AND permission_code = 'MANAGE'
                   AND resolved_capability_code = 'ACTION.HRIS_SEPARATION_CASE:MANAGE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_resources
                 WHERE key = 'ACTION.HRIS_SEPARATION_CASE' AND enabled = TRUE
                """, Integer.class)).isEqualTo(count(jdbc, "com_tenants"));
        zeroAuthoritySnapshot(jdbc).forEach((key, rows) ->
                assertThat(rows).as(key).isEmpty());

        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var reader = new TenantFeatureActivationRepository(jdbc);
        for (String route : ROUTES) {
            var decision = reader.evaluate(new TenantFeatureActivationReader.GateRequest(
                    tenant, "hcm", "hcm.operations", route, 61,
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
                 WHERE resource_key = 'ACTION.HRIS_SEPARATION_CASE'
                """);
        queries.put("role-permissions", """
                SELECT role_permission.* FROM com_role_permissions role_permission
                  JOIN com_resources resource
                    ON resource.resource_id = role_permission.resource_id
                 WHERE resource.key = 'ACTION.HRIS_SEPARATION_CASE'
                """);
        queries.put("duty-assignments", """
                SELECT * FROM com_admin_scoped_duty_assignments
                 WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER'
                """);
        queries.put("resource-set-members", """
                SELECT * FROM com_admin_resource_set_members
                 WHERE resource_key = 'ACTION.HRIS_SEPARATION_CASE'
                """);
        queries.put("preset-duties", """
                SELECT * FROM sys_admin_app_preset_duties
                 WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER'
                """);
        queries.put("principal-grants", """
                SELECT grant_record.* FROM com_principal_resource_grants grant_record
                  JOIN com_resources resource
                    ON resource.resource_id = grant_record.resource_id
                 WHERE resource.key = 'ACTION.HRIS_SEPARATION_CASE'
                """);
        queries.put("activation", """
                SELECT * FROM auth_product_feature_activation_changes
                 WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
                """);
        queries.put("enrollment", """
                SELECT * FROM com_tenant_feature_enrollment_changes
                 WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
                """);
        queries.put("effective-duty", """
                SELECT * FROM auth_effective_scoped_duties
                 WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER'
                """);
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        queries.forEach((key, query) -> snapshot.put(key, jdbc.queryForList(query)));
        return snapshot;
    }

    private Map<String, List<Map<String, Object>>> v270Snapshot(JdbcTemplate jdbc) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("permission", """
                SELECT * FROM com_permissions WHERE code = 'MANAGE'
                """);
        queries.put("template", """
                SELECT * FROM sys_tenant_resource_templates
                 WHERE resource_key = 'ACTION.HRIS_SEPARATION_CASE'
                """);
        queries.put("responsibility", """
                SELECT * FROM sys_admin_responsibility_catalog
                 WHERE responsibility_code = 'HRIS_DUTY_SEPARATION_MAKER'
                """);
        queries.put("duty", """
                SELECT * FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER'
                """);
        queries.put("duty-capability", """
                SELECT * FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code = 'HRIS_DUTY_SEPARATION_MAKER'
                """);
        queries.put("tenant-resources", """
                SELECT * FROM com_resources
                 WHERE key = 'ACTION.HRIS_SEPARATION_CASE' ORDER BY tenant_id, type, key
                """);
        queries.put("seed-release", """
                SELECT * FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 61
                """);
        queries.put("feature-policy", """
                SELECT * FROM sys_product_feature_activation_policies
                 WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
                """);
        queries.put("route-owners", """
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
                 ORDER BY route_contract_key
                """);
        queries.put("route-bindings", """
                SELECT * FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_HRM_EXIT_RETURN_WORKBENCH'
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

    private void executeV270(JdbcTemplate jdbc) {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V270__declare_hris_hrm013_v61_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read V270 migration", exception);
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
