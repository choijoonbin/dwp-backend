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
class TenantFeatureActivationV264PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_PERFORMANCE_CONFIGURATION";

    @Test void cleanInstallKeepsTenPoliciesAndFortyOneRoutesOffWithoutEnrollment() {
        var source = source();
        flyway(source, "264").clean();
        flyway(source, "264").migrate();
        assertCatalog(new JdbcTemplate(source));
    }

    @Test void v263UpgradeAddsOnlyFourOwnersAndNoGrantsOrSharedActionOwnership() {
        var source = source();
        flyway(source, "264").clean();
        flyway(source, "263").migrate();
        var jdbc = new JdbcTemplate(source);
        var owners = jdbc.queryForList("SELECT * FROM sys_product_feature_route_owners ORDER BY route_contract_key");
        var grants = jdbc.queryForList("SELECT * FROM com_role_permissions ORDER BY role_permission_id");
        flyway(source, "264").migrate();
        assertCatalog(jdbc);
        assertThat(jdbc.queryForList("SELECT * FROM com_role_permissions ORDER BY role_permission_id")).isEqualTo(grants);
        assertThat(jdbc.queryForList("SELECT * FROM sys_product_feature_route_owners WHERE feature_key <> ? ORDER BY route_contract_key", FEATURE))
                .isEqualTo(owners);
    }

    private void assertCatalog(JdbcTemplate jdbc) {
        assertThat(count(jdbc, "sys_product_feature_activation_policies")).isEqualTo(10);
        assertThat(count(jdbc, "sys_product_feature_route_owners")).isEqualTo(41);
        assertThat(count(jdbc, "sys_product_feature_route_bindings")).isEqualTo(41);
        assertThat(count(jdbc, "auth_product_feature_activation_changes")).isZero();
        assertThat(count(jdbc, "com_tenant_feature_enrollment_changes")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 1 AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM' AND minimum_bundle_version = 55
                   AND baseline_state = 'OFF' AND owner_key = 'PER'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForList("SELECT route_contract_key FROM sys_product_feature_route_owners WHERE feature_key = ?",
                String.class, FEATURE)).containsExactlyInAnyOrder(
                "route.hcm.operations.performance-forms-scales.page", "route.hcm.operations.performance-forms-scales.data",
                "route.hcm.configuration.settings-performance.page", "route.hcm.configuration.settings-performance.data");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND route_kind = 'ACTION'
                """, Integer.class, FEATURE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release WHERE version = 55
                 AND intended_bundle_status = 'DRAFT' AND automatic_import_enabled = FALSE
                 AND checksum = '7fb2121a1209b87234bcb389a9bd4c4addca3c4e8c8776a9aac70d278c74d4e6'
                """, Integer.class)).isOne();
        Long tenant = jdbc.queryForObject("SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var reader = new TenantFeatureActivationRepository(jdbc);
        for (String route : List.of("route.hcm.configuration.settings-performance.page", "route.hcm.configuration.settings-performance.data")) {
            var decision = reader.evaluate(new TenantFeatureActivationReader.GateRequest(
                    tenant, "hcm", "hcm.management", route, 55, Instant.parse("2026-10-19T05:00:00Z")));
            assertThat(decision.featureKey()).isEqualTo(FEATURE);
            assertThat(decision.state()).isEqualTo(TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
            assertThat(decision.preservesAuthority()).isFalse();
        }
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
    private PGSimpleDataSource source() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        return source;
    }
    private Flyway flyway(PGSimpleDataSource source, String target) {
        return Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).target(target).load();
    }
}
