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
class TenantFeatureActivationV262PostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void freshInstallKeepsAllEightFeaturesOffAndAddsNoActivationOrEnrollment() {
        PGSimpleDataSource source = source();
        Flyway flyway = flyway(source, "262");
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_activation_policies",
                Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_owners",
                Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_bindings",
                Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE baseline_state = 'OFF' AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM'
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_product_feature_activation_changes",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM com_tenant_feature_enrollment_changes",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE version = 53 AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                   AND checksum =
                       'c655a850ae2ac01466edb37f2e1c69f9e269dd2a4a529c3a87b1610c0561a359'
                """, Integer.class)).isOne();
        assertHrm011AuthorityReadyWithoutDutyEnrollment(jdbc);
    }

    @Test
    void v261UpgradeAddsOnlyTheDefaultOffHrm011Catalog() {
        PGSimpleDataSource source = source();
        Flyway latest = flyway(source, null);
        latest.clean();
        flyway(source, "261").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_activation_policies",
                Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_bindings",
                Integer.class)).isEqualTo(29);

        flyway(source, "262").migrate();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_activation_policies",
                Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_bindings",
                Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = 'HRIS_HRM_CONTRACT_COMPENSATION_WORKBENCH'
                   AND minimum_bundle_version = 53
                   AND lifecycle_state = 'ACTIVE'
                   AND surface_key = 'hcm.operations'
                """, Integer.class)).isEqualTo(3);
        assertHrm011AuthorityReadyWithoutDutyEnrollment(jdbc);

        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var result = new TenantFeatureActivationRepository(jdbc).evaluate(
                new TenantFeatureActivationReader.GateRequest(
                        tenantId, "hcm", "hcm.operations",
                        "route.hcm.operations.contracts-compensation-basis.data", 53, NOW));
        assertThat(result.state()).isEqualTo(
                TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
        assertThat(result.reasonCode()).isEqualTo("TENANT_APP_ADOPTION_REQUIRED");
        assertThat(result.featureKey())
                .isEqualTo("HRIS_HRM_CONTRACT_COMPENSATION_WORKBENCH");
        assertThat(result.revision()).isEqualTo(TenantFeatureActivationReader.revision(
                String.join("|",
                        "v262",
                        "HRIS_HRM_CONTRACT_COMPENSATION_WORKBENCH",
                        "1",
                        "hcm",
                        "APP.HCM",
                        "53",
                        "route.hcm.operations.contracts-compensation-basis.data",
                        "DATA",
                        "PRODUCT",
                        "hcm.operations",
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

    private void assertHrm011AuthorityReadyWithoutDutyEnrollment(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_permissions WHERE code = 'ISSUE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_tenant_resource_templates
                 WHERE resource_key = 'ACTION.HRIS_EMPLOYMENT_CONTRACT'
                   AND resource_type = 'ACTION' AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_tenant_role_permission_templates
                 WHERE role_code = 'HR_ADMIN'
                   AND resource_key = 'ACTION.HRIS_EMPLOYMENT_CONTRACT'
                   AND permission_code = 'ISSUE'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_responsibility_catalog
                 WHERE responsibility_code = 'HRIS_DUTY_CONTRACT_ISSUER'
                   AND risk_tier = 'L3' AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_scoped_duty_catalog duty
                  JOIN sys_admin_scoped_duty_capabilities capability
                    ON capability.duty_code = duty.duty_code
                 WHERE duty.duty_code = 'HRIS_DUTY_CONTRACT_ISSUER'
                   AND duty.product_key = 'hcm'
                   AND duty.product_resource_key = 'APP.HCM'
                   AND duty.resource_key = 'ACTION.HRIS_EMPLOYMENT_CONTRACT'
                   AND duty.lifecycle_state = 'ACTIVE'
                   AND capability.capability_contract_key =
                       'hcm.operations.contract.issue'
                   AND capability.permission_resource_key =
                       'ACTION.HRIS_EMPLOYMENT_CONTRACT'
                   AND capability.permission_code = 'ISSUE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_app_preset_catalog preset
                  JOIN sys_admin_app_preset_duties duty
                    ON duty.preset_code = preset.preset_code
                 WHERE preset.preset_code = 'HRIS_CONTRACT_ISSUER'
                   AND preset.product_key = 'hcm'
                   AND preset.product_resource_key = 'APP.HCM'
                   AND preset.responsibility_code = 'APP_CONFIG_ADMIN'
                   AND preset.lifecycle_state = 'ACTIVE'
                   AND duty.duty_code = 'HRIS_DUTY_CONTRACT_ISSUER'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_resources
                 WHERE key = 'ACTION.HRIS_EMPLOYMENT_CONTRACT'
                   AND type = 'ACTION' AND enabled = TRUE
                """, Integer.class)).isEqualTo(jdbc.queryForObject(
                        "SELECT count(*) FROM com_tenants", Integer.class));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_role_permissions role_permission
                  JOIN com_resources resource
                    ON resource.tenant_id = role_permission.tenant_id
                   AND resource.resource_id = role_permission.resource_id
                  JOIN com_permissions permission
                    ON permission.permission_id = role_permission.permission_id
                 WHERE resource.key = 'ACTION.HRIS_EMPLOYMENT_CONTRACT'
                   AND permission.code = 'ISSUE'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE responsibility_code = 'HRIS_DUTY_CONTRACT_ISSUER'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments
                 WHERE duty_code = 'HRIS_DUTY_CONTRACT_ISSUER'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE preset_code = 'HRIS_CONTRACT_ISSUER'
                """, Integer.class)).isZero();
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
