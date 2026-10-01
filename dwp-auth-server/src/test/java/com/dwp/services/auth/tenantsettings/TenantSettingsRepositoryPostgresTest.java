package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private static UUID legacyReceiptId;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway preCanonical = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("231")
                .cleanDisabled(false)
                .load();
        preCanonical.clean();
        preCanonical.migrate();
        jdbc = new JdbcTemplate(dataSource);
        legacyReceiptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_sso_test_login_receipts (
                    test_login_job_id, tenant_id, provider_key, requested_by,
                    idempotency_key, justification, lifecycle_state,
                    internal_prerequisite_state, external_probe_state,
                    blocking_reasons, execution_boundary, requested_at,
                    completed_at, receipt_sha256, correlation_id)
                VALUES (?, 1, NULL, 1, ?,
                        'Legacy application-hashed receipt retained during repair.',
                        'BLOCKED', 'NOT_REQUIRED', 'NOT_REQUIRED',
                        '["SSO_LOGIN_NOT_ENABLED"]'::jsonb,
                        'UNCONNECTED_EXTERNAL_IDP_EXECUTOR', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, ?, 'legacy-canonical-repair')
                """, legacyReceiptId, UUID.randomUUID(), "9".repeat(64));
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();
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
    void boundsChangeHistoryWhileKeepingTheSingleOpenWorkflowFirst() {
        Instant now = Instant.parse("2026-09-18T08:00:00Z");
        TenantSettingsRepository.PolicyState policy = repository.currentPolicy(1L);
        TenantSettingsDtos.Impact impact = new TenantSettingsDtos.Impact(
                "ESTIMATED", 1L, "INTERNAL_AUTH_DIRECTORY_ACTIVE_IDENTITIES",
                now, List.of("EXTERNAL_IDP_POPULATION_NOT_PROBED"));
        TenantSettingsDtos.ChangeSet terminal = repository.insertChangeSet(
                1L, mapper.valueToTree(policy), mapper.valueToTree(policy),
                "c".repeat(64), "d".repeat(64), impact,
                "Create a terminal history row for bounded ordering.", 1L);
        TenantSettingsDtos.ChangeSet submitted = repository.submit(
                1L, terminal.changeSetId(), terminal.version(), 1L, now);
        repository.decide(
                1L, terminal.changeSetId(), submitted.version(), "REJECTED",
                "Reject the historical ordering fixture.", 2L, now.plusSeconds(1));
        TenantSettingsDtos.ChangeSet open = repository.insertChangeSet(
                1L, mapper.valueToTree(policy), mapper.valueToTree(policy),
                "e".repeat(64), "f".repeat(64), impact,
                "Keep the actionable workflow visible before history.", 1L);
        try {
            assertThat(repository.listChangeSets(1L, 1))
                    .extracting(TenantSettingsDtos.ChangeSet::changeSetId)
                    .containsExactly(open.changeSetId());
        } finally {
            jdbc.update(
                    "DELETE FROM com_tenant_setting_change_sets WHERE change_set_id IN (?, ?)",
                    terminal.changeSetId(), open.changeSetId());
        }
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
                "UNCONNECTED_EXTERNAL_IDP_EXECUTOR", now, now,
                "sso-test-login-postgres");
        TenantSettingsRepository.SsoReceiptWrite replay = repository.recordSsoTestLoginReceipt(
                1L, UUID.randomUUID(), "entra", 1L, idempotencyKey,
                "Verify the configured tenant SSO provider before cutover.",
                "UNAVAILABLE", "READY_FOR_EXTERNAL_PROBE", "UNAVAILABLE",
                List.of("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED"),
                "UNCONNECTED_EXTERNAL_IDP_EXECUTOR", now, now,
                "sso-test-login-postgres-replay");

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.receipt().testLoginJobId()).isEqualTo(firstJobId);
        assertThat(replay.receipt().receiptSha256()).isEqualTo(first.receipt().receiptSha256());
        assertThat(first.receipt().tenantId()).isEqualTo(1L);
        assertThat(first.receipt().idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(first.receipt().receiptSha256())
                .isEqualTo(sha256(first.receipt().receiptPayloadCanonical()));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_sso_test_login_receipts
                   SET lifecycle_state = 'SUCCEEDED'
                 WHERE test_login_job_id = ?
                """, firstJobId))
                .hasMessageContaining("SSO test-login receipts are immutable");
    }

    @Test
    void onlyReplaysAnIdempotencyKeyForTheSameNormalizedCommandMeaning() {
        Instant now = Instant.parse("2026-09-29T08:30:00Z");
        UUID idempotencyKey = UUID.randomUUID();
        var service = new TenantSettingsService(
                repository, mock(IdentityAuditService.class), mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        String justification = "Verify this tenant provider with one immutable command meaning.";

        var first = service.requestSsoTestLogin(
                1L, 1L, "idempotency-first",
                new TenantSettingsDtos.SsoTestLoginCommand(
                        idempotencyKey, "  " + justification + "  "));
        var replay = service.requestSsoTestLogin(
                1L, 1L, "idempotency-replay",
                new TenantSettingsDtos.SsoTestLoginCommand(idempotencyKey, justification));

        assertThat(replay.testLoginJobId()).isEqualTo(first.testLoginJobId());
        assertThatThrownBy(() -> service.requestSsoTestLogin(
                1L, 2L, "idempotency-different-actor",
                new TenantSettingsDtos.SsoTestLoginCommand(idempotencyKey, justification)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> service.requestSsoTestLogin(
                1L, 1L, "idempotency-different-justification",
                new TenantSettingsDtos.SsoTestLoginCommand(
                        idempotencyKey,
                        "Verify a materially different tenant authentication intent.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.RESOURCE_CONFLICT));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_sso_test_login_receipts
                 WHERE tenant_id = 1 AND idempotency_key = ?
                """, Integer.class, idempotencyKey)).isEqualTo(1);
    }

    @Test
    void preservesTheLegacyApplicationHashWhenCanonicalEvidenceIsBackfilled() {
        var evidence = jdbc.queryForMap("""
                SELECT pre_canonical_receipt_sha256,
                       receipt_payload_canonical,
                       receipt_sha256
                  FROM com_sso_test_login_receipts
                 WHERE test_login_job_id = ?
                """, legacyReceiptId);

        assertThat(evidence.get("pre_canonical_receipt_sha256")).isEqualTo("9".repeat(64));
        assertThat(evidence.get("receipt_sha256")).isEqualTo(
                sha256((String) evidence.get("receipt_payload_canonical")));
    }

    @Test
    void bindsSubMicrosecondCommandsToTheExactPersistedCanonicalReceipt() throws Exception {
        Instant highPrecision = Instant.parse("2026-09-29T08:00:00.123456789Z");
        UUID idempotencyKey = UUID.randomUUID();
        var service = new TenantSettingsService(
                repository, mock(IdentityAuditService.class), mapper,
                Clock.fixed(highPrecision, ZoneOffset.UTC));

        TenantSettingsDtos.SsoTestLoginReceipt receipt = service.requestSsoTestLogin(
                1L, 1L, "sub-microsecond-correlation",
                new TenantSettingsDtos.SsoTestLoginCommand(
                        idempotencyKey,
                        "Bind the immutable receipt to persisted timestamp precision."));

        var canonical = mapper.readTree(receipt.receiptPayloadCanonical());
        long requestedAtEpochMicros = receipt.requestedAt().getEpochSecond() * 1_000_000
                + receipt.requestedAt().getNano() / 1_000;
        assertThat(receipt.requestedAt().getNano() % 1_000).isZero();
        assertThat(canonical.path("tenantId").asLong()).isEqualTo(1L);
        assertThat(canonical.path("idempotencyKey").asText())
                .isEqualTo(idempotencyKey.toString());
        assertThat(canonical.path("requestedAtEpochMicros").asLong())
                .isEqualTo(requestedAtEpochMicros);
        assertThat(receipt.receiptSha256()).isEqualTo(sha256(receipt.receiptPayloadCanonical()));
        assertThat(jdbc.queryForObject("""
                SELECT receipt_payload_canonical
                  FROM com_sso_test_login_receipts
                 WHERE test_login_job_id = ?
                """, String.class, receipt.testLoginJobId()))
                .isEqualTo(receipt.receiptPayloadCanonical());
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
        insertPendingAuditorPreset("GROUP", groupId);

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
    void excludesExpiredPresetAndWorkforceGrantsAtTheExactValidityBoundary() {
        long expiredPresetUser = insertUser("s07-expired-preset@dwp.test");
        long futurePresetUser = insertUser("s07-future-preset@dwp.test");
        UUID expiredPresetId = insertPendingAuditorPreset("USER", expiredPresetUser);
        insertPendingAuditorPreset("USER", futurePresetUser);

        long expiredGroupUser = insertUser("s07-expired-group-preset@dwp.test");
        long expiredGroupId = jdbc.queryForObject("""
                INSERT INTO com_groups (tenant_id, group_key, display_name, status)
                VALUES (1, 's07-expired-group-preset', 'S07 expired group preset', 'ACTIVE')
                RETURNING group_id
                """, Long.class);
        jdbc.update("INSERT INTO com_group_members (tenant_id, group_id, user_id) VALUES (1, ?, ?)",
                expiredGroupId, expiredGroupUser);
        UUID expiredGroupPresetId = insertPendingAuditorPreset("GROUP", expiredGroupId);
        expirePresetAtCurrentBoundary(expiredPresetId);
        expirePresetAtCurrentBoundary(expiredGroupPresetId);

        var presetGrants = repository.grants(
                1L, List.of(expiredPresetUser, futurePresetUser, expiredGroupUser));
        assertThat(presetGrants.get(expiredPresetUser))
                .noneMatch(grant -> grant.sourceType().equals("APP_PRESET"));
        assertThat(presetGrants.get(futurePresetUser))
                .anyMatch(grant -> grant.sourceType().equals("APP_PRESET"));
        assertThat(presetGrants.get(expiredGroupUser))
                .noneMatch(grant -> grant.sourceType().equals("APP_PRESET_GROUP"));

        UUID installationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_app_installations (
                    installation_id, tenant_id, product_key, app_resource_key,
                    installation_kind, lifecycle_state, external_executor_state,
                    justification, requested_by, approved_by, approved_at,
                    activated_by, activated_at, activation_receipt_id)
                VALUES (?, 1, 's07-validity', 'APP.S07_VALIDITY',
                        'INTERNAL_AUTH_CONTROLLED', 'ENABLED', 'NOT_REQUIRED',
                        'S07 workforce validity projection fixture.', 1, 2,
                        CURRENT_TIMESTAMP, 3, CURRENT_TIMESTAMP, ?)
                """, installationId, UUID.randomUUID());
        long expiredWorkforceUser = insertUser("s07-expired-workforce@dwp.test");
        long openWorkforceUser = insertUser("s07-open-workforce@dwp.test");
        long futureWorkforceUser = insertUser("s07-future-workforce@dwp.test");
        jdbc.update("""
                INSERT INTO com_tenant_app_workforce_assignments (
                    assignment_id, tenant_id, installation_id, principal_type,
                    principal_ref, lifecycle_state, source_type,
                    external_settlement_state, valid_from, valid_to,
                    justification, requested_by, approved_by, approved_at,
                    activated_by, activated_at, activation_receipt_id)
                SELECT gen_random_uuid(), 1, ?, 'USER', subject.user_id::text,
                       'ACTIVE', 'TENANT_DIRECT', 'NOT_REQUIRED',
                       CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       CASE subject.validity
                           WHEN 'BOUNDARY' THEN CURRENT_TIMESTAMP
                           WHEN 'FUTURE' THEN CURRENT_TIMESTAMP + INTERVAL '1 day'
                           ELSE NULL
                       END,
                       'S07 workforce validity projection fixture.', 1, 2,
                       CURRENT_TIMESTAMP, 3, CURRENT_TIMESTAMP, gen_random_uuid()
                  FROM (VALUES (?::bigint, 'BOUNDARY'), (?::bigint, 'OPEN'),
                               (?::bigint, 'FUTURE')) subject(user_id, validity)
                """, installationId, expiredWorkforceUser, openWorkforceUser,
                futureWorkforceUser);

        var workforceGrants = repository.grants(1L, List.of(
                expiredWorkforceUser, openWorkforceUser, futureWorkforceUser));
        assertThat(workforceGrants.get(expiredWorkforceUser))
                .noneMatch(grant -> grant.sourceType().equals("TENANT_APP_ASSIGNMENT"));
        assertThat(workforceGrants.get(openWorkforceUser))
                .anyMatch(grant -> grant.sourceType().equals("TENANT_APP_ASSIGNMENT"));
        assertThat(workforceGrants.get(futureWorkforceUser))
                .anyMatch(grant -> grant.sourceType().equals("TENANT_APP_ASSIGNMENT"));
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

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID insertPendingAuditorPreset(String principalType, long principalId) {
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
                    VALUES (?, 1, ?, ?, 'APP_ACCESS_REVIEWER', ?, 'MANUAL',
                            'PENDING_APPROVAL', CURRENT_TIMESTAMP + INTERVAL '30 days',
                            CURRENT_TIMESTAMP + INTERVAL '20 days',
                            'S07 app preset projection validity fixture.')
                    """, responsibilityId, principalType, Long.toString(principalId),
                    resourceSetId);
            jdbc.update("""
                    INSERT INTO com_admin_app_preset_assignments (
                        app_preset_assignment_id, tenant_id, preset_code,
                        preset_catalog_version, principal_type, principal_ref,
                        resource_set_id, responsibility_assignment_id,
                        assignment_source, request_channel, lifecycle_state,
                        valid_to, review_due_at, justification, requested_by)
                    SELECT ?, 1, preset_code, version, ?, ?, ?, ?, 'MANUAL',
                           'GOVERNANCE', 'PENDING_APPROVAL',
                           CURRENT_TIMESTAMP + INTERVAL '30 days',
                           CURRENT_TIMESTAMP + INTERVAL '20 days',
                           'S07 app preset projection validity fixture.', 1
                      FROM sys_admin_app_preset_catalog
                     WHERE preset_code = 'APPROVAL_AUDITOR'
                    """, aggregateId, principalType, Long.toString(principalId),
                    resourceSetId, responsibilityId);
            jdbc.update("""
                    INSERT INTO com_admin_scoped_duty_assignments (
                        scoped_duty_assignment_id, tenant_id, principal_type, principal_ref,
                        duty_code, resource_set_id, responsibility_assignment_id,
                        app_preset_assignment_id, assignment_source, lifecycle_state,
                        valid_to, review_due_at, justification, requested_by)
                    VALUES (?, 1, ?, ?, 'APPROVAL_OPERATIONS_AUDIT', ?, ?, ?,
                            'MANUAL', 'PENDING_APPROVAL',
                            CURRENT_TIMESTAMP + INTERVAL '30 days',
                            CURRENT_TIMESTAMP + INTERVAL '20 days',
                            'S07 app preset projection validity fixture.', 1)
                    """, UUID.randomUUID(), principalType, Long.toString(principalId),
                    resourceSetId, responsibilityId, aggregateId);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
        return aggregateId;
    }

    private void expirePresetAtCurrentBoundary(UUID presetId) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("""
                    UPDATE com_admin_role_assignments responsibility
                       SET review_due_at = CURRENT_TIMESTAMP - INTERVAL '1 day',
                           valid_to = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                      FROM com_admin_app_preset_assignments preset
                     WHERE preset.app_preset_assignment_id = ?
                       AND responsibility.admin_role_assignment_id =
                           preset.responsibility_assignment_id
                    """, presetId);
            jdbc.update("""
                    UPDATE com_admin_scoped_duty_assignments
                       SET review_due_at = CURRENT_TIMESTAMP - INTERVAL '1 day',
                           valid_to = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                     WHERE app_preset_assignment_id = ?
                    """, presetId);
            jdbc.update("""
                    UPDATE com_admin_app_preset_assignments
                       SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                           review_due_at = CURRENT_TIMESTAMP - INTERVAL '1 day',
                           valid_to = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                     WHERE app_preset_assignment_id = ?
                    """, presetId);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }
}
