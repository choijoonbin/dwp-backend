package com.dwp.services.provider.settings;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TenantOwnerProjectionRepositoryPostgresTest {

    private static final UUID FIRST_TENANT =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_TENANT =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TenantOwnerProjectionRepository repository;

    @BeforeEach
    void migrateAndSeedCrossTenantEvidence() {
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
        repository = new TenantOwnerProjectionRepository(
                new NamedParameterJdbcTemplate(dataSource));

        jdbc.update("""
                INSERT INTO prv_organizations (
                    organization_id, organization_key, display_name,
                    created_by, updated_by)
                VALUES (?, 'other-customer', 'Other customer', 1, 1)
                """, SECOND_TENANT);
        jdbc.update("""
                INSERT INTO prv_tenants (
                    provider_tenant_id, tenant_key, display_name, service_tier,
                    data_region, isolation_model, lifecycle_state, onboarding_state,
                    auth_tenant_id, organization_id, created_by, updated_by)
                VALUES (?, 'other-tenant', 'Other tenant', 'ENTERPRISE', 'local', 'POOL',
                        'ACTIVE', 'READY', 2, ?, 1, 1)
                """, SECOND_TENANT, SECOND_TENANT);
        jdbc.update("""
                INSERT INTO prv_organization_subscriptions (
                    organization_id, service_plan_id, lifecycle_state,
                    contract_reference, created_by, updated_by)
                SELECT ?, service_plan_id, 'ACTIVE', 'other-standard-contract', 1, 1
                  FROM prv_service_plans
                 WHERE plan_key = 'DWP_STANDARD' AND lifecycle_state = 'ACTIVE'
                """, SECOND_TENANT);
        jdbc.update("""
                INSERT INTO prv_tenant_entitlements (
                    provider_tenant_id, entitlement_id, lifecycle_state,
                    created_by, updated_by)
                SELECT ?, entitlement_id, 'SUSPENDED', 1, 1
                  FROM prv_entitlement_catalog
                 WHERE entitlement_key = 'core.approvals'
                """, SECOND_TENANT);
        jdbc.update("""
                INSERT INTO prv_tenant_domains (
                    tenant_domain_id, provider_tenant_id, domain_name, domain_type,
                    verification_method, verification_state, verification_token_hash,
                    verification_record_value, primary_domain,
                    verified_at, created_by, updated_by)
                VALUES
                    (?, ?, 'first.example', 'LOGIN', 'DNS_TXT', 'VERIFIED', ?,
                     'redacted-first-record', FALSE,
                     CURRENT_TIMESTAMP, 1, 1),
                    (?, ?, 'second.example', 'LOGIN', 'DNS_TXT', 'VERIFIED', ?,
                     'redacted-second-record', TRUE,
                     CURRENT_TIMESTAMP, 1, 1)
                """, UUID.randomUUID(), FIRST_TENANT, "a".repeat(64),
                UUID.randomUUID(), SECOND_TENANT, "b".repeat(64));
        seedPolicy("global.retention.test", "GLOBAL", null, "RETENTION",
                "{\"retentionDays\":90}");
        seedPolicy("asset.retention.test", "ASSET", "provider.public.prv_tenants",
                "RETENTION", "{\"retentionDays\":30}");
        seedLifecycle(FIRST_TENANT, "PURGE", "[\"global-hold-secret-ref\"]");
        seedLifecycle(SECOND_TENANT, "RETIRE", "[]");
    }

    @Test
    void queriesStayTenantBoundAndExposeOnlyGlobalPolicyEvidence() {
        assertThat(repository.domains(FIRST_TENANT))
                .extracting(TenantOwnerProjectionRepository.DomainRow::domainName)
                .containsExactly("default.local", "first.example");
        assertThat(repository.domains(FIRST_TENANT))
                .noneMatch(domain -> domain.domainName().equals("second.example"));

        assertThat(repository.latestTenantLifecycleHoldObservations(FIRST_TENANT))
                .singleElement()
                .satisfies(hold -> {
                    assertThat(hold.requestedAction()).isEqualTo("PURGE");
                    assertThat(hold.evidenceReferenceCount()).isEqualTo(1);
                });
        assertThat(repository.globalRetentionAndLegalHoldPolicies())
                .singleElement()
                .satisfies(policy -> {
                    assertThat(policy.policyType()).isEqualTo("RETENTION");
                    assertThat(policy.retentionDays()).isEqualTo(90);
                });

        assertThat(repository.currentPlan(FIRST_TENANT))
                .get()
                .satisfies(plan -> {
                    assertThat(plan.planKey()).isEqualTo("DWP_ENTERPRISE");
                    assertThat(plan.subscriptionState()).isEqualTo("ACTIVE");
                });
        assertThat(repository.productEligibility(FIRST_TENANT))
                .filteredOn(row -> row.productKey().equals("approvals"))
                .singleElement()
                .satisfies(row -> assertThat(row.eligibilityState()).isEqualTo("ELIGIBLE"));
        assertThat(repository.productEligibility(SECOND_TENANT))
                .filteredOn(row -> row.productKey().equals("approvals"))
                .singleElement()
                .satisfies(row ->
                        assertThat(row.eligibilityState())
                                .isEqualTo("TENANT_ENTITLEMENT_SUSPENDED"));
        assertThat(repository.productEligibility(SECOND_TENANT))
                .filteredOn(row -> row.productKey().equals("dwaion"))
                .singleElement()
                .satisfies(row ->
                        assertThat(row.eligibilityState())
                                .isEqualTo("NOT_ASSIGNED_TO_TENANT"));
    }

    private void seedPolicy(
            String key,
            String scopeType,
            String scopeRef,
            String policyType,
            String rule) {
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_data_policies (
                    data_policy_id, policy_key, display_name, description, policy_type,
                    scope_type, scope_ref, owner_service, created_by, updated_by)
                VALUES (?, ?, 'Redacted policy', 'Repository projection fixture', ?, ?, ?,
                        'provider-data-governance', 1, 1)
                """, policyId, key, policyType, scopeType, scopeRef);
        jdbc.update("""
                INSERT INTO prv_data_policy_revisions (
                    data_policy_revision_id, data_policy_id, revision_number,
                    lifecycle_state, policy_rule, justification, impact_hash,
                    impact_previewed_at, requested_by, published_at)
                VALUES (?, ?, 1, 'ACTIVE', CAST(? AS jsonb), 'Fixture', ?,
                        CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), policyId, rule, "a".repeat(64));
    }

    private void seedLifecycle(UUID tenantId, String action, String evidence) {
        jdbc.update("""
                INSERT INTO prv_tenant_lifecycle_requests (
                    lifecycle_request_id, provider_tenant_id, requested_action,
                    hold_evaluation_state, hold_evidence, justification, requested_by)
                VALUES (?, ?, ?, 'OWNER_VERIFICATION_REQUIRED', CAST(? AS jsonb),
                        'Fixture lifecycle request', 1)
                """, UUID.randomUUID(), tenantId, action, evidence);
    }
}
