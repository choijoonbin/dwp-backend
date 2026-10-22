package com.dwp.services.auth.tenantfeatureactivation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class TenantFeatureActivationV267PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_PAY_RETIREMENT_WORKBENCH";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data");
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
        flyway(source, "267").clean();
        flyway(source, "267").migrate();
        assertCatalog(new JdbcTemplate(source));
    }

    @Test
    void v266UpgradePreservesAssignmentsEnrollmentsActivationsGrantsAndPriorCatalogs() {
        var source = source();
        flyway(source, "267").clean();
        flyway(source, "266").migrate();
        var jdbc = new JdbcTemplate(source);
        Map<String, List<Map<String, Object>>> unchanged = ZERO_CHANGE_TABLES.stream()
                .collect(java.util.stream.Collectors.toMap(
                        table -> table,
                        table -> jdbc.queryForList("SELECT * FROM " + table)));
        var priorPolicies = jdbc.queryForList("""
                SELECT * FROM sys_product_feature_activation_policies
                 ORDER BY feature_key, policy_version
                """);
        var priorOwners = jdbc.queryForList("""
                SELECT * FROM sys_product_feature_route_owners
                 ORDER BY route_contract_key
                """);
        var priorBindings = jdbc.queryForList("""
                SELECT * FROM sys_product_feature_route_bindings
                 ORDER BY route_contract_key, feature_key, policy_version
                """);

        flyway(source, "267").migrate();

        assertCatalog(jdbc);
        for (String table : ZERO_CHANGE_TABLES) {
            assertThat(jdbc.queryForList("SELECT * FROM " + table))
                    .as(table).isEqualTo(unchanged.get(table));
        }
        assertThat(jdbc.queryForList("""
                SELECT * FROM sys_product_feature_activation_policies
                 WHERE feature_key <> ? ORDER BY feature_key, policy_version
                """, FEATURE)).isEqualTo(priorPolicies);
        assertThat(jdbc.queryForList("""
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key <> ? ORDER BY route_contract_key
                """, FEATURE)).isEqualTo(priorOwners);
        assertThat(jdbc.queryForList("""
                SELECT * FROM sys_product_feature_route_bindings
                 WHERE feature_key <> ?
                 ORDER BY route_contract_key, feature_key, policy_version
                """, FEATURE)).isEqualTo(priorBindings);
    }

    @Test
    void reapplyingExactV267IsANoopForEveryOwnedCatalogTuple() {
        var source = source();
        flyway(source, "267").clean();
        flyway(source, "266").migrate();
        var jdbc = new JdbcTemplate(source);

        executeV267(jdbc);
        Map<String, List<Map<String, Object>>> afterFirstApply = v267CatalogSnapshot(jdbc);
        executeV267(jdbc);

        assertThat(v267CatalogSnapshot(jdbc)).isEqualTo(afterFirstApply);
        assertCatalog(jdbc);
    }

    @Test
    void rejectsConflictingPolicyAndRollsBackEveryV267Statement() {
        var source = source();
        flyway(source, "267").clean();
        flyway(source, "266").migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO sys_product_feature_activation_policies (
                    feature_key, policy_version, product_key, app_resource_key,
                    minimum_bundle_version, baseline_state, owner_key)
                VALUES (?, 1, 'hcm', 'APP.HCM', 58, 'OFF', 'HRM')
                """, FEATURE);
        Map<String, List<Map<String, Object>>> before = v267CatalogSnapshot(jdbc);

        assertThatThrownBy(() -> executeV267(jdbc))
                .rootCause()
                .hasMessageContaining(
                        "V267 conflict: PAY retirement feature policy differs from the sealed tuple");

        assertThat(v267CatalogSnapshot(jdbc)).isEqualTo(before);
    }

    private void assertCatalog(JdbcTemplate jdbc) {
        assertThat(count(jdbc, "sys_product_feature_activation_policies")).isEqualTo(13);
        assertThat(count(jdbc, "sys_product_feature_route_owners")).isEqualTo(62);
        assertThat(count(jdbc, "sys_product_feature_route_bindings")).isEqualTo(62);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 58
                   AND checksum =
                       '556f2cc1554f0aaab6f88e50c34d64ca5cdd95af9b2d55072f6e6745f4652cc2'
                   AND auth_seed_artifact =
                       'product-surfaces-v1.bundle-v58.generated.json'
                   AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 58 AND baseline_state = 'OFF'
                   AND lifecycle_state = 'PUBLISHED' AND owner_key = 'PAY'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key FROM sys_product_feature_route_owners
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, String.class, FEATURE)).containsExactlyInAnyOrderElementsOf(ROUTES);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 58 AND subject_type = 'PRODUCT'
                   AND surface_key = 'hcm.operations' AND lifecycle_state = 'ACTIVE'
                """, Integer.class, FEATURE)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key, route_kind
                  FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, FEATURE)).containsExactlyInAnyOrder(
                        Map.of("route_contract_key", ROUTES.get(0), "route_kind", "PAGE"),
                        Map.of("route_contract_key", ROUTES.get(1), "route_kind", "DATA"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_feature_activation_changes
                 WHERE feature_key = ?
                """, Integer.class, FEATURE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_tenant_feature_enrollment_changes
                 WHERE feature_key = ?
                """, Integer.class, FEATURE)).isZero();

        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var reader = new TenantFeatureActivationRepository(jdbc);
        for (String route : ROUTES) {
            var decision = reader.evaluate(new TenantFeatureActivationReader.GateRequest(
                    tenant, "hcm", "hcm.operations", route, 58,
                    Instant.parse("2026-10-22T06:00:00Z")));
            assertThat(decision.featureKey()).isEqualTo(FEATURE);
            assertThat(decision.state()).isEqualTo(
                    TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
            assertThat(decision.reasonCode()).isEqualTo("TENANT_APP_ADOPTION_REQUIRED");
            assertThat(decision.preservesAuthority()).isFalse();
        }
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void executeV267(JdbcTemplate jdbc) {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V267__declare_hris_pay004_v58_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read V267 migration", exception);
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    private Map<String, List<Map<String, Object>>> v267CatalogSnapshot(JdbcTemplate jdbc) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("seed-release", """
                SELECT * FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 58
                """);
        queries.put("feature-policy", """
                SELECT * FROM sys_product_feature_activation_policies
                 WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
                 ORDER BY policy_version
                """);
        queries.put("route-owners", """
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
                 ORDER BY route_contract_key
                """);
        queries.put("route-bindings", """
                SELECT * FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
                 ORDER BY route_contract_key, policy_version
                """);
        queries.put("activations", """
                SELECT * FROM auth_product_feature_activation_changes
                 WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
                 ORDER BY activation_change_id
                """);
        queries.put("enrollments", """
                SELECT * FROM com_tenant_feature_enrollment_changes
                 WHERE feature_key = 'HRIS_PAY_RETIREMENT_WORKBENCH'
                 ORDER BY enrollment_change_id
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
