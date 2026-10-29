package com.dwp.services.payroll.calculation;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import com.dwp.services.payroll.security.PayrollStepUpHeaders;
import com.dwp.services.payroll.preparation.PayrollPreparationPostgresFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class PayrollCalculationDurabilityPostgresTest extends PayrollMigrationPostgresFixture {

    @Test
    void realPostgresCheckpointResumeAndResultUnknownForwardRecovery() {
        UUID formulaId = UUID.fromString("00000000-0000-0000-0000-000000001108");
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                formulaId, PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource), canonical);
        Instant databaseNow = admin.queryForObject(
                "SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
        PayrollCalculationTestSupport.MutableClock clock =
                new PayrollCalculationTestSupport.MutableClock(databaseNow);
        PayrollCalculationWorker worker = new PayrollCalculationWorker(
                store, new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings()),
                canonical, new DataSourceTransactionManager(runtimeDataSource),
                clock, Duration.ofSeconds(1), 1, 100);
        PayrollCalculationService service = transactionalService(
                mock(PayrollHighRiskCommandGuard.class));

        UUID resumedSnapshot = UUID.randomUUID();
        String resumedDigest = "5".repeat(64);
        seedV49Line(
                resumedSnapshot, 9, resumedDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE", "6".repeat(64));
        UUID resumedRun = preparedRun(
                resumedSnapshot, 9, resumedDigest);
        service.calculate(
                calculator(), resumedRun, "resume-command", 2, UUID.randomUUID(),
                new ActionCommand("CALCULATE", null, null));

        assertThatThrownBy(() -> worker.execute(
                1, resumedRun, 1, "worker-a",
                PayrollCalculationWorker.FailurePoint.AFTER_FIRST_CHECKPOINT_COMMIT))
                .isInstanceOf(PayrollCalculationWorker.SimulatedWorkerCrash.class);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_workers
                 WHERE run_id = ? AND record_kind = 'CHECKPOINT'
                """, Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                        + "AND event_type <> 'PayrollRunPrepared'",
                Long.class, resumedRun)).isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(worker.execute(1, resumedRun, 1, "worker-b")).isTrue();
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                        + "AND event_type = 'PayrollCalculated'",
                Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, resumedRun)).isEqualTo(2L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_command_receipts WHERE run_id = ? "
                        + "AND command_type <> 'RUN_PREPARE'",
                Long.class, resumedRun)).isEqualTo(5L);

        UUID unknownSnapshot = UUID.randomUUID();
        String unknownDigest = "7".repeat(64);
        seedV49Line(
                unknownSnapshot, 9, unknownDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE", "8".repeat(64));
        UUID unknownRun = preparedRun(
                unknownSnapshot, 9, unknownDigest);
        service.calculate(
                calculator(), unknownRun, "unknown-command", 2, UUID.randomUUID(),
                new ActionCommand("CALCULATE", null, null));
        assertThat(worker.execute(
                1, unknownRun, 1, "worker-unknown",
                PayrollCalculationWorker.FailurePoint.BEFORE_COMPLETION_UNCERTAIN))
                .isFalse();

        PayrollCalculationStore pollStore = transactionalStore();
        assertThat(pollStore.recoveryCandidates(1, 10))
                .extracting(PayrollCalculationStore.RecoveryCandidate::runId)
                .contains(unknownRun);
        assertThat(worker.reconcile(1, unknownRun)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(DISTINCT attempt_no) FROM pay_payroll_run_attempts
                 WHERE run_id = ?
                """, Long.class, unknownRun)).isEqualTo(2L);
        assertThat(worker.execute(1, unknownRun, 2, "worker-forward")).isTrue();
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                        + "AND event_type = 'PayrollCalculated'",
                Long.class, unknownRun)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_attempts
                 WHERE run_id = ? AND attempt_no = 1
                   AND attempt_state = 'SUPERSEDED'
                """, Long.class, unknownRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, unknownRun)).isEqualTo(2L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, unknownRun)).isEqualTo(4L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, unknownRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_command_receipts WHERE run_id = ? "
                        + "AND command_type <> 'RUN_PREPARE'",
                Long.class, unknownRun)).isEqualTo(10L);
        assertThat(admin.queryForList("""
                SELECT result_line_digest FROM pay_result_lines
                 WHERE run_id = ? ORDER BY attempt_no
                """, String.class, unknownRun)).hasSize(2)
                .allMatch(admin.queryForList("""
                        SELECT result_line_digest FROM pay_result_lines
                         WHERE run_id = ? ORDER BY attempt_no
                        """, String.class, unknownRun).getFirst()::equals);
    }

    @Test
    void replacementLeaseFencesOldCheckpointAndTerminalWritesInPostgres() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        UUID runId = calculateReadyRun(transactionalService(
                mock(PayrollHighRiskCommandGuard.class)), "4", "2");
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource),
                new PayrollCalculationCanonical(
                        JsonMapper.builder().findAndAddModules().build()));
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        Instant databaseNow = admin.queryForObject(
                "SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
        PayrollCalculationModels.Lease oldLease = tx.execute(status -> store.claim(
                1, runId, 1, "old-worker", databaseNow,
                Duration.ofSeconds(1)).orElseThrow());
        PayrollCalculationModels.Lease replacement = tx.execute(status -> store.claim(
                1, runId, 1, "replacement-worker", databaseNow.plusSeconds(2),
                Duration.ofSeconds(30)).orElseThrow());
        assertThat(replacement.claimNo()).isEqualTo(oldLease.claimNo() + 1);

        PayrollCalculationModels.FrozenSnapshot snapshot = tx.execute(status ->
                store.snapshot(1, runId, 1));
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        PayrollCalculationModels.CalculationOutcome outcome =
                new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings())
                        .calculate(snapshot);
        PayrollCalculationModels.Checkpoint checkpoint =
                new PayrollCalculationModels.Checkpoint(
                        1, 2, 1, canonical.checkpointDigest(
                        runId, 1, 1, 2,
                        List.of(outcome.lines().getFirst().resultLineDigest())));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                store.appendProgress(
                        oldLease, outcome, 1, 1, checkpoint, databaseNow.plusSeconds(2))))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        PayrollCalculationModels.RunRevision current = tx.execute(status ->
                store.currentRun(1, runId).orElseThrow());
        PayrollCalculationModels.RunRevision failed = new PayrollCalculationModels.RunRevision(
                current.tenantId(), current.runId(), current.revision() + 1,
                current.runType(), current.payPeriodId(), current.sourceSnapshotId(),
                current.sourceSnapshotRevision(), current.sourcePayloadDigest(),
                current.legalEntityId(), current.payrollGroupId(),
                current.populationKind(), current.populationScopeDigest(),
                PayrollCalculationModels.RunState.FAILED, current.makerActorId(),
                current.makerSubjectPublicId(), current.latestAttemptNo(),
                current.workerCount(), current.gross(), current.net(),
                current.currency(), current.resultDigest(), current.approvalCaseId(),
                current.decisionDigest(), current.approvedByActorId(),
                current.approvedAt(), current.auditBundleDigest(),
                current.commandReceiptId(), databaseNow.plusSeconds(2));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                store.terminalFailure(
                        oldLease, current.commandReceiptId(),
                        PayrollCalculationModels.AttemptState.FAILED,
                        "STALE_WORKER", failed, "{}", databaseNow.plusSeconds(2))))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId)).isZero();
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE run_id = ? ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("READY");
    }

    @Test
    void postCommitAcknowledgementLossProbesCommittedOutcomeWithoutDuplicates() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        UUID runId = calculateReadyRun(transactionalService(
                mock(PayrollHighRiskCommandGuard.class)), "2", "1");
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        CommitThenLoseAcknowledgement manager = new CommitThenLoseAcknowledgement(
                new DataSourceTransactionManager(runtimeDataSource), admin, runId);
        PayrollCalculationWorker worker = new PayrollCalculationWorker(
                new JdbcPayrollCalculationStore(
                        new NamedParameterJdbcTemplate(runtimeDataSource), canonical),
                new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings()),
                canonical, manager, java.time.Clock.systemUTC(),
                Duration.ofSeconds(30), 1, 100);

        manager.arm();
        assertThat(worker.execute(1, runId, 1, "commit-unknown-worker")).isTrue();
        assertThat(manager.didLoseAcknowledgement()).isTrue();
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, runId)).isEqualTo(2L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                        + "AND event_type <> 'PayrollRunPrepared'",
                Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_command_receipts
                 WHERE run_id = ? AND receipt_status = 'SUCCEEDED'
                   AND command_type <> 'RUN_PREPARE'
                """, Long.class, runId)).isEqualTo(2L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_runs
                 WHERE run_id = ? AND state = 'VALIDATED'
                """, Long.class, runId)).isEqualTo(1L);
    }

    @Test
    void transactionalApprovalRollsBackReceiptRunAuditAndOutboxTogether() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationService service = transactionalService(
                fixedStepUpGuard("rollback"));
        PayrollCalculationWorker worker = jdbcWorker();
        UUID runId = calculateValidatedRun(service, worker, "9", "6");
        long expectedVersion = currentRunVersion(runId);
        long runRowsBefore = countRunRows(runId);
        admin.execute("""
                CREATE FUNCTION pay011_test_reject_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated outbox failure'; END
                $$
                """);
        admin.execute("""
                CREATE TRIGGER pay011_test_reject_outbox
                BEFORE INSERT ON pay_outbox_events
                FOR EACH ROW EXECUTE FUNCTION pay011_test_reject_outbox()
                """);
        try {
            assertThatThrownBy(() -> service.approve(
                    approver(202), runId, runId.toString(),
                    "rollback-command", expectedVersion,
                    UUID.randomUUID(),
                    new ActionCommand(
                            "APPROVE_REGULAR", null, null),
                    new PayrollStepUpHeaders(
                            "signed", "rollback-command", "decision-41",
                            expectedVersion)))
                    .isInstanceOf(RuntimeException.class);

            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_payroll_runs WHERE run_id = ?",
                    Long.class, runId)).isEqualTo(runRowsBefore);
            assertThat(admin.queryForObject("""
                    SELECT COUNT(*) FROM pay_command_receipts
                     WHERE run_id = ? AND command_type = 'RUN_APPROVE'
                    """, Long.class, runId)).isZero();
            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                            + "AND event_type = 'PayrollRunApproved'",
                    Long.class, runId)).isZero();
        } finally {
            admin.execute("DROP TRIGGER IF EXISTS pay011_test_reject_outbox "
                    + "ON pay_outbox_events");
            admin.execute("DROP FUNCTION IF EXISTS pay011_test_reject_outbox()");
        }
    }

    @Test
    void concurrentCrossRunIdempotencyHasOneWinnerAndNoLoserEffects() throws Exception {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        AtomicInteger challenge = new AtomicInteger();
        CountDownLatch guardEntered = new CountDownLatch(2);
        CountDownLatch guardRelease = new CountDownLatch(1);
        PayrollHighRiskCommandGuard guard = mock(PayrollHighRiskCommandGuard.class);
        when(guard.require(any(), any(), anyLong(), any(), any(), any()))
                .thenAnswer(ignored -> {
                    guardEntered.countDown();
                    if (!guardRelease.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("guard race did not release");
                    }
                    return stepUp("race-" + challenge.incrementAndGet());
                });
        PayrollCalculationService service = transactionalService(guard);
        PayrollCalculationWorker worker = jdbcWorker();
        UUID firstRun = calculateValidatedRun(service, worker, "8", "5");
        UUID secondRun = calculateValidatedRun(service, worker, "7", "4");
        long firstVersion = currentRunVersion(firstRun);
        long secondVersion = currentRunVersion(secondRun);
        long totalRunRowsBefore = countRunRows(firstRun) + countRunRows(secondRun);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> approvalRace(
                    service, firstRun, firstVersion));
            var second = executor.submit(() -> approvalRace(
                    service, secondRun, secondVersion));
            assertThat(guardEntered.await(10, TimeUnit.SECONDS)).isTrue();
            guardRelease.countDown();
            List<Object> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes.stream()
                    .filter(PayrollCalculationModels.PayrollCommandReceipt.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream().filter(BaseException.class::isInstance)
                    .map(BaseException.class::cast)
                    .map(BaseException::getErrorCode))
                    .containsExactly(ErrorCode.RESOURCE_CONFLICT);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(DISTINCT receipt_id) FROM pay_command_receipts "
                            + "WHERE command_type = 'RUN_APPROVE'",
                    Long.class)).isEqualTo(1L);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_payroll_runs WHERE run_id IN (?, ?)",
                    Long.class, firstRun, secondRun))
                    .isEqualTo(totalRunRowsBefore + 1);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(DISTINCT run_id) FROM pay_payroll_run_attempts "
                            + "WHERE run_id IN (?, ?)",
                    Long.class, firstRun, secondRun)).isEqualTo(2L);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_outbox_events "
                            + "WHERE event_type = 'PayrollRunApproved'",
                    Long.class)).isEqualTo(1L);
            assertThat(admin.queryForObject("""
                    SELECT COUNT(*) FROM pay_command_receipts
                     WHERE audit_type = 'PAYROLL_RUN_APPROVED'
                    """, Long.class)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void postgresGuardsSealFactsRejectMakerFinalizeAndPersistExactEvents()
            throws Exception {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationWorker worker = jdbcWorker();
        PayrollCalculationService calculate = transactionalService(
                mock(PayrollHighRiskCommandGuard.class));
        UUID runId = calculateValidatedRun(calculate, worker, "6", "3");

        PayrollCalculationService approve = transactionalService(
                fixedStepUpGuard("event-approve"));
        long validatedVersion = currentRunVersion(runId);
        approve.approve(
                approver(202), runId, runId.toString(), "event-approve",
                validatedVersion, UUID.randomUUID(),
                new ActionCommand(
                        "APPROVE_REGULAR", null, null),
                new PayrollStepUpHeaders(
                        "signed", "event-approve", "decision-41",
                        validatedVersion));

        long approvedVersion = currentRunVersion(runId);
        UUID makerFinalizeReceipt = UUID.randomUUID();
        insertHighRiskReceipt(
                makerFinalizeReceipt, runId, "maker-finalize",
                "RUN_FINALIZE", "PAY_RUN_FINALIZE", 101,
                PayrollCalculationTestSupport.SUBJECT, approvedVersion + 1,
                "maker-finalize-proof");
        assertThatThrownBy(() -> insertFinalizedRevision(
                runId, makerFinalizeReceipt, approvedVersion + 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("maker cannot approve or finalize");
        assertThat(currentRunVersion(runId)).isEqualTo(approvedVersion);

        PayrollCalculationService finalize = transactionalService(
                fixedStepUpGuard("event-finalize"));
        finalize.finalizeRun(
                finalizer(202), runId, runId.toString(), "event-finalize",
                approvedVersion, UUID.randomUUID(),
                new ActionCommand(
                        "FINALIZE_REGULAR", null, null),
                new PayrollStepUpHeaders(
                        "signed", "event-finalize", "decision-41",
                        approvedVersion));

        List<Map<String, Object>> events = admin.queryForList("""
                SELECT event_type, event_payload::text AS payload
                  FROM pay_outbox_events
                 WHERE tenant_id = 1 AND run_id = ?
                   AND event_type <> 'PayrollRunPrepared'
                 ORDER BY run_revision
                """, runId);
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly(
                        "PayrollCalculated", "PayrollRunApproved", "PayrollFinalized");
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        Set<String> envelopeKeys = Set.of(
                "specVersion", "id", "source", "type", "schemaVersion", "time",
                "tenantId", "aggregateType", "aggregateId", "aggregateSequence",
                "correlationId", "data");
        Map<String, Set<String>> dataKeys = Map.of(
                "PayrollCalculated", Set.of(
                        "runId", "attemptNo", "workerCount", "resultDigest",
                        "controlTotals"),
                "PayrollRunApproved", Set.of(
                        "runId", "runRevision", "approvalCaseId", "decisionDigest"),
                "PayrollFinalized", Set.of(
                        "runId", "runRevision", "resultDigest", "auditBundleDigest"));
        for (Map<String, Object> row : events) {
            JsonNode payload = mapper.readTree((String) row.get("payload"));
            assertThat(fieldNames(payload)).isEqualTo(envelopeKeys);
            assertThat(fieldNames(payload.path("data")))
                    .isEqualTo(dataKeys.get(row.get("event_type")));
        }

        long workerFacts = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, runId);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_worker_results
                SELECT * FROM pay_worker_results
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sealed or unfenced");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, runId)).isEqualTo(workerFacts);

        long traceFacts = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, runId);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_calculation_trace_nodes
                SELECT * FROM pay_calculation_trace_nodes
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sealed or unfenced");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, runId)).isEqualTo(traceFacts);

        long resultLines = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_result_lines
                SELECT * FROM pay_result_lines
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sealed or unfenced");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId)).isEqualTo(resultLines);

        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_payroll_run_workers
                SELECT * FROM pay_payroll_run_workers
                 WHERE tenant_id = 1 AND run_id = ?
                   AND record_kind = 'CHECKPOINT' LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("stale or out of sequence");
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_input_snapshots
                SELECT * FROM pay_input_snapshots
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("outside the open freeze boundary");
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_input_snapshot_compensation_refs
                SELECT ref.* FROM pay_input_snapshot_compensation_refs ref
                  JOIN pay_input_snapshots snapshot
                    ON snapshot.tenant_id = ref.tenant_id
                   AND snapshot.input_snapshot_internal_id =
                       ref.input_snapshot_internal_id
                 WHERE snapshot.tenant_id = 1 AND snapshot.run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("references are sealed");

        assertThatThrownBy(() -> insertIllegalAttemptTransition(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Illegal payroll attempt transition");
        assertThatThrownBy(() -> insertIllegalRunTransition(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Illegal payroll run state transition");

        assertThatThrownBy(() -> insertInvalidInitialCalculateReceipt(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sequence one");
        assertThatThrownBy(() -> insertOutboxWithExtraData(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payload is not exact");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                        + "AND event_type <> 'PayrollRunPrepared'",
                Long.class, runId)).isEqualTo(3L);
    }

    @Test
    void failedRunCanFreezeAnExactSecondAttempt() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationService service = transactionalService(
                mock(PayrollHighRiskCommandGuard.class));
        UUID runId = calculateReadyRun(service, "5", "4");
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        PayrollCalculationWorker failingWorker = new PayrollCalculationWorker(
                new JdbcPayrollCalculationStore(
                        new NamedParameterJdbcTemplate(runtimeDataSource), canonical),
                new PayrollCalculationEngine(
                        canonical, (snapshot, input) -> {
                            throw new IllegalStateException("typed evaluation unavailable");
                        }),
                canonical, new DataSourceTransactionManager(runtimeDataSource),
                java.time.Clock.systemUTC(), Duration.ofSeconds(30), 1, 100);
        assertThatThrownBy(() -> failingWorker.execute(
                1, runId, 1, "failed-attempt-worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typed evaluation unavailable");
        long failedVersion = currentRunVersion(runId);
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE run_id = ? ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("FAILED");

        service.calculate(
                calculator(), runId, "retry-after-failed", failedVersion,
                UUID.randomUUID(), new ActionCommand(
                "RETRY_CALCULATION", null, null));
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_input_snapshots
                 WHERE tenant_id = 1 AND run_id = ?
                """, Long.class, runId)).isEqualTo(2L);
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE run_id = ? ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("READY");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_attempts
                 WHERE run_id = ? AND attempt_no = 2 AND attempt_state = 'READY'
                """, Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) = 2
                       AND COUNT(DISTINCT preparation_id) = 1
                       AND COUNT(*) FILTER (
                           WHERE state_sequence = 1
                             AND preparation_state = 'PREPARING') = 1
                       AND COUNT(*) FILTER (
                           WHERE state_sequence = 2
                             AND preparation_state = 'READY') = 1
                  FROM pay_run_preparations
                 WHERE tenant_id = 1 AND run_id = ?
                """, Boolean.class, runId)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_command_receipts
                 WHERE tenant_id = 1 AND run_id = ?
                   AND command_type = 'RUN_PREPARE'
                   AND originating_action = 'PAY_RUN_PREPARE'
                   AND status_sequence = 1
                """, Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_outbox_events
                 WHERE tenant_id = 1 AND run_id = ?
                   AND event_type = 'PayrollRunPrepared'
                   AND event_payload ->> 'type' = 'PayrollRunPrepared.v1'
                """, Long.class, runId)).isEqualTo(1L);
        PayrollPreparationPostgresFixture
                .assertRejectedPreparationsLeaveNoBusinessEffects(
                        adminDataSource, runtimeDataSource, publisherDataSource,
                        runId, PayrollCalculationTestSupport.LEGAL_ENTITY,
                        PayrollCalculationTestSupport.PAY_GROUP,
                        PayrollCalculationTestSupport.SUBJECT, 101,
                        PayrollCalculationTestSupport.TARGET_DIGEST);
    }
}
