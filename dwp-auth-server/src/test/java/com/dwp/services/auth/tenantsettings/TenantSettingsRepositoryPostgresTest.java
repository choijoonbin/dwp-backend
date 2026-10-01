package com.dwp.services.auth.tenantsettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.services.auth.service.IdentityAuditService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class TenantSettingsRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static PGSimpleDataSource dataSource;
    private static TenantSettingsRepository repository;
    private static ProductCapabilityEntitlementRepository capabilityRepository;
    private static ObjectMapper mapper;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
        repository = new TenantSettingsRepository(
                jdbc, new NamedParameterJdbcTemplate(dataSource), mapper);
        capabilityRepository = new ProductCapabilityEntitlementRepository(
                jdbc, new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void persistsTheIndependentWorkflowAndPublishesTheRuntimePolicy() {
        Instant now = Instant.parse("2026-09-17T08:00:00Z");
        TenantSettingsRepository.PolicyState before = repository.currentPolicy(1L);
        TenantSettingsRepository.PolicyState proposed = new TenantSettingsRepository.PolicyState(
                before.defaultLoginType(), before.allowedLoginTypes(),
                before.localLoginEnabled(), before.ssoLoginEnabled(), before.ssoProviderKey(),
                !before.requireMfa(), before.tokenTtlSec());
        TenantSettingsDtos.ChangeSet draft = repository.insertChangeSet(
                1L, mapper.valueToTree(before), mapper.valueToTree(proposed),
                "a".repeat(64), "b".repeat(64),
                new TenantSettingsDtos.Impact(
                        "ESTIMATED", repository.activeIdentityCount(1L),
                        "INTERNAL_AUTH_DIRECTORY_ACTIVE_IDENTITIES", now,
                        List.of("EXTERNAL_IDP_POPULATION_NOT_PROBED")),
                "Require an independent review before changing MFA policy.", 1L);

        TenantSettingsDtos.ChangeSet submitted = repository.submit(
                1L, draft.changeSetId(), draft.version(), 1L, now);
        TenantSettingsDtos.ChangeSet approved = repository.decide(
                1L, draft.changeSetId(), submitted.version(), "APPROVED",
                "A second administrator reviewed the proposed policy.", 2L, now.plusSeconds(1));
        TenantSettingsDtos.ChangeSet published = repository.publish(
                1L, draft.changeSetId(), approved.version(), proposed,
                3L, now.plusSeconds(2), UUID.randomUUID());

        assertThat(published.lifecycleState()).isEqualTo("PUBLISHED");
        assertThat(published.decidedBy()).isEqualTo(2L);
        assertThat(published.publishReceiptId()).isNotNull();
        assertThat(repository.currentPolicy(1L).requireMfa()).isEqualTo(proposed.requireMfa());
    }

    @Test
    void keepsSsoTestLoginReceiptsImmutableAndIdempotent() {
        Instant now = Instant.parse("2026-09-29T08:00:00Z");
        UUID idempotencyKey = UUID.randomUUID();
        UUID firstJobId = UUID.randomUUID();
        TenantSettingsRepository.SsoReceiptWrite first = repository.recordSsoTestLoginReceipt(
                1L, firstJobId, "entra", 1L, idempotencyKey,
                "Verify the configured tenant SSO provider before cutover.",
                "UNAVAILABLE", "READY_FOR_EXTERNAL_PROBE", "UNAVAILABLE",
                List.of("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED"),
                "UNCONNECTED_EXTERNAL_IDP_EXECUTOR", now, now, "a".repeat(64),
                "sso-test-login-postgres");
        TenantSettingsRepository.SsoReceiptWrite replay = repository.recordSsoTestLoginReceipt(
                1L, UUID.randomUUID(), "entra", 1L, idempotencyKey,
                "Verify the configured tenant SSO provider before cutover.",
                "UNAVAILABLE", "READY_FOR_EXTERNAL_PROBE", "UNAVAILABLE",
                List.of("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED"),
                "UNCONNECTED_EXTERNAL_IDP_EXECUTOR", now, now, "b".repeat(64),
                "sso-test-login-postgres-replay");

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.receipt().testLoginJobId()).isEqualTo(firstJobId);
        assertThat(replay.receipt().receiptSha256()).isEqualTo("a".repeat(64));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_sso_test_login_receipts
                   SET lifecycle_state = 'SUCCEEDED'
                 WHERE test_login_job_id = ?
                """, firstJobId))
                .hasMessageContaining("SSO test-login receipts are immutable");
    }

    @Test
    void readsInternalRoleAndAppPresetCoverageWithoutExternalClaims() {
        List<TenantSettingsRepository.UserRow> users = repository.users(1L, null, 0, 20);
        assertThat(users).isNotEmpty();
        List<Long> userIds = users.stream().map(TenantSettingsRepository.UserRow::userId).toList();

        assertThat(repository.grants(1L, userIds)).containsKeys(userIds.toArray(Long[]::new));
        assertThat(repository.freshestProjectionSource(1L)).isNotNull();
        assertThat(capabilityRepository.grants(1L, userIds))
                .containsKeys(userIds.toArray(Long[]::new));
        // A tenant with no active product bundle is a valid typed NO_DATA owner state.
        capabilityRepository.freshest(1L);
    }

    @Test
    void keepsAStableSnapshotAcrossMoreThanOneRealPostgresPage() {
        jdbc.update("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                SELECT 1, 'S07 page subject ' || value,
                       's07-page-' || value || '@dwp.test', 'ACTIVE'
                  FROM generate_series(1, 101) value
                """);
        var service = new TenantSettingsService(
                repository,
                mock(IdentityAuditService.class),
                mapper,
                TenantSettingsAuthorization.testAllowAll(),
                new InternalEntitlementAdapterRegistry(List.of(
                        new CoreIdentityEntitlementAdapter(repository),
                        new ProductCapabilityEntitlementAdapter(capabilityRepository))),
                Clock.fixed(Instant.parse("2026-09-29T08:00:00Z"), ZoneOffset.UTC));

        TenantSettingsDtos.AccessProjection first = service.accessProjection(
                1L, 1L, "s07-page-", 0, 100);
        TenantSettingsDtos.AccessProjection second = service.accessProjection(
                1L, 1L, "s07-page-", 1, 100);

        assertThat(first.totalElements()).isEqualTo(101);
        assertThat(first.principals()).hasSize(100);
        assertThat(second.principals()).hasSize(1);
        assertThat(first.snapshotId()).isEqualTo(second.snapshotId());
        assertThat(first.principals()).extracting(TenantSettingsDtos.PrincipalAccess::userId)
                .doesNotContainAnyElementsOf(
                        second.principals().stream()
                                .map(TenantSettingsDtos.PrincipalAccess::userId)
                                .toList());
    }

    @Test
    void excludesEligibleAndScopedGroupRolesFromGlobalCapabilityProjection() {
        long userId = insertUser("s07-scoped-capability@dwp.test");
        long roleId = jdbc.queryForObject("""
                INSERT INTO com_roles (tenant_id, code, name, status)
                VALUES (1, 'S07_SCOPED_CAPABILITY', 'S07 scoped capability', 'ACTIVE')
                RETURNING role_id
                """, Long.class);
        long groupId = jdbc.queryForObject("""
                INSERT INTO com_groups (tenant_id, group_key, display_name, status)
                VALUES (1, 's07-scoped-capability', 'S07 scoped capability', 'ACTIVE')
                RETURNING group_id
                """, Long.class);
        jdbc.update("INSERT INTO com_group_members (tenant_id, group_id, user_id) VALUES (1, ?, ?)",
                groupId, userId);
        long assignmentId = jdbc.queryForObject("""
                INSERT INTO com_group_role_assignments (
                    tenant_id, group_id, role_id, assignment_type, scope_type,
                    lifecycle_state, justification)
                VALUES (1, ?, ?, 'ELIGIBLE', 'TENANT', 'ACTIVE',
                        'Negative projection boundary fixture.')
                RETURNING group_role_assignment_id
                """, Long.class, groupId, roleId);

        assertThat(repository.grants(1L, List.of(userId)).get(userId))
                .noneMatch(grant -> grant.entitlementKey().equals("S07_SCOPED_CAPABILITY"));

        long resourceId = jdbc.queryForObject("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (1, 'ACTION', 'ACTION.S07_GLOBAL', 'S07 global action', TRUE)
                RETURNING resource_id
                """, Long.class);
        long viewPermissionId = jdbc.queryForObject(
                "SELECT permission_id FROM com_permissions WHERE code = 'VIEW'", Long.class);
        jdbc.update("""
                INSERT INTO com_role_permissions (
                    tenant_id, role_id, resource_id, permission_id, effect)
                VALUES (1, ?, ?, ?, 'ALLOW')
                """, roleId, resourceId, viewPermissionId);
        UUID bundleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_product_authorization_bundle (
                    bundle_id, bundle_key, version, bundle_status, schema_version,
                    checksum_algorithm, checksum, owner, approved_by, approved_at, activated_at)
                VALUES (?, 's07-scope-negative', 1, 'ACTIVE', 1, 'SHA-256', ?,
                        'S07 test owner', 'S07 independent reviewer', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP)
                """, bundleId, "a".repeat(64));
        jdbc.update("""
                INSERT INTO auth_product_capability_contract (
                    bundle_id, contract_key, product_key, surface_key,
                    lifecycle_state, descriptor)
                VALUES (?, 'approvals.s07-scope-negative', 'approvals', 'admin', 'ACTIVE',
                        '{"resolvedCapabilityCode":"ACTION.S07_GLOBAL:VIEW",'
                        '"displayName":"S07 global capability","riskTier":"LOW",'
                        '"requiresProductEntitlement":false}'::jsonb)
                """, bundleId);
        jdbc.update("""
                INSERT INTO auth_product_authorization_active (
                    bundle_key, bundle_id, revision, activated_by)
                VALUES ('s07-scope-negative', ?, 1, 'S07 test activator')
                """, bundleId);

        assertThat(capabilityRepository.grants(1L, List.of(userId)).get(userId)).isEmpty();

        jdbc.update("""
                UPDATE com_group_role_assignments
                   SET assignment_type = 'ACTIVE', scope_type = 'ORG_UNIT',
                       scope_ref = 'org-s07', updated_at = CURRENT_TIMESTAMP
                 WHERE group_role_assignment_id = ?
                """, assignmentId);
        assertThat(capabilityRepository.grants(1L, List.of(userId)).get(userId)).isEmpty();

        jdbc.update("""
                UPDATE com_group_role_assignments
                   SET scope_type = 'TENANT', scope_ref = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE group_role_assignment_id = ?
                """, assignmentId);
        assertThat(capabilityRepository.grants(1L, List.of(userId)).get(userId))
                .extracting(TenantSettingsDtos.AccessGrant::entitlementKey)
                .contains("approvals.s07-scope-negative");
    }

    @Test
    void excludesInactiveGroupPresetAndMarksWorkforceBlockedByInstallation() {
        long userId = insertUser("s07-inactive-group@dwp.test");
        long groupId = jdbc.queryForObject("""
                INSERT INTO com_groups (tenant_id, group_key, display_name, status)
                VALUES (1, 's07-inactive-preset', 'S07 inactive preset', 'INACTIVE')
                RETURNING group_id
                """, Long.class);
        jdbc.update("INSERT INTO com_group_members (tenant_id, group_id, user_id) VALUES (1, ?, ?)",
                groupId, userId);
        insertPendingAuditorPreset(groupId);

        assertThat(repository.grants(1L, List.of(userId)).get(userId))
                .noneMatch(grant -> grant.sourceType().equals("APP_PRESET_GROUP"));
        jdbc.update("UPDATE com_groups SET status = 'ACTIVE' WHERE group_id = ?", groupId);
        assertThat(repository.grants(1L, List.of(userId)).get(userId))
                .anyMatch(grant -> grant.sourceType().equals("APP_PRESET_GROUP"));

        UUID installationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_app_installations (
                    installation_id, tenant_id, product_key, app_resource_key,
                    installation_kind, lifecycle_state, external_executor_state,
                    justification, requested_by)
                VALUES (?, 1, 's07-workforce', 'APP.S07_WORKFORCE',
                        'INTERNAL_AUTH_CONTROLLED', 'SUSPENDED', 'NOT_REQUIRED',
                        'Suspended installation projection boundary.', 1)
                """, installationId);
        jdbc.update("""
                INSERT INTO com_tenant_app_workforce_assignments (
                    assignment_id, tenant_id, installation_id, principal_type,
                    principal_ref, lifecycle_state, source_type,
                    external_settlement_state, justification, requested_by,
                    approved_by, approved_at, activated_by, activated_at,
                    activation_receipt_id)
                VALUES (?, 1, ?, 'USER', ?, 'ACTIVE', 'TENANT_DIRECT',
                        'NOT_REQUIRED', 'Active seat behind suspended installation.',
                        1, 2, CURRENT_TIMESTAMP, 3, CURRENT_TIMESTAMP, ?)
                """, UUID.randomUUID(), installationId, Long.toString(userId), UUID.randomUUID());

        assertThat(repository.grants(1L, List.of(userId)).get(userId))
                .filteredOn(grant -> grant.sourceType().equals("TENANT_APP_ASSIGNMENT"))
                .singleElement()
                .satisfies(grant -> assertThat(grant.lifecycleState())
                        .isEqualTo("BLOCKED_BY_INSTALLATION_SUSPENDED"));
    }

    @Test
    void storesRecoveryVerificationAsAnAttestationRatherThanExternalLoginProof() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_emergency_access_principals (
                    emergency_access_principal_id, tenant_id, user_id,
                    justification, review_due_at, lifecycle_state)
                VALUES (?, 1, 1, ?, CURRENT_TIMESTAMP + INTERVAL '90 days', 'ACTIVE')
                """, id, "Recovery owner registered for the tenant drill.");
        jdbc.update("""
                UPDATE com_emergency_access_principals
                   SET verification_status = 'VERIFIED',
                       verification_method = 'RECOVERY_DRILL_COMPLETED',
                       verification_reference = 'Internal drill ticket REC-2026-0917',
                       last_verified_at = CURRENT_TIMESTAMP,
                       last_verified_by = 2,
                       verification_due_at = CURRENT_TIMESTAMP + INTERVAL '90 days'
                 WHERE emergency_access_principal_id = ?
                """, id);

        assertThat(jdbc.queryForObject("""
                SELECT verification_method
                  FROM com_emergency_access_principals
                 WHERE emergency_access_principal_id = ?
                """, String.class, id)).isEqualTo("RECOVERY_DRILL_COMPLETED");
    }

    private long insertUser(String email) {
        return jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (1, 'S07 projection subject', ?, 'ACTIVE')
                RETURNING user_id
                """, Long.class, email);
    }

    private void insertPendingAuditorPreset(long groupId) {
        UUID resourceSetId = jdbc.queryForObject("""
                SELECT resource_set.resource_set_id
                  FROM com_admin_resource_sets resource_set
                  JOIN com_admin_resource_set_members member
                    ON member.tenant_id = resource_set.tenant_id
                   AND member.resource_set_id = resource_set.resource_set_id
                 WHERE resource_set.tenant_id = 1
                   AND member.resource_key = 'APP.APPROVALS'
                   AND member.lifecycle_state = 'ACTIVE'
                 LIMIT 1
                """, UUID.class);
        UUID responsibilityId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO com_admin_role_assignments (
                        admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                        responsibility_code, resource_set_id, assignment_source,
                        lifecycle_state, valid_to, review_due_at, justification)
                    VALUES (?, 1, 'GROUP', ?, 'APP_ACCESS_REVIEWER', ?, 'MANUAL',
                            'PENDING_APPROVAL', CURRENT_TIMESTAMP + INTERVAL '30 days',
                            CURRENT_TIMESTAMP + INTERVAL '20 days',
                            'S07 inactive group preset projection fixture.')
                    """, responsibilityId, Long.toString(groupId), resourceSetId);
            jdbc.update("""
                    INSERT INTO com_admin_app_preset_assignments (
                        app_preset_assignment_id, tenant_id, preset_code,
                        preset_catalog_version, principal_type, principal_ref,
                        resource_set_id, responsibility_assignment_id,
                        assignment_source, request_channel, lifecycle_state,
                        valid_to, review_due_at, justification, requested_by)
                    SELECT ?, 1, preset_code, version, 'GROUP', ?, ?, ?, 'MANUAL',
                           'GOVERNANCE', 'PENDING_APPROVAL',
                           CURRENT_TIMESTAMP + INTERVAL '30 days',
                           CURRENT_TIMESTAMP + INTERVAL '20 days',
                           'S07 inactive group preset projection fixture.', 1
                      FROM sys_admin_app_preset_catalog
                     WHERE preset_code = 'APPROVAL_AUDITOR'
                    """, aggregateId, Long.toString(groupId), resourceSetId, responsibilityId);
            jdbc.update("""
                    INSERT INTO com_admin_scoped_duty_assignments (
                        scoped_duty_assignment_id, tenant_id, principal_type, principal_ref,
                        duty_code, resource_set_id, responsibility_assignment_id,
                        app_preset_assignment_id, assignment_source, lifecycle_state,
                        valid_to, review_due_at, justification, requested_by)
                    VALUES (?, 1, 'GROUP', ?, 'APPROVAL_OPERATIONS_AUDIT', ?, ?, ?,
                            'MANUAL', 'PENDING_APPROVAL',
                            CURRENT_TIMESTAMP + INTERVAL '30 days',
                            CURRENT_TIMESTAMP + INTERVAL '20 days',
                            'S07 inactive group preset projection fixture.', 1)
                    """, UUID.randomUUID(), Long.toString(groupId), resourceSetId,
                    responsibilityId, aggregateId);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }
}
