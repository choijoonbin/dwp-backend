package com.dwp.services.people.hr;

import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class HrMailProposalOutcomePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static HrMailProposalOutcomeOutboxRepository repository;
    private static HrMailProposalExecutionRepository executions;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
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
        repository = new HrMailProposalOutcomeOutboxRepository(jdbc);
        executions = new HrMailProposalExecutionRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clearOutbox() {
        jdbc.update("TRUNCATE TABLE abs_mail_proposal_execution_receipts");
        jdbc.update("TRUNCATE TABLE abs_mail_proposal_outcome_outbox");
    }

    @Test
    void concurrentOwnerClaimsSerializeUntilTheWinnerCommitsAndReuseItsReceipt()
            throws Exception {
        HrMailProposalBinding binding = binding();
        ReceiptFixture fixture = receiptFixture();
        HrDtos.CreateLeaveRequest request = fixture.request();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> winner = executor.submit(() -> transactions.execute(status -> {
                var claim = executions.claim(fixture.tenantId(), 17L, binding, request);
                assertThat(claim.status())
                        .isEqualTo(HrMailProposalExecutionRepository.ClaimStatus.ACQUIRED);
                claimed.countDown();
                await(allowCommit);
                UUID leaveRequestId = insertLeaveRequest(fixture);
                executions.complete(
                        fixture.tenantId(), 17L, binding,
                        claim.requestFingerprint(), leaveRequestId);
                return leaveRequestId;
            }));
            assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<HrMailProposalExecutionRepository.Claim> duplicate = executor.submit(
                    () -> transactions.execute(status ->
                            executions.claim(
                                    fixture.tenantId(), 17L, binding, request)));

            assertThatThrownBy(() -> duplicate.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowCommit.countDown();

            UUID result = winner.get(10, TimeUnit.SECONDS);
            assertThat(duplicate.get(10, TimeUnit.SECONDS)).satisfies(claim -> {
                assertThat(claim.status())
                        .isEqualTo(HrMailProposalExecutionRepository.ClaimStatus.COMPLETED);
                assertThat(claim.leaveRequestId()).isEqualTo(result);
                assertThat(claim.resultRef()).isEqualTo("hr-leave-request:" + result);
            });
        }
    }

    @Test
    void rolledBackWinnerLetsTheWaitingRequestAcquireWithoutReleasingItsClaim()
            throws Exception {
        HrMailProposalBinding binding = binding();
        ReceiptFixture fixture = receiptFixture();
        HrDtos.CreateLeaveRequest request = fixture.request();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch allowRollback = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> winner = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(executions.claim(
                        fixture.tenantId(), 17L, binding, request).status())
                        .isEqualTo(HrMailProposalExecutionRepository.ClaimStatus.ACQUIRED);
                claimed.countDown();
                await(allowRollback);
                status.setRollbackOnly();
            }));
            assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<HrMailProposalExecutionRepository.Claim> successor = executor.submit(
                    () -> transactions.execute(status ->
                            executions.claim(
                                    fixture.tenantId(), 17L, binding, request)));

            assertThatThrownBy(() -> successor.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowRollback.countDown();
            winner.get(10, TimeUnit.SECONDS);

            assertThat(successor.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo(HrMailProposalExecutionRepository.ClaimStatus.ACQUIRED);
        }
    }

    @Test
    void committedReceiptMakesLostResponseRetryIdempotentAndRejectsFingerprintDrift() {
        HrMailProposalBinding binding = binding();
        ReceiptFixture fixture = receiptFixture();
        HrDtos.CreateLeaveRequest request = fixture.request();
        UUID leaveRequestId = transactions.execute(status -> {
            var claim = executions.claim(
                    fixture.tenantId(), 17L, binding, request);
            UUID created = insertLeaveRequest(fixture);
            executions.complete(
                    fixture.tenantId(), 17L, binding,
                    claim.requestFingerprint(), created);
            return created;
        });

        var replay = transactions.execute(status ->
                executions.claim(fixture.tenantId(), 17L, binding, request));
        assertThat(replay.status())
                .isEqualTo(HrMailProposalExecutionRepository.ClaimStatus.COMPLETED);
        assertThat(replay.leaveRequestId()).isEqualTo(leaveRequestId);

        HrDtos.CreateLeaveRequest drifted = new HrDtos.CreateLeaveRequest(
                request.planId(), request.startAt(), request.endAt(),
                request.requestedMinutes(), "Changed after the first execution");
        assertThatThrownBy(() -> transactions.execute(status ->
                executions.claim(fixture.tenantId(), 17L, binding, drifted)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getMessage()).contains("different owner request"));
    }

    @Test
    void enqueueJoinsTheOwnerTransactionAndClaimUsesDurableRetryState() {
        HrMailProposalBinding rolledBack = binding();
        transactions.executeWithoutResult(status -> {
            repository.enqueue(
                    3L, 17L, rolledBack, UUID.randomUUID(),
                    "hr-leave-request:" + UUID.randomUUID(), "corr-rollback");
            status.setRollbackOnly();
        });
        assertThat(count(rolledBack.proposalId())).isZero();

        HrMailProposalBinding committed = binding();
        UUID leaveRequestId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> repository.enqueue(
                3L, 17L, committed, leaveRequestId,
                "hr-leave-request:" + leaveRequestId, "corr-commit"));

        var firstClaim = repository.claim(10);
        assertThat(firstClaim).singleElement().satisfies(outcome -> {
            assertThat(outcome.proposalId()).isEqualTo(committed.proposalId());
            assertThat(outcome.commandId()).isEqualTo(committed.commandId());
            assertThat(outcome.proposalVersion()).isEqualTo(committed.proposalVersion());
            assertThat(outcome.resultRef()).isEqualTo("hr-leave-request:" + leaveRequestId);
            assertThat(outcome.attemptCount()).isEqualTo(1);
        });

        var outcome = firstClaim.get(0);
        repository.markFailed(
                outcome.outcomeId(), outcome.attemptCount(), 3, true, "temporary failure");
        assertThat(jdbc.queryForObject("""
                SELECT published_at IS NULL AND last_error = 'temporary failure'
                  FROM abs_mail_proposal_outcome_outbox WHERE outcome_id = ?
                """, Boolean.class, outcome.outcomeId())).isTrue();

        jdbc.update("""
                UPDATE abs_mail_proposal_outcome_outbox
                   SET next_attempt_at = CURRENT_TIMESTAMP
                 WHERE outcome_id = ?
                """, outcome.outcomeId());
        var retry = repository.claim(10).get(0);
        assertThat(retry.attemptCount()).isEqualTo(2);
        assertThat(retry.proposalId()).isEqualTo(outcome.proposalId());
        assertThat(retry.commandId()).isEqualTo(outcome.commandId());
        assertThat(retry.resultRef()).isEqualTo(outcome.resultRef());

        repository.markPublished(retry.outcomeId());
        assertThat(repository.claim(10)).isEmpty();
    }

    @Test
    void cancellationRaceAfterPreflightLeavesTerminalReconciliationEvidence() {
        HrMailProposalBinding binding = binding();
        UUID leaveRequestId = UUID.randomUUID();
        repository.enqueue(
                9L, 37L, binding, leaveRequestId,
                "hr-leave-request:" + leaveRequestId, "corr-cancel-race");
        HrMailProposalOutcomeClient client = mock(HrMailProposalOutcomeClient.class);
        doThrow(new HrMailProposalOutcomeClient.DeliveryException(
                false, "Platform Mail proposal outcome delivery returned HTTP 409", null))
                .when(client).record(any());
        HrMailProposalOutcomeWorker worker =
                new HrMailProposalOutcomeWorker(repository, client, 10, 3);

        worker.publishPending();

        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, terminal_at IS NOT NULL AS terminal,
                       last_error, attempt_count
                  FROM abs_mail_proposal_outcome_outbox
                 WHERE proposal_id = ?
                """, binding.proposalId()))
                .containsEntry("delivery_state", "UNKNOWN_RECONCILE")
                .containsEntry("terminal", true)
                .containsEntry("last_error",
                        "Platform Mail proposal outcome delivery returned HTTP 409")
                .containsEntry("attempt_count", 1);
        assertThat(repository.claim(10)).isEmpty();
    }

    @Test
    void exhaustedTransientDeliveryAlsoBecomesTerminalEvidence() {
        HrMailProposalBinding binding = binding();
        UUID leaveRequestId = UUID.randomUUID();
        repository.enqueue(
                11L, 41L, binding, leaveRequestId,
                "hr-leave-request:" + leaveRequestId, "corr-network");
        var claimed = repository.claim(10).get(0);

        repository.markFailed(
                claimed.outcomeId(), claimed.attemptCount(), 1, true,
                "Platform connection timed out");

        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, terminal_at IS NOT NULL AS terminal,
                       last_error
                  FROM abs_mail_proposal_outcome_outbox
                 WHERE outcome_id = ?
                """, claimed.outcomeId()))
                .containsEntry("delivery_state", "UNKNOWN_RECONCILE")
                .containsEntry("terminal", true)
                .containsEntry("last_error", "Platform connection timed out");
        assertThat(repository.claim(10)).isEmpty();
    }

    @Test
    void oneMailProposalCannotCreateTwoOwnerOutcomeRows() {
        HrMailProposalBinding binding = binding();
        UUID firstRequest = UUID.randomUUID();
        repository.enqueue(
                7L, 23L, binding, firstRequest,
                "hr-leave-request:" + firstRequest, "corr-first");

        assertThatThrownBy(() -> repository.enqueue(
                7L, 23L, binding, UUID.randomUUID(),
                "hr-leave-request:" + UUID.randomUUID(), "corr-second"))
                .isInstanceOf(BaseException.class);
        assertThat(count(binding.proposalId())).isEqualTo(1);
    }

    private static int count(UUID proposalId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM abs_mail_proposal_outcome_outbox WHERE proposal_id = ?
                """, Integer.class, proposalId);
    }

    private HrMailProposalBinding binding() {
        return new HrMailProposalBinding(UUID.randomUUID(), UUID.randomUUID(), 4L);
    }

    private ReceiptFixture receiptFixture() {
        Instant start = Instant.parse("2037-10-05T00:00:00Z");
        return jdbc.query("""
                SELECT balance.tenant_id, balance.worker_id, plan.public_id
                  FROM abs_leave_balances balance
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = balance.tenant_id
                   AND plan.leave_plan_id = balance.leave_plan_id
                 ORDER BY balance.tenant_id, balance.worker_id, plan.leave_plan_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException(
                    "HR leave balance fixture is missing.");
            UUID planId = result.getObject("public_id", UUID.class);
            return new ReceiptFixture(
                    result.getLong("tenant_id"),
                    result.getLong("worker_id"),
                    new HrDtos.CreateLeaveRequest(
                            planId, start, start.plusSeconds(28_800),
                            480, "Mail proposal leave"));
        });
    }

    private UUID insertLeaveRequest(ReceiptFixture fixture) {
        return jdbc.queryForObject("""
                INSERT INTO abs_leave_requests (
                    tenant_id, worker_id, leave_plan_id, start_at, end_at,
                    requested_minutes, reason, status, created_by, updated_by)
                SELECT ?, ?, plan.leave_plan_id,
                       TIMESTAMPTZ '2037-10-05 00:00:00+00',
                       TIMESTAMPTZ '2037-10-05 08:00:00+00',
                       480, 'Mail proposal receipt fixture', 'DRAFT', 17, 17
                  FROM abs_leave_plans plan
                 WHERE plan.tenant_id = ? AND plan.public_id = ?
                RETURNING public_id
                """, UUID.class,
                fixture.tenantId(), fixture.workerId(),
                fixture.tenantId(), fixture.request().planId());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the concurrent receipt test.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the receipt test.", exception);
        }
    }

    private record ReceiptFixture(
            long tenantId,
            long workerId,
            HrDtos.CreateLeaveRequest request) {
    }
}
