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

@Testcontainers
class TenantFeatureActivationV268PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_PER_TEAM_PERFORMANCE_FEEDBACK";
    private static final List<String> ROUTES = List.of(
            "route.hcm.team.performance.page",
            "route.hcm.team.performance.data",
            "route.hcm.personal.performance-feedback.page",
            "route.hcm.performance.feedback-request-options.data",
            "route.hcm.performance.feedback-request-create.action",
            "route.hcm.personal.feedback-assignments-mine.data",
            "route.hcm.personal.feedback-assignment-detail.data",
            "route.hcm.personal.feedback-response-submit.action",
            "route.hcm.performance.feedback-release.data");
    private static final List<String> ZERO_EFFECT_TABLES = List.of(
            "sys_tenant_role_permission_templates",
            "com_role_permissions",
            "com_admin_scoped_duty_assignments",
            "sys_admin_app_preset_duties",
            "com_admin_resource_set_members",
            "com_principal_resource_grants",
            "sys_builtin_role_catalog",
            "com_roles",
            "com_role_members",
            "auth_product_feature_activation_changes",
            "com_tenant_feature_enrollment_changes");

    @Test
    void cleanInstallAddsOnlyCatalogReadinessAndLeavesTheFeatureOff() {
        var source = source();
        flyway(source, "268").clean();
        flyway(source, "268").migrate();
        assertCatalog(new JdbcTemplate(source));
    }

    @Test
    void v267UpgradePreservesAllSevenAuthorityAndLegacyBoundaries() {
        var source = source();
        flyway(source, "268").clean();
        flyway(source, "267").migrate();
        var jdbc = new JdbcTemplate(source);
        Map<String, List<Map<String, Object>>> unchanged = ZERO_EFFECT_TABLES.stream()
                .collect(java.util.stream.Collectors.toMap(
                        table -> table,
                        table -> jdbc.queryForList("SELECT * FROM " + table)));

        flyway(source, "268").migrate();

        assertCatalog(jdbc);
        for (String table : ZERO_EFFECT_TABLES) {
            assertThat(jdbc.queryForList("SELECT * FROM " + table))
                    .as(table).isEqualTo(unchanged.get(table));
        }
    }

    @Test
    void v267UpgradePreservesCustomRolesThatUseLegacyAdapterNames() {
        var source = source();
        flyway(source, "268").clean();
        flyway(source, "267").migrate();
        var jdbc = new JdbcTemplate(source);
        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        for (String code : List.of("EMPLOYEE", "MANAGER")) {
            jdbc.update("""
                    INSERT INTO com_roles (
                        tenant_id, code, name, description, status, role_type,
                        privileged, assignable_to_groups, builtin_role_code)
                    VALUES (?, ?, ?, 'Customer-owned role', 'ACTIVE', 'CUSTOM',
                            FALSE, TRUE, NULL)
                    """, tenant, code, "Customer " + code);
        }
        List<Map<String, Object>> before = jdbc.queryForList("""
                SELECT * FROM com_roles
                 WHERE tenant_id = ? AND code IN ('EMPLOYEE', 'MANAGER')
                 ORDER BY code
                """, tenant);

        flyway(source, "268").migrate();

        assertThat(jdbc.queryForList("""
                SELECT * FROM com_roles
                 WHERE tenant_id = ? AND code IN ('EMPLOYEE', 'MANAGER')
                 ORDER BY code
                """, tenant)).isEqualTo(before);
        assertCatalog(jdbc);
    }

    @Test
    void reapplyingExactV268IsANoopForEveryOwnedCatalogTuple() {
        var source = source();
        flyway(source, "268").clean();
        flyway(source, "267").migrate();
        var jdbc = new JdbcTemplate(source);

        executeV268(jdbc);
        Map<String, List<Map<String, Object>>> afterFirstApply = v268Snapshot(jdbc);
        executeV268(jdbc);

        assertThat(v268Snapshot(jdbc)).isEqualTo(afterFirstApply);
        assertCatalog(jdbc);
    }

    @Test
    void conflictingTemplateAbortsAndRollsBackEveryV268Statement() {
        var source = source();
        flyway(source, "268").clean();
        flyway(source, "267").migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO sys_tenant_resource_templates (
                    resource_key, resource_type, display_name, required_entitlement)
                VALUES ('DATA.HRIS_TEAM_WORKBENCH', 'DATA',
                        'conflicting display name', 'core.people')
                """);
        Map<String, List<Map<String, Object>>> before = v268Snapshot(jdbc);

        assertThatThrownBy(() -> executeV268(jdbc)).rootCause()
                .hasMessageContaining(
                        "V268 conflict: PER-008 resource templates differ from the sealed tuples");

        assertThat(v268Snapshot(jdbc)).isEqualTo(before);
    }

    private void assertCatalog(JdbcTemplate jdbc) {
        assertThat(count(jdbc, "sys_product_feature_activation_policies")).isEqualTo(14);
        assertThat(count(jdbc, "sys_product_feature_route_owners")).isEqualTo(71);
        assertThat(count(jdbc, "sys_product_feature_route_bindings")).isEqualTo(71);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 59
                   AND checksum =
                       '56582749cebf382c5c2669d83a30fb4c81fcb532db2878b15e6a83ced6898094'
                   AND auth_seed_artifact =
                       'product-surfaces-v1.bundle-v59.generated.json'
                   AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 59 AND baseline_state = 'OFF'
                   AND lifecycle_state = 'PUBLISHED' AND owner_key = 'PER'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key FROM sys_product_feature_route_owners
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, String.class, FEATURE)).containsExactlyInAnyOrderElementsOf(ROUTES);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 1
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 59 AND subject_type = 'PRODUCT'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, FEATURE)).isEqualTo(9);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_templates
                 WHERE resource_key IN ('DATA.HRIS_TEAM_WORKBENCH',
                        'ACTION.HRIS_PERFORMANCE_FEEDBACK',
                        'DATA.HRIS_PERFORMANCE_FEEDBACK')
                   AND required_entitlement = 'core.people'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_templates
                 WHERE resource_key = 'DATA.HR_TALENT'
                   AND resource_type = 'DATA'
                   AND display_name = 'Talent, learning, and employee journeys'
                   AND required_entitlement = 'core.people'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code IN (
                       'HRIS_DUTY_TEAM_WORKBENCH_READER',
                       'HRIS_DUTY_SELF_PERFORMANCE_READER',
                       'HRIS_DUTY_FEEDBACK_REQUESTER',
                       'HRIS_DUTY_FEEDBACK_PROVIDER',
                       'HRIS_DUTY_FEEDBACK_AGGREGATE_READER')
                   AND product_key = 'hcm'
                   AND product_resource_key = 'APP.HCM'
                   AND audit_policy_exception = FALSE
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code IN (
                       'HRIS_DUTY_TEAM_WORKBENCH_READER',
                       'HRIS_DUTY_SELF_PERFORMANCE_READER',
                       'HRIS_DUTY_FEEDBACK_REQUESTER',
                       'HRIS_DUTY_FEEDBACK_PROVIDER',
                       'HRIS_DUTY_FEEDBACK_AGGREGATE_READER')
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_resources
                 WHERE key IN ('DATA.HRIS_TEAM_WORKBENCH',
                        'ACTION.HRIS_PERFORMANCE_FEEDBACK',
                        'DATA.HRIS_PERFORMANCE_FEEDBACK')
                   AND enabled = TRUE
                """, Integer.class)).isEqualTo(3 * count(jdbc, "com_tenants"));
        for (String table : List.of(
                "auth_product_feature_activation_changes",
                "com_tenant_feature_enrollment_changes")) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE feature_key = ?",
                    Integer.class, FEATURE)).as(table).isZero();
        }

        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var reader = new TenantFeatureActivationRepository(jdbc);
        for (String route : ROUTES) {
            String surface = route.startsWith("route.hcm.team.")
                    ? "hcm.team"
                    : route.startsWith("route.hcm.personal.")
                            ? "hcm.personal" : "hcm.operations";
            var decision = reader.evaluate(new TenantFeatureActivationReader.GateRequest(
                    tenant, "hcm", surface, route, 59,
                    Instant.parse("2026-10-23T06:00:00Z")));
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

    private void executeV268(JdbcTemplate jdbc) {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V268__declare_hris_per008_v59_authorization_seed.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read V268 migration", exception);
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    private Map<String, List<Map<String, Object>>> v268Snapshot(JdbcTemplate jdbc) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("templates", """
                SELECT * FROM sys_tenant_resource_templates
                 WHERE resource_key IN ('DATA.HRIS_TEAM_WORKBENCH',
                        'ACTION.HRIS_PERFORMANCE_FEEDBACK',
                        'DATA.HRIS_PERFORMANCE_FEEDBACK') ORDER BY resource_key
                """);
        queries.put("duties", """
                SELECT * FROM sys_admin_scoped_duty_catalog
                 WHERE duty_code LIKE 'HRIS_DUTY_%FEEDBACK%'
                    OR duty_code IN ('HRIS_DUTY_TEAM_WORKBENCH_READER',
                                     'HRIS_DUTY_SELF_PERFORMANCE_READER')
                 ORDER BY duty_code
                """);
        queries.put("duty-capabilities", """
                SELECT * FROM sys_admin_scoped_duty_capabilities
                 WHERE duty_code LIKE 'HRIS_DUTY_%FEEDBACK%'
                    OR duty_code IN ('HRIS_DUTY_TEAM_WORKBENCH_READER',
                                     'HRIS_DUTY_SELF_PERFORMANCE_READER')
                 ORDER BY duty_code, capability_contract_key
                """);
        queries.put("tenant-resources", """
                SELECT * FROM com_resources
                 WHERE key IN ('DATA.HRIS_TEAM_WORKBENCH',
                        'ACTION.HRIS_PERFORMANCE_FEEDBACK',
                        'DATA.HRIS_PERFORMANCE_FEEDBACK')
                 ORDER BY tenant_id, type, key
                """);
        queries.put("seed-release", """
                SELECT * FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 59
                """);
        queries.put("feature-policy", """
                SELECT * FROM sys_product_feature_activation_policies
                 WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
                """);
        queries.put("route-owners", """
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
                 ORDER BY route_contract_key
                """);
        queries.put("route-bindings", """
                SELECT * FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_PER_TEAM_PERFORMANCE_FEEDBACK'
                 ORDER BY route_contract_key
                """);
        for (String table : ZERO_EFFECT_TABLES) {
            queries.put("zero:" + table, "SELECT * FROM " + table);
        }
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
