package com.dwp.services.provider.provisioning;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProviderOnboardingFoundationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ProviderOnboardingFoundationRepository repository;

    @BeforeEach
    void migrateDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new ProviderOnboardingFoundationRepository(jdbc);
    }

    @Test
    void committedFoundationMustRetainExactCrashRehydrationState() {
        Foundation fixture = foundation();
        assertThat(repository.matches(fixture.expected())).isTrue();

        assertDriftRejected(
                fixture,
                "UPDATE prv_tenants SET lifecycle_state = 'ACTIVE' WHERE provider_tenant_id = ?",
                "UPDATE prv_tenants SET lifecycle_state = 'PROVISIONING' WHERE provider_tenant_id = ?");
        assertDriftRejected(
                fixture,
                "UPDATE prv_tenants SET onboarding_state = 'PENDING_EXTERNAL' WHERE provider_tenant_id = ?",
                "UPDATE prv_tenants SET onboarding_state = 'CONTROL_PLANE_READY' WHERE provider_tenant_id = ?");
        assertDriftRejected(
                fixture,
                "UPDATE prv_tenants SET auth_tenant_id = 880001 WHERE provider_tenant_id = ?",
                "UPDATE prv_tenants SET auth_tenant_id = NULL WHERE provider_tenant_id = ?");
        assertDriftRejected(
                fixture,
                "UPDATE prv_tenants SET schema_version = 2 WHERE provider_tenant_id = ?",
                "UPDATE prv_tenants SET schema_version = 1 WHERE provider_tenant_id = ?");
        assertAdministratorDriftRejected(fixture, "lifecycle_state = 'INVITED'", "lifecycle_state = 'PENDING'");
        assertAdministratorDriftRejected(fixture, "auth_user_id = 880002", "auth_user_id = NULL");
        assertAdministratorDriftRejected(fixture, "role_code = 'SECURITY_ADMIN'", "role_code = 'TENANT_ADMIN'");
        jdbc.update("""
                UPDATE prv_organization_subscriptions
                   SET lifecycle_state = 'SUSPENDED'
                 WHERE organization_id = ? AND lifecycle_state = 'ACTIVE'
                """, fixture.expected().organizationId());
        assertThat(repository.matches(fixture.expected())).isFalse();
    }

    @Test
    void retiredExpectedConfigurationCannotMaskAnActiveDriftValue() {
        Foundation fixture = foundation();
        jdbc.update("""
                UPDATE prv_configuration_values
                   SET lifecycle_state = 'RETIRED'
                 WHERE provider_tenant_id = ?
                   AND namespace = 'provider.tenant.extensions'
                   AND lifecycle_state = 'ACTIVE'
                """, fixture.tenantId());
        jdbc.update("""
                INSERT INTO prv_configuration_values (
                    namespace, schema_version, provider_tenant_id, lifecycle_state, value)
                VALUES ('provider.tenant.extensions', 1, ?, 'ACTIVE', '{"drift":true}'::jsonb)
                """, fixture.tenantId());

        assertThat(repository.matches(fixture.expected())).isFalse();
    }

    @Test
    void entitlementAssignmentsMustMatchTheApprovedRowsAndMetadataExactly() {
        Foundation fixture = foundation();

        assertEntitlementDriftRejected(
                fixture, "configuration = '{\"drift\":true}'::jsonb", "configuration = '{}'::jsonb");
        assertEntitlementDriftRejected(fixture, "version = 1", "version = 0");
        assertEntitlementDriftRejected(
                fixture, "lifecycle_state = 'SUSPENDED'", "lifecycle_state = 'ACTIVE'");

        jdbc.update("""
                INSERT INTO prv_tenant_entitlements (
                    provider_tenant_id, entitlement_id, lifecycle_state, configuration)
                SELECT ?, entitlement_id, 'RETIRED', '{}'::jsonb
                  FROM prv_entitlement_catalog
                 WHERE entitlement_key <> 'core.workspace'
                 ORDER BY entitlement_key
                 LIMIT 1
                """, fixture.tenantId());
        assertThat(repository.matches(fixture.expected())).isFalse();
    }

    private void assertDriftRejected(
            Foundation fixture,
            String driftSql,
            String restoreSql) {
        jdbc.update(driftSql, fixture.tenantId());
        assertThat(repository.matches(fixture.expected())).isFalse();
        jdbc.update(restoreSql, fixture.tenantId());
        assertThat(repository.matches(fixture.expected())).isTrue();
    }

    private void assertAdministratorDriftRejected(
            Foundation fixture,
            String driftAssignment,
            String restoreAssignment) {
        jdbc.update("UPDATE prv_tenant_administrators SET " + driftAssignment
                + " WHERE provider_tenant_id = ?", fixture.tenantId());
        assertThat(repository.matches(fixture.expected())).isFalse();
        jdbc.update("UPDATE prv_tenant_administrators SET " + restoreAssignment
                + " WHERE provider_tenant_id = ?", fixture.tenantId());
        assertThat(repository.matches(fixture.expected())).isTrue();
    }

    private void assertEntitlementDriftRejected(
            Foundation fixture,
            String driftAssignment,
            String restoreAssignment) {
        jdbc.update("UPDATE prv_tenant_entitlements SET " + driftAssignment
                + " WHERE provider_tenant_id = ?", fixture.tenantId());
        assertThat(repository.matches(fixture.expected())).isFalse();
        jdbc.update("UPDATE prv_tenant_entitlements SET " + restoreAssignment
                + " WHERE provider_tenant_id = ?", fixture.tenantId());
        assertThat(repository.matches(fixture.expected())).isTrue();
    }

    private Foundation foundation() {
        String suffix = UUID.randomUUID().toString().substring(0, 10);
        String organizationKey = "foundation-org-" + suffix;
        String tenantKey = "foundation-tenant-" + suffix;
        UUID organizationId = jdbc.queryForObject("""
                INSERT INTO prv_organizations (organization_key, display_name)
                VALUES (?, 'Foundation organization') RETURNING organization_id
                """, UUID.class, organizationKey);
        jdbc.update("""
                INSERT INTO prv_organization_subscriptions (
                    organization_id, service_plan_id, lifecycle_state)
                SELECT ?, service_plan_id, 'ACTIVE'
                  FROM prv_service_plans
                 WHERE service_tier = 'ENTERPRISE' AND lifecycle_state = 'ACTIVE'
                """, organizationId);
        UUID tenantId = jdbc.queryForObject("""
                INSERT INTO prv_tenants (
                    tenant_key, organization_id, display_name, service_tier, data_region,
                    isolation_model, lifecycle_state, onboarding_state, environment_key,
                    default_locale, time_zone, schema_version, configuration)
                VALUES (?, ?, 'Foundation tenant', 'ENTERPRISE', 'ap-northeast-2',
                        'POOL', 'PROVISIONING', 'CONTROL_PLANE_READY', 'production',
                        'en', 'Asia/Seoul', 1, '{}'::jsonb)
                RETURNING provider_tenant_id
                """, UUID.class, tenantKey, organizationId);
        jdbc.update("""
                INSERT INTO prv_configuration_values (
                    namespace, schema_version, provider_tenant_id, lifecycle_state, value)
                VALUES ('provider.tenant.extensions', 1, ?, 'ACTIVE', '{}'::jsonb)
                """, tenantId);
        jdbc.update("""
                INSERT INTO prv_tenant_entitlements (
                    provider_tenant_id, entitlement_id, lifecycle_state, configuration)
                SELECT ?, entitlement_id, 'ACTIVE', '{}'::jsonb
                  FROM prv_entitlement_catalog
                 WHERE entitlement_key = 'core.workspace'
                """, tenantId);
        jdbc.update("""
                INSERT INTO prv_tenant_domains (
                    provider_tenant_id, domain_name, domain_type, verification_method,
                    verification_state, primary_domain, requested_primary, verified_at)
                VALUES (?, ?, 'LOGIN', 'INTERNAL', 'VERIFIED', TRUE, FALSE, CURRENT_TIMESTAMP)
                """, tenantId, tenantKey + ".local");
        jdbc.update("""
                INSERT INTO prv_tenant_administrators (
                    provider_tenant_id, email, display_name, role_code,
                    lifecycle_state, primary_administrator)
                VALUES (?, 'admin@foundation.example', 'Foundation administrator',
                        'TENANT_ADMIN', 'PENDING', TRUE)
                """, tenantId);
        ProviderOnboardingFoundationRepository.ControlFoundation expected =
                new ProviderOnboardingFoundationRepository.ControlFoundation(
                        tenantId, organizationId, organizationKey, "Foundation organization",
                        null, null, tenantKey, "Foundation tenant", "production", "ENTERPRISE",
                        "ap-northeast-2", "POOL", "en", "Asia/Seoul", "{}",
                        "admin@foundation.example", "Foundation administrator",
                        List.of("core.workspace"), null, null, null);
        return new Foundation(tenantId, expected);
    }

    private record Foundation(
            UUID tenantId,
            ProviderOnboardingFoundationRepository.ControlFoundation expected) {
    }
}
