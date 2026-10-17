package com.dwp.services.auth.tenantfeatureactivation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TenantFeatureActivationV259PostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-17T07:00:00Z");
    private static final String FEATURE = "HRIS_ORGANIZATION_POSITIONS";
    private static final String ROUTE =
            "route.hcm.operations.organization-positions.page";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void cleanAndV258UpgradeRemainDefaultOffThenEnforceWorkflowAndRuntimeGate() {
        PGSimpleDataSource source = source();
        Flyway fresh = flyway(source, null);
        fresh.clean();
        fresh.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertCatalogOnly(jdbc, 6, 24);

        flyway(source, null).clean();
        flyway(source, "258").migrate();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name = 'sys_product_feature_activation_policies'
                """, Integer.class)).isZero();
        flyway(source, "259").migrate();
        assertCatalogOnly(jdbc, 4, 16);

        TenantFeatureActivationRepository reader =
                new TenantFeatureActivationRepository(jdbc);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var request = new TenantFeatureActivationReader.GateRequest(
                tenantId, "hcm", "hcm.operations", ROUTE, 50, NOW);

        var appMissing = reader.evaluate(request);
        assertThat(appMissing.state())
                .isEqualTo(TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
        assertThat(appMissing.reasonCode()).isEqualTo("TENANT_APP_ADOPTION_REQUIRED");

        enableEphemeralHcmAdoption(jdbc, tenantId);
        var featureOff = reader.evaluate(request);
        assertThat(featureOff.reasonCode()).isEqualTo("FEATURE_ACTIVATION_REQUIRED");

        TenantFeatureActivationControlRepository controls =
                new TenantFeatureActivationControlRepository(jdbc);
        TenantFeatureActivationService service = new TenantFeatureActivationService(
                controls, Clock.fixed(NOW, ZoneOffset.UTC));

        var global = service.requestGlobal(
                FEATURE, 50, "Release the reviewed organization workspace feature.",
                "Release-Maker", "global-request");
        assertThat(global.requestedBy()).isEqualTo("release-maker");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth_product_feature_activation_changes (
                    feature_key, policy_version, target_bundle_version,
                    justification, requested_by)
                VALUES ('HRIS_PAYROLL_RUN_PREPARE', 1, 50,
                        'Reject a noncanonical direct database actor identity.', 'Case-Maker')
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> service.requestGlobal(
                FEATURE, 50, "A second open winner must be rejected by the database.",
                "other-maker", "global-duplicate"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> service.submitGlobal(
                global.changeId(), 99, "release-maker", "global-stale"))
                .isInstanceOf(IllegalStateException.class);
        var submitted = service.submitGlobal(
                global.changeId(), global.version(), "RELEASE-MAKER", "global-submit");
        assertThatThrownBy(() -> service.approveGlobal(
                global.changeId(), submitted.version(), "release-MAKER", "global-self-review"))
                .isInstanceOf(IllegalStateException.class);
        var approved = service.approveGlobal(
                global.changeId(), submitted.version(), "Security-Reviewer", "global-approve");
        assertThat(approved.approvedBy()).isEqualTo("security-reviewer");
        assertThatThrownBy(() -> service.activateGlobal(
                global.changeId(), approved.version(), "SECURITY-REVIEWER",
                UUID.randomUUID(), "global-self-activate"))
                .isInstanceOf(IllegalStateException.class);
        UUID globalReceipt = UUID.randomUUID();
        var active = service.activateGlobal(
                global.changeId(), approved.version(), "release-activator",
                globalReceipt, "global-activate");
        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(active.activationReceiptId()).isEqualTo(globalReceipt);

        var tenantMissing = reader.evaluate(request);
        assertThat(tenantMissing.state())
                .isEqualTo(TenantFeatureActivationReader.State.TENANT_NOT_ENROLLED);
        assertThat(tenantMissing.reasonCode())
                .isEqualTo("TENANT_FEATURE_ENROLLMENT_REQUIRED");

        var enrollment = service.requestTenant(
                tenantId, FEATURE, "Enroll the independently approved test tenant feature.",
                7101L, "tenant-request");
        var enrollmentSubmitted = service.submitTenant(
                enrollment.changeId(), enrollment.version(), 7101L, "tenant-submit");
        assertThatThrownBy(() -> service.approveTenant(
                enrollment.changeId(), enrollmentSubmitted.version(),
                7101L, "tenant-self-review"))
                .isInstanceOf(IllegalStateException.class);
        var enrollmentApproved = service.approveTenant(
                enrollment.changeId(), enrollmentSubmitted.version(),
                7102L, "tenant-approve");
        assertThatThrownBy(() -> service.activateTenant(
                enrollment.changeId(), enrollmentApproved.version(),
                7102L, UUID.randomUUID(), "tenant-self-activate"))
                .isInstanceOf(IllegalStateException.class);
        UUID enrollmentReceipt = UUID.randomUUID();
        var enrolled = service.activateTenant(
                enrollment.changeId(), enrollmentApproved.version(),
                7103L, enrollmentReceipt, "tenant-activate");
        assertThat(enrolled.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(enrolled.activationReceiptId()).isEqualTo(enrollmentReceipt);

        var allowed = reader.evaluate(request);
        assertThat(allowed.state()).isEqualTo(TenantFeatureActivationReader.State.ACTIVE);
        assertThat(allowed.reasonCode()).isEqualTo("ACTIVE");
        assertThat(allowed.revision()).isNotBlank().isNotEqualTo(tenantMissing.revision());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_product_feature_control_events",
                Integer.class)).isEqualTo(8);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sys_product_feature_activation_policies
                   SET owner_key = 'OTHER' WHERE feature_key = ?
                """, FEATURE)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM sys_product_feature_route_bindings
                 WHERE route_contract_key = ?
                """, ROUTE)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auth_product_feature_control_events
                   SET event_type = 'TAMPERED'
                 WHERE aggregate_id = ?
                """, global.changeId())).isInstanceOf(DataAccessException.class);

        jdbc.update("""
                INSERT INTO sys_product_feature_activation_policies (
                    feature_key, policy_version, product_key, app_resource_key,
                    minimum_bundle_version, baseline_state, owner_key)
                VALUES (?, 2, 'hcm', 'APP.HCM', 50, 'OFF', 'HRM')
                """, FEATURE);
        assertThat(reader.evaluate(request).state())
                .isEqualTo(TenantFeatureActivationReader.State.AUTHORITY_UNAVAILABLE);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sys_product_feature_route_owners (
                    route_contract_key, feature_key)
                VALUES (?, 'HRIS_PAYROLL_RUN_PREPARE')
                """, ROUTE)).isInstanceOf(DataAccessException.class);
        jdbc.update("""
                INSERT INTO sys_product_feature_route_bindings (
                    route_contract_key, feature_key, policy_version, product_key,
                    app_resource_key, minimum_bundle_version, route_kind,
                    subject_type, surface_key, lifecycle_state)
                VALUES (?, ?, 2, 'hcm', 'APP.HCM', 50, 'PAGE',
                        'PRODUCT', 'hcm.operations', 'RETIRED')
                """, ROUTE, FEATURE);
        assertThat(reader.evaluate(request).state())
                .isEqualTo(TenantFeatureActivationReader.State.UNGATED);
        jdbc.update("""
                INSERT INTO sys_product_feature_route_bindings (
                    route_contract_key, feature_key, policy_version, product_key,
                    app_resource_key, minimum_bundle_version, route_kind,
                    subject_type, surface_key, lifecycle_state)
                VALUES ('route.hcm.operations.organization-graph.data', ?, 2,
                        'hcm', 'APP.HCM', 50, 'DATA', 'PRODUCT',
                        'hcm.operations', 'ACTIVE')
                """, FEATURE);
        var successorRequest = new TenantFeatureActivationReader.GateRequest(
                tenantId, "hcm", "hcm.operations",
                "route.hcm.operations.organization-graph.data", 50, NOW);
        assertThat(reader.evaluate(successorRequest).reasonCode())
                .isEqualTo("FEATURE_ACTIVATION_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT max(policy_version)
                  FROM sys_product_feature_activation_policies
                 WHERE feature_key = ?
                """, Long.class, FEATURE)).isEqualTo(2L);
    }

    @Test
    void v260MinimumBundle51IsAValidDefaultOffSuccessor() {
        PGSimpleDataSource source = source();
        Flyway flyway = flyway(source, null);
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertCatalogOnly(jdbc, 6, 24);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var result = new TenantFeatureActivationRepository(jdbc).evaluate(
                new TenantFeatureActivationReader.GateRequest(
                        tenantId, "hcm", "hcm.team",
                        "route.hcm.team.people-changes.data", 51, NOW));
        assertThat(result.state()).isEqualTo(
                TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
        assertThat(result.reasonCode()).isEqualTo("TENANT_APP_ADOPTION_REQUIRED");
        assertThat(result.featureKey()).isEqualTo("HRIS_HRM_APPOINTMENT_WORKBENCH");
    }

    private void assertCatalogOnly(JdbcTemplate jdbc, int policies, int routes) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_activation_policies",
                Integer.class)).isEqualTo(policies);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE baseline_state = 'OFF' AND product_key = 'hcm'
                   AND app_resource_key = 'APP.HCM' AND minimum_bundle_version >= 50
                """, Integer.class)).isEqualTo(policies);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_bindings",
                Integer.class)).isEqualTo(routes);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_product_feature_route_owners",
                Integer.class)).isEqualTo(routes);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_product_feature_activation_changes",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM com_tenant_feature_enrollment_changes",
                Integer.class)).isZero();
    }

    private void enableEphemeralHcmAdoption(JdbcTemplate jdbc, Long tenantId) {
        jdbc.update("""
                INSERT INTO com_tenant_app_installations (
                    tenant_id, product_key, app_resource_key, installation_kind,
                    lifecycle_state, external_executor_state, justification,
                    requested_by, submitted_at, approved_by, approved_at,
                    activated_by, activated_at, activation_receipt_id, version)
                VALUES (?, 'hcm', 'APP.HCM', 'INTERNAL_AUTH_CONTROLLED',
                        'ENABLED', 'NOT_REQUIRED',
                        'Enable an isolated test-only HCM adoption prerequisite.',
                        7001, ?, 7002, ?, 7003, ?, ?, 3)
                """, tenantId, Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW), UUID.randomUUID());
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
