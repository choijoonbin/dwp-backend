package com.dwp.services.auth.tenantfeatureactivation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TenantFeatureActivationV261PostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-18T07:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void freshInstallHasSevenDefaultOffPoliciesAndTwentyNineExactRouteTuples() {
        PGSimpleDataSource source = source();
        Flyway flyway = flyway(source, "261");
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_activation_policies",
                Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_owners",
                Integer.class)).isEqualTo(29);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_bindings",
                Integer.class)).isEqualTo(29);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE baseline_state = 'OFF' AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM'
                """, Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_product_feature_activation_changes",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM com_tenant_feature_enrollment_changes",
                Integer.class)).isZero();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE version = 52 AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                   AND checksum =
                       'b33fbf968e71f97b09fa497fdfad7d2f6854199170b79fd1602a0ea604977346'
                """, Integer.class)).isOne();
    }

    @Test
    void v260UpgradeAddsOnlyTheDefaultOffHrm009CatalogAndPersonalSurface() {
        PGSimpleDataSource source = source();
        Flyway latest = flyway(source, null);
        latest.clean();
        flyway(source, "260").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_activation_policies",
                Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_bindings",
                Integer.class)).isEqualTo(24);

        flyway(source, "261").migrate();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_activation_policies",
                Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_bindings",
                Integer.class)).isEqualTo(29);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_HRM_CERTIFICATE_WORKBENCH'
                   AND minimum_bundle_version = 52
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_HRM_CERTIFICATE_WORKBENCH'
                   AND surface_key = 'hcm.personal'
                """, Integer.class)).isEqualTo(2);

        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var result = new TenantFeatureActivationRepository(jdbc).evaluate(
                new TenantFeatureActivationReader.GateRequest(
                        tenantId, "hcm", "hcm.personal",
                        "route.hcm.personal.requests.data", 52, NOW));
        assertThat(result.state()).isEqualTo(
                TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
        assertThat(result.reasonCode()).isEqualTo("TENANT_APP_ADOPTION_REQUIRED");
        assertThat(result.featureKey()).isEqualTo("HRIS_HRM_CERTIFICATE_WORKBENCH");
        assertThat(result.revision()).isEqualTo(TenantFeatureActivationReader.revision(
                String.join("|",
                        "v261",
                        "HRIS_HRM_CERTIFICATE_WORKBENCH",
                        "1",
                        "hcm",
                        "APP.HCM",
                        "52",
                        "route.hcm.personal.requests.data",
                        "DATA",
                        "PRODUCT",
                        "hcm.personal",
                        Long.toString(tenantId),
                        "app=ABSENT|global=NOT_READ|tenant=NOT_READ")));
    }

    private PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private Flyway flyway(PGSimpleDataSource source, String target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
