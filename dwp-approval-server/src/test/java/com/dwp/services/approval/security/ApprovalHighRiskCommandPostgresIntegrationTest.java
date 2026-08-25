package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.support.PilotAuthorizationFixtureAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Production-faithful transactional acceptance tests for the Approval HIGH recovery command. */
@Testcontainers(disabledWithoutDocker = true)
class ApprovalHighRiskCommandPostgresIntegrationTest {

    private static final String ROUTE_KEY =
            "route.approvals.admin.operations.retry.action";
    private static final String SERVICE_PATH =
            "/v1/admin/operations/events/00000000-0000-0000-0000-000000000004/retry";
    private static final String PUBLIC_PATH =
            "/api/approvals/v1/admin/operations/events/"
                    + "00000000-0000-0000-0000-000000000004/retry";
    private static final UUID OUTBOX_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000004");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ApprovalPilotPepRegistry registry;
    private ApprovalHighRiskCommandGuard guard;
    private ApprovalCommandRepository commands;
    private PilotAuthorizationFixtureAdapter.ApprovalPepFixture fixture;
    private PilotAuthorizationFixtureAdapter.ApprovalStepUpFixture challenge;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        registry = new ApprovalPilotPepRegistry(objectMapper);
        fixture = new PilotAuthorizationFixtureAdapter().project("PS-A004");
        challenge = fixture.stepUpChallenge();
        ApprovalStepUpVerifier verifier = new ApprovalStepUpVerifier(
                objectMapper,
                Clock.fixed(fixture.fixedClock(), ZoneOffset.UTC),
                publicKey(challenge.verification().publicKeyPem()),
                challenge.verification().issuer(),
                challenge.verification().audience(),
                challenge.verification().keyId(),
                challenge.verification().requiredAcr(),
                600,
                900);
        ApprovalStepUpReplayRepository replay =
                new ApprovalStepUpReplayRepository(namedJdbc, objectMapper);
        ApprovalOwnerPredicateEvaluator owner = new ApprovalOwnerPredicateEvaluator(
                namedJdbc, mock(ApprovalIdentityDirectory.class));
        guard = new ApprovalHighRiskCommandGuard(verifier, replay, owner);
        commands = new ApprovalCommandRepository(namedJdbc, objectMapper);
        jdbc.update("INSERT INTO apr_tenants (tenant_id) VALUES (42)");
    }

    @AfterEach
    void clearContexts() {
        ApprovalManagementScopeContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        ApprovalDecisionRevisionContext.clear();
    }

    @Test
    void assignedRecoveryMutationReplayAndReceiptCommitAtomicallyAndRetryIsIdempotent() {
        seedOutbox(2, 100, 300);

        CommandRun first = execute(2, challenge.compactToken(), null, null);

        assertThat(first.priorResult()).isFalse();
        assertThat(outbox()).isEqualTo(new OutboxState("PENDING", 3, 1, 17L));
        assertThat(rowCount("apr_step_up_replay_ledger")).isEqualTo(1);
        assertThat(rowCount("apr_high_risk_idempotency_ledger")).isEqualTo(1);
        assertThat(transactionIds()).containsOnlyKeys("outbox", "replay", "idempotency");
        assertThat(Set.copyOf(transactionIds().values())).hasSize(1);

        String committedOutboxTransaction = transactionIds().get("outbox");
        CommandRun retry = execute(2, challenge.compactToken(), null, null);

        assertThat(retry.priorResult()).isTrue();
        assertThat(retry.receipt()).isEqualTo(
                new ApprovalStepUpReplayRepository.CommandReceipt(
                        1, "OUTBOX_EVENT", OUTBOX_ID.toString(), 2, "COMMITTED"));
        assertThat(outbox()).isEqualTo(new OutboxState("PENDING", 3, 1, 17L));
        assertThat(rowCount("apr_step_up_replay_ledger")).isEqualTo(1);
        assertThat(rowCount("apr_high_risk_idempotency_ledger")).isEqualTo(1);
        assertThat(transactionIds().get("outbox")).isEqualTo(committedOutboxTransaction);
    }

    @Test
    void originatorAndAssignedAuditorAreDeniedBeforeMutationAndAllGuardRowsRollBack() {
        seedOutbox(2, 17, 300);

        assertDeniedWithoutSideEffects(ErrorCode.SOD_CONFLICT);

        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET event_originator_user_id = 100, assigned_auditor_user_id = 17
                 WHERE outbox_id = ?
                """, OUTBOX_ID);

        assertDeniedWithoutSideEffects(ErrorCode.SOD_CONFLICT);
    }

    @Test
    void invalidSignedTokenAndChangedOwnerVersionCannotMutateOrLeaveGuardState() {
        seedOutbox(2, 100, 300);
        String token = challenge.compactToken();
        int signatureIndex = token.lastIndexOf('.') + 5;
        char original = token.charAt(signatureIndex);
        String invalidSignature = token.substring(0, signatureIndex)
                + (original == 'A' ? 'B' : 'A')
                + token.substring(signatureIndex + 1);

        assertThatThrownBy(() -> execute(2, invalidSignature, null, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
        assertNoMutationOrLedgerRows(2);

        jdbc.update("UPDATE apr_integration_outbox SET version = 3 WHERE outbox_id = ?", OUTBOX_ID);

        assertThatThrownBy(() -> execute(2, challenge.compactToken(), null, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertNoMutationOrLedgerRows(3);
    }

    @Test
    void challengeScopeAndSelectedOwnerScopeMismatchFailsBeforeReplayOrMutation() {
        seedOutbox(2, 100, 300);

        assertThatThrownBy(() -> transaction.execute(status -> {
            installAuthority();
            ApprovalManagementScopeContext.set("opaque-scope-b", "RS_APPROVALS");
            return guard.begin(
                    actor(),
                    challenge.capabilityContractKey(),
                    challenge.targetType(),
                    OUTBOX_ID,
                    2,
                    PUBLIC_PATH,
                    Map.of(),
                    ApprovalStepUpHeaders.of(
                            challenge.compactToken(),
                            challenge.idempotencyKey(),
                            challenge.decisionRevision(),
                            2L));
        }))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
        assertNoMutationOrLedgerRows(2);
    }

    @Test
    void missingAssignedPartyAndLegacyEvidenceFailUnavailableWithoutAnySideEffect() {
        seedUnrecoverableOutbox("PENDING", null, null, null, false);

        assertAuthorityUnavailableWithoutSideEffects();

        jdbc.update("DELETE FROM apr_integration_outbox WHERE outbox_id = ?", OUTBOX_ID);
        seedUnrecoverableOutbox(
                "LEGACY_UNASSIGNED", 300L, "RS_APPROVALS", null, true);

        assertAuthorityUnavailableWithoutSideEffects();
    }

    @Test
    void concurrentIdenticalCommandsProduceOneMutationAndOneSafePriorReceipt() throws Exception {
        seedOutbox(2, 100, 300);
        CountDownLatch firstGuarded = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CommandRun> first = executor.submit(() ->
                    execute(2, challenge.compactToken(), firstGuarded, releaseFirst));
            assertThat(firstGuarded.await(5, TimeUnit.SECONDS)).isTrue();
            Future<CommandRun> second = executor.submit(() -> {
                secondStarted.countDown();
                return execute(2, challenge.compactToken(), null, null);
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).priorResult()).isFalse();
            CommandRun loser = second.get(10, TimeUnit.SECONDS);
            assertThat(loser.priorResult()).isTrue();
            assertThat(loser.receipt()).isNotNull();
        }

        assertThat(outbox()).isEqualTo(new OutboxState("PENDING", 3, 1, 17L));
        assertThat(rowCount("apr_step_up_replay_ledger")).isEqualTo(1);
        assertThat(rowCount("apr_high_risk_idempotency_ledger")).isEqualTo(1);
    }

    @Test
    void legacyRetryCanRecoverTheSameDeliveryAcrossConsecutiveFailureCycles() {
        seedOutbox(2, 100, 300);

        executeLegacy();
        assertThat(outbox()).isEqualTo(new OutboxState("PENDING", 3, 1, 17L));

        markDeliveryFailed();
        executeLegacy();

        assertThat(outbox()).isEqualTo(new OutboxState("PENDING", 4, 2, 17L));
        assertThat(rowCount("apr_step_up_replay_ledger")).isZero();
        assertThat(rowCount("apr_high_risk_idempotency_ledger")).isZero();
    }

    @Test
    void legacyRetryRemainsAvailableAfterGovernedModeRollsBack() {
        seedOutbox(2, 100, 300);

        execute(2, challenge.compactToken(), null, null);
        assertThat(outbox()).isEqualTo(new OutboxState("PENDING", 3, 1, 17L));

        markDeliveryFailed();
        executeLegacy();

        assertThat(outbox()).isEqualTo(new OutboxState("PENDING", 4, 2, 17L));
        assertThat(rowCount("apr_step_up_replay_ledger")).isEqualTo(1);
        assertThat(rowCount("apr_high_risk_idempotency_ledger")).isEqualTo(1);
    }

    private CommandRun execute(
            long expectedVersion,
            String compactToken,
            CountDownLatch guarded,
            CountDownLatch release) {
        return transaction.execute(status -> {
            installAuthority();
            try {
                ApprovalRequestContext.Actor actor = actor();
                ApprovalHighRiskCommandGuard.Permit permit = guard.begin(
                        actor,
                        challenge.capabilityContractKey(),
                        challenge.targetType(),
                        OUTBOX_ID,
                        expectedVersion,
                        PUBLIC_PATH,
                        Map.of(),
                        ApprovalStepUpHeaders.of(
                                compactToken,
                                challenge.idempotencyKey(),
                                challenge.decisionRevision(),
                                expectedVersion));
                if (guarded != null) guarded.countDown();
                if (release != null) await(release);
                if (!permit.priorResult()) {
                    commands.retryIntegrationDelivery(actor, OUTBOX_ID, expectedVersion);
                    guard.complete(permit);
                }
                return new CommandRun(
                        permit.priorResult(),
                        permit.reservation() == null ? null : permit.reservation().receipt());
            } finally {
                ApprovalManagementScopeContext.clear();
                ApprovalPilotAuthorizationContext.clear();
                ApprovalDecisionRevisionContext.clear();
            }
        });
    }

    private void executeLegacy() {
        transaction.executeWithoutResult(status ->
                commands.retryIntegrationDelivery(actor(), OUTBOX_ID));
    }

    private void markDeliveryFailed() {
        assertThat(jdbc.update("""
                UPDATE apr_integration_outbox
                   SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = 42 AND outbox_id = ? AND status = 'PENDING'
                """, OUTBOX_ID)).isEqualTo(1);
    }

    private void installAuthority() {
        ApprovalPilotPepRegistry.Decision decision = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        "POST",
                        SERVICE_PATH,
                        Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"),
                        "APP_CONFIG_ADMIN@RS_APPROVALS," + ScopedAuthorityToken.wireToken(
                                "approvals.operations.execute",
                                "ADMIN.APPROVAL_OPERATIONS:EXECUTE",
                                "RS_APPROVALS"),
                        Set.of("APPROVAL_OPERATOR"),
                        ROUTE_KEY));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authorities()).singleElement().satisfies(authority -> {
            assertThat(authority.routeContractKey()).isEqualTo(ROUTE_KEY);
            assertThat(authority.capabilityContractKey())
                    .isEqualTo("approvals.operations.execute");
            assertThat(authority.highRisk()).isTrue();
        });
        ApprovalPilotAuthorizationContext.set(decision.authorities());
        ApprovalManagementScopeContext.set(challenge.scopeRef(), "RS_APPROVALS");
        ApprovalDecisionRevisionContext.set(
                challenge.decisionRevision(),
                OffsetDateTime.now().plusHours(1),
                "approval-management",
                challenge.scopeRef(),
                ROUTE_KEY,
                "111");
    }

    private void seedOutbox(long version, long originatorUserId, long auditorUserId) {
        jdbc.update("""
                INSERT INTO apr_integration_outbox (
                    outbox_id, event_id, tenant_id, event_type, payload,
                    payload_sha256, status, version, event_originator_user_id,
                    assigned_auditor_user_id, recovery_auditor_assignment_state,
                    recovery_auditor_resource_set_key,
                    recovery_auditor_assignment_revision,
                    recovery_auditor_assigned_at)
                VALUES (?, ?, 42, 'approval.request.submitted', '{}'::jsonb,
                        ?, 'FAILED', ?, ?, ?, 'ASSIGNED', 'RS_APPROVALS',
                        'auth-assignment-revision-1', CURRENT_TIMESTAMP)
                """, OUTBOX_ID, UUID.randomUUID(), "a".repeat(64), version,
                originatorUserId, auditorUserId);
    }

    private void seedUnrecoverableOutbox(
            String assignmentState,
            Long auditorUserId,
            String resourceSetKey,
            String assignmentRevision,
            boolean assignedAt) {
        jdbc.update("""
                INSERT INTO apr_integration_outbox (
                    outbox_id, event_id, tenant_id, event_type, payload,
                    payload_sha256, status, version, event_originator_user_id,
                    assigned_auditor_user_id, recovery_auditor_assignment_state,
                    recovery_auditor_resource_set_key,
                    recovery_auditor_assignment_revision,
                    recovery_auditor_assigned_at)
                VALUES (?, ?, 42, 'approval.request.submitted', '{}'::jsonb,
                        ?, 'FAILED', 2, 100, ?, ?, ?, ?,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, OUTBOX_ID, UUID.randomUUID(), "a".repeat(64), auditorUserId,
                assignmentState, resourceSetKey, assignmentRevision, assignedAt);
    }

    private ApprovalRequestContext.Actor actor() {
        return new ApprovalRequestContext.Actor(
                challenge.actorUserId(),
                challenge.tenantId(),
                null,
                "Recovery Operator",
                Set.of("APPROVAL_OPERATOR"),
                Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"));
    }

    private void assertDeniedWithoutSideEffects(ErrorCode expected) {
        assertThatThrownBy(() -> execute(2, challenge.compactToken(), null, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected));
        assertNoMutationOrLedgerRows(2);
    }

    private void assertAuthorityUnavailableWithoutSideEffects() {
        assertThatThrownBy(() -> execute(2, challenge.compactToken(), null, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertNoMutationOrLedgerRows(2);
    }

    private void assertNoMutationOrLedgerRows(long version) {
        assertThat(outbox()).isEqualTo(new OutboxState("FAILED", version, 0, null));
        assertThat(rowCount("apr_step_up_replay_ledger")).isZero();
        assertThat(rowCount("apr_high_risk_idempotency_ledger")).isZero();
    }

    private OutboxState outbox() {
        return jdbc.queryForObject("""
                SELECT status, version, manual_retry_count, last_retried_by
                  FROM apr_integration_outbox
                 WHERE outbox_id = ?
                """, (result, ignored) -> new OutboxState(
                result.getString("status"),
                result.getLong("version"),
                result.getInt("manual_retry_count"),
                (Long) result.getObject("last_retried_by")), OUTBOX_ID);
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Map<String, String> transactionIds() {
        return Map.of(
                "outbox", jdbc.queryForObject(
                        "SELECT xmin::text FROM apr_integration_outbox WHERE outbox_id = ?",
                        String.class, OUTBOX_ID),
                "replay", jdbc.queryForObject(
                        "SELECT xmin::text FROM apr_step_up_replay_ledger",
                        String.class),
                "idempotency", jdbc.queryForObject(
                        "SELECT xmin::text FROM apr_high_risk_idempotency_ledger",
                        String.class));
    }

    private PublicKey publicKey(String pem) {
        try {
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Fixture public key is invalid.", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record CommandRun(
            boolean priorResult,
            ApprovalStepUpReplayRepository.CommandReceipt receipt) {
    }

    private record OutboxState(
            String status,
            long version,
            int manualRetryCount,
            Long lastRetriedBy) {
    }
}
