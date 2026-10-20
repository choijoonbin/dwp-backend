package com.dwp.services.auth.tenantfeatureactivation;

import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TenantFeatureActivationV265PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_AUTOMATION_CONTROL_PLANE";
    private static final List<String> ROUTES = List.of(
            "route.hcm.configuration.automation-catalog.data",
            "route.hcm.configuration.automation-definition-create.action",
            "route.hcm.configuration.automation-run-queue.action",
            "route.hcm.configuration.automation-run-detail.data",
            "route.hcm.configuration.automation-run-retry.action",
            "route.hcm.configuration.automation-version-create.action",
            "route.hcm.configuration.automation-version-validate.action",
            "route.hcm.configuration.automation-version-submit.action",
            "route.hcm.configuration.automation-version-approve.action",
            "route.hcm.configuration.automation-version-activate.action",
            "route.hcm.configuration.automation-schedule-create.action",
            "route.hcm.configuration.automation-schedule-submit.action",
            "route.hcm.configuration.automation-schedule-approve.action",
            "route.hcm.configuration.automation-schedule-activate.action",
            "route.hcm.configuration.automation-schedule-suspend.action",
            "route.hcm.configuration.automation-run-cancel.action");

    @Test
    void cleanInstallKeepsElevenPoliciesAndFiftySevenRoutesOffWithoutEnrollment() {
        var source = source();
        flyway(source, "265").clean();
        flyway(source, "265").migrate();
        assertCatalog(new JdbcTemplate(source));
    }

    @Test
    void v264UpgradeAddsOnlySixteenOwnersAndNoGrants() {
        var source = source();
        flyway(source, "265").clean();
        flyway(source, "264").migrate();
        var jdbc = new JdbcTemplate(source);
        var owners = jdbc.queryForList(
                "SELECT * FROM sys_product_feature_route_owners ORDER BY route_contract_key");
        var grants = jdbc.queryForList("SELECT * FROM com_role_permissions ORDER BY role_permission_id");
        flyway(source, "265").migrate();
        assertCatalog(jdbc);
        assertThat(jdbc.queryForList("SELECT * FROM com_role_permissions ORDER BY role_permission_id"))
                .isEqualTo(grants);
        assertThat(jdbc.queryForList("""
                SELECT * FROM sys_product_feature_route_owners
                 WHERE feature_key <> ? ORDER BY route_contract_key
                """, FEATURE)).isEqualTo(owners);
    }

    private void assertCatalog(JdbcTemplate jdbc) {
        assertThat(count(jdbc, "sys_product_feature_activation_policies")).isEqualTo(11);
        assertThat(count(jdbc, "sys_product_feature_route_owners")).isEqualTo(57);
        assertThat(count(jdbc, "sys_product_feature_route_bindings")).isEqualTo(57);
        assertThat(count(jdbc, "auth_product_feature_activation_changes")).isZero();
        assertThat(count(jdbc, "com_tenant_feature_enrollment_changes")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 1 AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM' AND minimum_bundle_version = 56
                   AND baseline_state = 'OFF' AND owner_key = 'SYS'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key FROM sys_product_feature_route_owners
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, String.class, FEATURE)).containsExactlyInAnyOrderElementsOf(ROUTES);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND route_kind = 'DATA'
                """, Integer.class, FEATURE)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND route_kind = 'ACTION'
                """, Integer.class, FEATURE)).isEqualTo(14);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 1 AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM' AND minimum_bundle_version = 56
                   AND subject_type = 'PRODUCT' AND surface_key = 'hcm.management'
                """, Integer.class, FEATURE)).isEqualTo(16);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release WHERE version = 56
                 AND intended_bundle_status = 'DRAFT' AND automatic_import_enabled = FALSE
                 AND checksum = '4c1e82c4ac70f7bbc4aba7a41d77b8fd238da0478501cbdfed97ebd4b5dd0930'
                """, Integer.class)).isOne();
        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var reader = new TenantFeatureActivationRepository(jdbc);
        for (String route : List.of(
                "route.hcm.configuration.automation-catalog.data",
                "route.hcm.configuration.automation-run-queue.action")) {
            var decision = reader.evaluate(new TenantFeatureActivationReader.GateRequest(
                    tenant, "hcm", "hcm.management", route, 56,
                    Instant.parse("2026-10-20T06:00:00Z")));
            assertThat(decision.featureKey()).isEqualTo(FEATURE);
            assertThat(decision.state()).isEqualTo(
                    TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
            assertThat(decision.preservesAuthority()).isFalse();
        }
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
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
