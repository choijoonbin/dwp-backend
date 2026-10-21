package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedOriginal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.HandlerIdentity;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RetrySelection;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RunRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationEffectRequest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationItemPlan;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.ExternalEffectEvidencePort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.ReconciliationEvidence;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.TypedAutomationHandlerPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.SelectionMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Executable PostgreSQL evidence for the SYS-006 golden execution scenarios.
 *
 * <p>Every negative arm executes at the same repository boundary as production
 * and verifies that no durable attempt/checkpoint was created. The fixture uses
 * the real V333 schema, constraints and database triggers; it does not emulate
 * lease, retry, receipt or checkpoint persistence in memory.
 */
@Testcontainers(disabledWithoutDocker = true)
class AutomationGoldenExecutionEvidenceTest {

    private static final long TENANT = 73506L;
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final String DIGEST_E = "e".repeat(64);
    private static final String DIGEST_F = "f".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private JdbcAutomationRepository repository;

    @BeforeAll
    static void migrateRealPlatformSchema() {
        PGSimpleDataSource source = dataSource();
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .schemas("public")
                .defaultSchema("public")
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("333")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        source.setCurrentSchema("public");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
    }

    @BeforeEach
    void resetGoldenEvidence() {
        jdbc.execute("""
                TRUNCATE TABLE
                    sys_automation_run_checkpoints,
                    sys_automation_item_attempts,
                    sys_automation_run_items,
                    sys_automation_run_attempts,
                    sys_automation_runs,
                    sys_automation_schedules,
                    sys_automation_versions,
                    sys_automation_definitions,
                    sys_hris_command_receipts
                CASCADE
                """);
        repository = new JdbcAutomationRepository(jdbc, JSON);
    }

    @Test
    void sysGold015SignedTypedExecutionDurablyReloadsAndRejectsUnregisteredDispatch() {
        transactions.executeWithoutResult(ignored -> {
            GoldenFixture fixture = seedQueued("gold015", 1);
            OffsetDateTime now = databaseNow();
            RuntimeRun queued = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant worker = repository.acquireWorkerLease(
                    queued, uuid("gold015-worker"), now.plusMinutes(2), now);
            RuntimeRun leased = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            AutomationItemPlan plan = new AutomationItemPlan(
                    uuid("gold015-item"), 1, "subject:gold015");
            repository.materializeItems(leased, worker, List.of(plan), now);
            RuntimeRun materialized = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            PreparedOriginal prepared = repository.prepareOriginal(
                    materialized, worker, DIGEST_F, now);

            assertClosedInvocationRejects(
                    prepared.invocation(), "class", "java.lang.Runtime");
            assertClosedInvocationRejects(
                    prepared.invocation(), "method", "exec");
            assertClosedInvocationRejects(
                    prepared.invocation(), "sql", "DELETE FROM sys_automation_runs");

            SignedManifest signed = materialized.manifest();
            SignedArtifactVerifierPort verifier = verifier(now.plusMinutes(5));
            AutomationHandlerRegistryPort registry = identity ->
                    identity.equals(signed.identity())
                            ? Optional.of(signed) : Optional.empty();
            RecordingHandler handler = new RecordingHandler(signed.identity());

            AutomationRuntimeService noArbitraryDispatch = runtime(
                    registry, verifier, List.of());
            Throwable unavailable = catchThrowable(() ->
                    noArbitraryDispatch.invokeOriginal(
                            fixture.tenantId(), fixture.subject(),
                            fixture.runPublicId(), worker, prepared));
            assertThat(unavailable)
                    .hasMessageContaining("AUTOMATION_TYPED_HANDLER_UNAVAILABLE");

            AutomationRuntimeService typedRuntime = runtime(
                    registry, verifier, List.of(handler));
            List<AutomationRunItemReceiptV1> receipts =
                    typedRuntime.invokeOriginal(
                            fixture.tenantId(), fixture.subject(),
                            fixture.runPublicId(), worker, prepared);
            assertThat(handler.invocations()).isOne();
            assertThat(receipts).hasSize(1);

            RuntimeRun finalized = repository.finalizeOriginal(
                    materialized, worker, prepared, receipts, now.plusSeconds(2));
            AutomationRunItemReceiptV1 reloaded =
                    repository.loadCommittedOriginalReceipt(
                            finalized, plan.itemId());
            assertThat(reloaded).isEqualTo(receipts.getFirst());
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_automation_item_attempts
                     WHERE tenant_id = ? AND run_id = ?
                       AND effect_state = 'COMMITTED'
                       AND effect_receipt_digest IS NOT NULL
                    """, Integer.class, fixture.tenantId(), fixture.runId()))
                    .isOne();
        });
    }

    @Test
    void sysGold016SameIdempotencyPayloadReplaysTheExactReceipt() {
        transactions.executeWithoutResult(ignored -> {
            CommandSeal command = commandSeal(DIGEST_A);
            OffsetDateTime now = databaseNow();
            var first = repository.beginCommand(command, now);
            var replay = repository.beginCommand(command, now.plusSeconds(1));

            assertThat(replay).isEqualTo(first);
            assertThat(repository.findReplay(command)).contains(first);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_hris_command_receipts
                     WHERE tenant_id = ? AND originating_action = ?
                       AND idempotency_key = ?
                    """, Integer.class, command.tenantId(),
                    command.originatingAction(), command.idempotencyKey()))
                    .isOne();
        });
    }

    @Test
    void sysGold016DifferentPayloadWithTheSameKeyIsRejectedWithoutASecondReceipt() {
        transactions.executeWithoutResult(ignored -> {
            repository.beginCommand(commandSeal(DIGEST_A), databaseNow());

            Throwable conflict = catchThrowable(() ->
                    repository.beginCommand(commandSeal(DIGEST_B), databaseNow()));

            assertResourceConflict(conflict, "AUTOMATION_IDEMPOTENCY_CONFLICT");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_hris_command_receipts
                     WHERE tenant_id = ? AND idempotency_key = ?
                    """, Integer.class, TENANT, "gold016-command-key-0001"))
                    .isOne();
        });
    }

    @Test
    void sysGold017ExpiredLeaseResumesWithAHigherFenceAndRejectsTheStaleToken() {
        transactions.executeWithoutResult(ignored -> {
            GoldenFixture fixture = seedQueued("gold017", 0);
            OffsetDateTime now = databaseNow();
            RuntimeRun queued = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant crashed = repository.acquireWorkerLease(
                    queued, uuid("gold017-crashed-worker"),
                    now.plusSeconds(30), now);
            expireWorkerLease(fixture, now.minusSeconds(1));

            RuntimeRun expired = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            AutomationDomainPolicy.RecoveryDecision decision =
                    AutomationDomainPolicy.ownerRecovery(
                            expired.controlFenceEpoch(), expired.workerFenceEpoch(),
                            expired.reconcilerFenceEpoch(), expired.workerLease(),
                            expired.reconcilerLease(), crashed.token(), now, false);
            var recovered = repository.recoverAuthority(
                    expired, decision, crashed.token(), false,
                    "LEASE_EXPIRED_EFFECT_EVIDENCE_REQUIRED", now);
            assertThat(recovered.externalDispatchPerformed()).isFalse();

            JdbcAutomationRepository reloadedRepository =
                    new JdbcAutomationRepository(jdbc, JSON);
            RuntimeRun resumable = reloadedRepository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant resumed = reloadedRepository.acquireWorkerLease(
                    resumable, uuid("gold017-resumed-worker"),
                    now.plusMinutes(2), now);
            RuntimeRun resumedRun = reloadedRepository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            assertThat(resumed.attemptNumber()).isEqualTo(crashed.attemptNumber());
            assertThat(resumed.authorityFenceEpoch())
                    .isGreaterThan(crashed.authorityFenceEpoch());
            assertThat(resumed.controlFenceEpoch())
                    .isGreaterThan(crashed.controlFenceEpoch());

            Throwable stale = catchThrowable(() -> reloadedRepository.renewWorkerLease(
                    resumedRun, crashed, now.plusMinutes(3), now));
            assertResourceConflict(stale, "AUTOMATION_WORKER_LEASE_CAS_CONFLICT");
            Throwable staleCheckpoint = catchThrowable(() ->
                    reloadedRepository.appendCheckpointAndRecalculate(
                            resumedRun, crashed, DIGEST_A, null, now));
            assertThat(staleCheckpoint)
                    .hasMessageContaining("AUTOMATION_STALE_AUTHORITY_FENCE");
            assertThat(reloadedRepository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId()).workerLeaseToken())
                    .isEqualTo(resumed.token());
        });

        transactions.executeWithoutResult(ignored -> {
            GoldenFixture fixture = seedQueued("gold017-stale-finalize", 1);
            OffsetDateTime now = databaseNow();
            RuntimeRun queued = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant expiredWorker = repository.acquireWorkerLease(
                    queued, uuid("gold017-stale-finalize-worker"),
                    now.plusSeconds(30), now);
            RuntimeRun leased = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            AutomationItemPlan plan = new AutomationItemPlan(
                    uuid("gold017-stale-finalize-item"), 1,
                    "subject:gold017-stale-finalize");
            repository.materializeItems(leased, expiredWorker, List.of(plan), now);
            RuntimeRun materialized = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            PreparedOriginal prepared = repository.prepareOriginal(
                    materialized, expiredWorker, DIGEST_E, now);
            expireWorkerLease(fixture, now.minusSeconds(1));
            RuntimeRun expired = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            AutomationRunItemReceiptV1 receipt = successfulReceipt(
                    prepared.invocation(), effectRequest(materialized,
                            prepared.items().getFirst()));

            Throwable staleFinalize = catchThrowable(() ->
                    repository.finalizeOriginal(
                            expired, expiredWorker, prepared,
                            List.of(receipt), now.plusSeconds(1)));
            assertThat(staleFinalize)
                    .hasMessageContaining("AUTOMATION_STALE_AUTHORITY_FENCE");
            Throwable staleCheckpoint = catchThrowable(() ->
                    repository.appendCheckpointAndRecalculate(
                            expired, expiredWorker, DIGEST_A, null,
                            now.plusSeconds(1)));
            assertThat(staleCheckpoint)
                    .hasMessageContaining("AUTOMATION_STALE_AUTHORITY_FENCE");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_automation_item_attempts
                     WHERE tenant_id = ? AND run_id = ?
                       AND completed_at IS NOT NULL
                    """, Integer.class, fixture.tenantId(), fixture.runId()))
                    .isZero();
            assertThat(checkpointCount(fixture.tenantId(), fixture.runId()))
                    .isZero();
        });
    }

    @Test
    void sysGold018PartialRetryCreatesANewAttemptOnlyForTheRetryableFailure() {
        transactions.executeWithoutResult(ignored -> {
            PartialFixture fixture = seedPartial("gold018", false);
            RunRecord partial = repository.lockRun(
                    fixture.tenantId(), fixture.runPublicId());
            RetrySelection selection = repository.retrySelection(partial);
            RunRecord retried = repository.retryRun(
                    partial, fixture.subject(), uuid("gold018-receipt"),
                    selection.mode(), selection.selectedItemCount(),
                    selection.selectedItemDigest(), databaseNow());

            assertThat(retried.currentAttempt()).isEqualTo(2);
            assertThat(selection.mode()).isEqualTo(SelectionMode.FAILED_ITEMS_ONLY);
            assertThat(selection.selectedItemCount()).isOne();
            assertThat(selection.selectedItemDigest())
                    .isEqualTo(selectionDigest(List.of(fixture.retryableItem())));
            assertThat(attemptedItems(fixture, 2))
                    .containsExactly(fixture.retryableItem());
            assertThat(itemEvidence(fixture, fixture.successfulItem()))
                    .containsEntry("lifecycle_state", "SUCCEEDED")
                    .containsEntry("effect_state", "COMMITTED")
                    .containsEntry("attempt_count", 1);
            assertThat(itemEvidence(fixture, fixture.finalFailedItem()))
                    .containsEntry("lifecycle_state", "FAILED_FINAL")
                    .containsEntry("effect_state", "COMMITTED")
                    .containsEntry("attempt_count", 1);

            OffsetDateTime now = databaseNow();
            RuntimeRun queued = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant worker = repository.acquireWorkerLease(
                    queued, uuid("gold018-retry-worker"),
                    now.plusMinutes(2), now);
            RuntimeRun leased = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            PreparedOriginal prepared = repository.prepareOriginal(
                    leased, worker, DIGEST_E, now);
            SignedManifest signed = leased.manifest();
            RecordingHandler handler = new RecordingHandler(signed.identity());
            AutomationRuntimeService runtime = runtime(
                    identity -> identity.equals(signed.identity())
                            ? Optional.of(signed) : Optional.empty(),
                    verifier(now.plusMinutes(5)), List.of(handler));
            List<AutomationRunItemReceiptV1> receipts = runtime.invokeOriginal(
                    fixture.tenantId(), fixture.subject(), fixture.runPublicId(),
                    worker, prepared);
            RuntimeRun checkpointed = runtime.finalizeOriginal(
                    fixture.tenantId(), fixture.subject(), fixture.runPublicId(),
                    worker, prepared, receipts);

            assertThat(handler.invocations()).isOne();
            assertThat(prepared.items())
                    .extracting(AutomationExecutionRepository.PreparedItem::itemPublicId)
                    .containsExactly(fixture.retryableItem());
            assertThat(checkpointed.run().state())
                    .isEqualTo(AutomationDtos.RunState.DEAD_LETTER);
            assertThat(jdbc.queryForMap("""
                    SELECT processed_item_count, writer_authority_kind
                      FROM sys_automation_run_checkpoints
                     WHERE tenant_id = ? AND run_id = ? AND attempt_number = 2
                    """, fixture.tenantId(), fixture.runId()))
                    .containsEntry("processed_item_count", 1L)
                    .containsEntry("writer_authority_kind", "WORKER");
            assertThat(itemEvidence(fixture, fixture.successfulItem()))
                    .containsEntry("attempt_count", 1);
        });
    }

    @Test
    void sysGold051ResumeStartsAfterTheDurableCheckpointAndSkipsCommittedEffects() {
        Golden051Checkpoint checkpointed = transactions.execute(ignored -> {
            repository = new JdbcAutomationRepository(jdbc, JSON);
            GoldenFixture fixture = seedQueued("gold051-resume", 2);
            UUID successfulItem = uuid("gold051-resume-success");
            UUID retryableItem = uuid("gold051-resume-retryable");
            OffsetDateTime now = databaseNow();
            RuntimeRun queued = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant worker = repository.acquireWorkerLease(
                    queued, uuid("gold051-first-worker"),
                    now.plusMinutes(2), now);
            RuntimeRun leased = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            repository.materializeItems(leased, worker, List.of(
                    new AutomationItemPlan(
                            successfulItem, 1, "subject:gold051-success"),
                    new AutomationItemPlan(
                            retryableItem, 2, "subject:gold051-retry")), now);
            RuntimeRun materialized = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            PreparedOriginal prepared = repository.prepareOriginal(
                    materialized, worker, DIGEST_E, now);
            AutomationExecutionRepository.PreparedItem success = prepared.items()
                    .stream().filter(item -> item.itemPublicId().equals(successfulItem))
                    .findFirst().orElseThrow();
            AutomationExecutionRepository.PreparedItem retryable = prepared.items()
                    .stream().filter(item -> item.itemPublicId().equals(retryableItem))
                    .findFirst().orElseThrow();
            SignedManifest signed = materialized.manifest();
            AutomationRuntimeService workerRuntime = runtime(
                    identity -> identity.equals(signed.identity())
                            ? Optional.of(signed) : Optional.empty(),
                    verifier(now.plusMinutes(5)), List.of());
            RuntimeRun firstCheckpoint = workerRuntime.finalizeOriginal(
                    fixture.tenantId(), fixture.subject(), fixture.runPublicId(),
                    worker, prepared, List.of(
                            successfulReceipt(prepared.invocation(),
                                    effectRequest(materialized, success)),
                            retryableFailureReceipt(prepared.invocation(),
                                    effectRequest(materialized, retryable))));
            assertThat(firstCheckpoint.run().state())
                    .isEqualTo(AutomationDtos.RunState.RUNNING);
            assertThat(checkpointCount(fixture.tenantId(), fixture.runId()))
                    .isOne();

            RuntimeRun uncertain = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            OffsetDateTime evidenceNow = databaseNow();
            LeaseGrant reconciler = repository.acquireReconcilerLease(
                    uncertain, uuid("gold051-reconciler"),
                    evidenceNow.plusMinutes(2), evidenceNow);
            ExternalEffectEvidencePort evidence = (tenantId, runPublicId,
                    itemPublicId, effectKey, requestDigest) ->
                    new ReconciliationEvidence(
                            "NO_EFFECT_CONFIRMED", null, DIGEST_D,
                            evidenceNow, evidenceNow.plusMinutes(2));
            AutomationRuntimeService reconciliationRuntime = runtime(
                    identity -> identity.equals(signed.identity())
                            ? Optional.of(signed) : Optional.empty(),
                    verifier(evidenceNow.plusMinutes(5)), List.of(),
                    List.of(evidence));
            RuntimeRun reconciled = reconciliationRuntime.reconcileOriginal(
                    fixture.tenantId(), fixture.subject(), fixture.runPublicId(),
                    retryableItem, reconciler, retryable.effectIdempotencyKey(),
                    retryable.effectRequestDigest());
            assertThat(reconciled.run().state())
                    .isEqualTo(AutomationDtos.RunState.PARTIAL);
            assertThat(checkpointCount(fixture.tenantId(), fixture.runId()))
                    .isEqualTo(2);
            return new Golden051Checkpoint(
                    fixture, successfulItem, retryableItem);
        });

        Golden051Crash crashed = transactions.execute(ignored -> {
            repository = new JdbcAutomationRepository(jdbc, JSON);
            GoldenFixture fixture = checkpointed.fixture();
            RunRecord partial = repository.lockRun(
                    fixture.tenantId(), fixture.runPublicId());
            RetrySelection selection = repository.retrySelection(partial);
            repository.retryRun(
                    partial, fixture.subject(), uuid("gold051-retry-receipt"),
                    selection.mode(), selection.selectedItemCount(),
                    selection.selectedItemDigest(), databaseNow());
            assertThat(jdbc.queryForObject("""
                    SELECT resume_after_checkpoint_ordinal
                      FROM sys_automation_run_attempts
                     WHERE tenant_id = ? AND run_id = ? AND attempt_number = 2
                    """, Long.class, fixture.tenantId(), fixture.runId()))
                    .isEqualTo(2L);
            OffsetDateTime now = databaseNow();
            RuntimeRun queued = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant crashedWorker = repository.acquireWorkerLease(
                    queued, uuid("gold051-crashed-worker"),
                    now.plusSeconds(30), now);
            expireWorkerLease(fixture, 2, now.minusSeconds(1));
            return new Golden051Crash(checkpointed, crashedWorker);
        });

        transactions.executeWithoutResult(ignored -> {
            repository = new JdbcAutomationRepository(jdbc, JSON);
            GoldenFixture fixture = crashed.checkpointed().fixture();
            OffsetDateTime now = databaseNow();
            RuntimeRun expired = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            AutomationDomainPolicy.RecoveryDecision decision =
                    AutomationDomainPolicy.ownerRecovery(
                            expired.controlFenceEpoch(), expired.workerFenceEpoch(),
                            expired.reconcilerFenceEpoch(), expired.workerLease(),
                            expired.reconcilerLease(), crashed.worker().token(),
                            now, false);
            repository.recoverAuthority(
                    expired, decision, crashed.worker().token(), false,
                    "LEASE_EXPIRED_EFFECT_EVIDENCE_REQUIRED", now);

            JdbcAutomationRepository resumedRepository =
                    new JdbcAutomationRepository(jdbc, JSON);
            RuntimeRun resumable = resumedRepository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant resumed = resumedRepository.acquireWorkerLease(
                    resumable, uuid("gold051-resumed-worker"),
                    now.plusMinutes(2), now);
            RuntimeRun leased = resumedRepository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            PreparedOriginal selected = resumedRepository.prepareOriginal(
                    leased, resumed, DIGEST_E, now);

            assertThat(resumed.authorityFenceEpoch())
                    .isGreaterThan(crashed.worker().authorityFenceEpoch());
            assertThat(selected.items())
                    .extracting(AutomationExecutionRepository.PreparedItem::itemPublicId)
                    .containsExactly(crashed.checkpointed().retryableItem());
            Throwable staleCheckpoint = catchThrowable(() ->
                    resumedRepository.appendCheckpointAndRecalculate(
                            leased, crashed.worker(), DIGEST_A, null, now));
            assertThat(staleCheckpoint)
                    .hasMessageContaining("AUTOMATION_STALE_AUTHORITY_FENCE");
            assertThat(itemAttemptCount(
                    fixture, crashed.checkpointed().successfulItem())).isOne();
            assertThat(attemptedItems(
                    fixture.tenantId(), fixture.runId(), 2))
                    .containsExactly(crashed.checkpointed().retryableItem());
        });
    }

    @Test
    void sysGold051StaleLeaseCannotAppendOrRegressTheCheckpoint() {
        transactions.executeWithoutResult(ignored -> {
            PartialFixture fixture = seedPartial("gold051-stale", true);
            RunRecord partial = repository.lockRun(
                    fixture.tenantId(), fixture.runPublicId());
            RetrySelection selection = repository.retrySelection(partial);
            repository.retryRun(
                    partial, fixture.subject(), uuid("gold051-stale-receipt"),
                    selection.mode(), selection.selectedItemCount(),
                    selection.selectedItemDigest(), databaseNow());
            OffsetDateTime now = databaseNow();
            RuntimeRun queued = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant current = repository.acquireWorkerLease(
                    queued, uuid("gold051-current-worker"),
                    now.plusMinutes(2), now);
            RuntimeRun leased = repository.lockRuntimeRun(
                    fixture.tenantId(), fixture.runPublicId());
            LeaseGrant stale = new LeaseGrant(
                    AutomationDomainPolicy.AuthorityKind.WORKER,
                    fixture.tenantId(), fixture.runPublicId(), 1,
                    uuid("gold051-expired-worker"),
                    current.authorityFenceEpoch() - 1,
                    current.controlFenceEpoch(), now.minusSeconds(1));

            Throwable rejected = catchThrowable(() ->
                    repository.appendCheckpointAndRecalculate(
                            leased, stale, DIGEST_A, null, now));

            assertThat(rejected)
                    .hasMessageContaining("AUTOMATION_STALE_AUTHORITY_FENCE");
            assertThat(checkpointCount(fixture)).isOne();
        });
    }

    @Test
    void sysGold051MismatchedProcessedItemDigestIsRejectedWithoutANewCheckpoint() {
        PartialFixture fixture = seedPartial("gold051-digest", true);
        RunRecord partial = repository.lockRun(
                fixture.tenantId(), fixture.runPublicId());
        RetrySelection selection = repository.retrySelection(partial);
        repository.retryRun(
                partial, fixture.subject(), uuid("gold051-digest-receipt"),
                selection.mode(), selection.selectedItemCount(),
                selection.selectedItemDigest(), databaseNow());
        OffsetDateTime now = databaseNow();
        RuntimeRun queued = repository.lockRuntimeRun(
                fixture.tenantId(), fixture.runPublicId());
        LeaseGrant worker = repository.acquireWorkerLease(
                queued, uuid("gold051-digest-worker"), now.plusMinutes(2), now);
        RuntimeRun leased = repository.lockRuntimeRun(
                fixture.tenantId(), fixture.runPublicId());

        Throwable rejected = rollbackFailure(() ->
                repository.appendCheckpointAndRecalculate(
                        leased, worker, DIGEST_F, null, now));

        assertThat(rejected)
                .hasMessageContaining("AUTOMATION_CHECKPOINT_PROCESSED_DIGEST_MISMATCH");
        assertThat(checkpointCount(fixture)).isOne();
    }

    @Test
    void sysGold052FailedItemsOnlyFreezesTheExactRetryableIdentitySet() {
        transactions.executeWithoutResult(ignored -> {
            PartialFixture fixture = seedPartial("gold052-exact", false);
            RunRecord partial = repository.lockRun(
                    fixture.tenantId(), fixture.runPublicId());
            RetrySelection selection = repository.retrySelection(partial);
            repository.retryRun(
                    partial, fixture.subject(), uuid("gold052-exact-receipt"),
                    selection.mode(), selection.selectedItemCount(),
                    selection.selectedItemDigest(), databaseNow());

            assertThat(selection.mode()).isEqualTo(SelectionMode.FAILED_ITEMS_ONLY);
            assertThat(selection.selectedItemCount()).isOne();
            assertThat(selection.selectedItemDigest())
                    .isEqualTo(selectionDigest(List.of(fixture.retryableItem())));
            assertThat(attemptedItems(fixture, 2))
                    .containsExactly(fixture.retryableItem());
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_automation_item_attempts
                     WHERE tenant_id = ? AND run_id = ? AND attempt_ordinal = 2
                    """, Integer.class, fixture.tenantId(), fixture.runId()))
                    .isOne();
        });
    }

    @Test
    void sysGold052AllItemsSubstitutionIsRejectedWithoutANewAttempt() {
        PartialFixture fixture = seedPartial("gold052-all", false);
        RunRecord partial = repository.lockRun(
                fixture.tenantId(), fixture.runPublicId());
        String allItemsDigest = selectionDigest(List.of(
                fixture.successfulItem(), fixture.retryableItem(),
                fixture.finalFailedItem()));

        Throwable rejected = rollbackFailure(() -> repository.retryRun(
                partial, fixture.subject(), uuid("gold052-all-receipt"),
                SelectionMode.ALL_ITEMS, 3, allItemsDigest, databaseNow()));

        assertResourceConflict(rejected, "AUTOMATION_RETRY");
        assertThat(runAttemptCount(fixture)).isOne();
    }

    @Test
    void sysGold052EmptyFailedItemsSelectionIsRejectedWithoutANewAttempt() {
        PartialFixture fixture = seedPartial("gold052-empty", false);
        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET session_replication_role = replica");
            try {
                jdbc.update("""
                        UPDATE sys_automation_run_items
                           SET lifecycle_state = 'FAILED_FINAL'
                         WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                        """, fixture.tenantId(), fixture.runId(),
                        fixture.retryableItem());
                jdbc.update("""
                        UPDATE sys_automation_item_attempts a
                           SET receipt_state = 'FAILED_FINAL',
                               retryable_snapshot = FALSE, next_attempt_at = NULL
                          FROM sys_automation_run_items i
                         WHERE a.tenant_id = i.tenant_id
                           AND a.run_id = i.run_id
                           AND a.run_item_id = i.run_item_id
                           AND a.tenant_id = ? AND a.run_id = ?
                           AND i.public_id = ?
                        """, fixture.tenantId(), fixture.runId(),
                        fixture.retryableItem());
            } finally {
                jdbc.execute("SET session_replication_role = origin");
            }
        });
        RunRecord partial = repository.lockRun(
                fixture.tenantId(), fixture.runPublicId());

        Throwable rejected = catchThrowable(() -> repository.retrySelection(partial));

        assertResourceConflict(rejected, "AUTOMATION_RETRY_SELECTION_EMPTY");
        assertThat(runAttemptCount(fixture)).isOne();
    }

    @Test
    void sysGold052DigestMismatchIsRejectedWithoutANewAttempt() {
        PartialFixture fixture = seedPartial("gold052-digest", false);
        RunRecord partial = repository.lockRun(
                fixture.tenantId(), fixture.runPublicId());
        RetrySelection selection = repository.retrySelection(partial);

        Throwable rejected = rollbackFailure(() -> repository.retryRun(
                partial, fixture.subject(), uuid("gold052-digest-receipt"),
                selection.mode(), selection.selectedItemCount(),
                DIGEST_F, databaseNow()));

        assertResourceConflict(rejected, "AUTOMATION_RETRY_SELECTION_CHANGED");
        assertThat(runAttemptCount(fixture)).isOne();
    }

    private AutomationRuntimeService runtime(
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            List<TypedAutomationHandlerPort> handlers) {
        return runtime(registry, verifier, handlers, List.of());
    }

    private AutomationRuntimeService runtime(
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            List<TypedAutomationHandlerPort> handlers,
            List<ExternalEffectEvidencePort> evidenceProviders) {
        return new AutomationRuntimeService(
                repository, repository, registry, verifier, handlers,
                evidenceProviders, JSON);
    }

    private static void assertClosedInvocationRejects(
            AutomationInvocationV1 invocation, String property, String payload) {
        ObjectNode document = JSON.valueToTree(invocation);
        document.put(property, payload);
        Throwable rejected = catchThrowable(() -> JSON.treeToValue(
                document, AutomationInvocationV1.class));
        assertThat(rejected).hasMessageContaining("unknown property " + property);
    }

    private static void assertResourceConflict(
            Throwable throwable, String message) {
        assertThat(throwable)
                .isInstanceOf(BaseException.class)
                .hasMessageContaining(message);
        assertThat(((BaseException) throwable).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    private static SignedArtifactVerifierPort verifier(
            OffsetDateTime freshUntil) {
        return new SignedArtifactVerifierPort() {
            @Override
            public VerifiedArtifact verifyDraft(
                    AutomationDtos.CreateVersionCommand command) {
                throw new UnsupportedOperationException(
                        "draft verification is outside execution evidence");
            }

            @Override
            public VerifiedArtifact revalidate(
                    SignedManifest approvedEvidence,
                    String definitionDigest,
                    String executionPolicyDigest,
                    String compatibilityBaselineDigest) {
                return new VerifiedArtifact(
                        approvedEvidence, "golden-registry-r1", DIGEST_A,
                        "golden-compatibility-v1", DIGEST_B, DIGEST_C,
                        freshUntil);
            }
        };
    }

    private static CommandSeal commandSeal(String requestDigest) {
        return new CommandSeal(
                TENANT, uuid("gold016-subject"),
                "CREATE_AUTOMATION_DEFINITION",
                AutomationService.CREATE_DEFINITION,
                "route:sys006:gold016", "context:sys006:gold016",
                "scope:tenant:" + TENANT, "decision:gold016:1",
                DIGEST_C, 1, 1, "HRIS_SYSTEM_ADMIN",
                "gold016-command-key-0001", requestDigest,
                uuid("gold016-correlation"));
    }

    private GoldenFixture seedQueued(String discriminator, int expectedItemCount) {
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID actor = uuid(discriminator + "-actor");
        UUID subject = uuid(discriminator + "-subject");
        OffsetDateTime now = databaseNow();
        insertDefinitionAndVersion(
                discriminator, definitionId, definitionPublicId,
                versionId, actor, now);
        jdbc.update("""
                INSERT INTO sys_automation_runs (
                    run_id, public_id, tenant_id, definition_id, version_id,
                    version_number, lifecycle_state, selection_mode, scope_type,
                    requested_at, requested_by_actor_ref, idempotency_key,
                    correlation_id, input_ref, input_digest, expected_item_count,
                    concurrency_key_digest, retry_mode, max_attempts,
                    current_attempt, item_total, command_receipt_id,
                    created_by_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'QUEUED', 'ALL_ITEMS', 'TENANT', ?, ?, ?,
                        ?, 'input:golden', ?, ?, ?, 'FAILED_ITEMS_ONLY', 3,
                        1, 0, ?, ?, ?, ?)
                """, runId, runPublicId, TENANT, definitionId, versionId,
                now, "principal:" + actor,
                "golden-" + discriminator + "-0001",
                uuid(discriminator + "-correlation"), DIGEST_A,
                expectedItemCount, digest(discriminator + "-concurrency"),
                uuid(discriminator + "-command-receipt"), actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    tenant_id, run_id, attempt_number, selection_mode,
                    selected_item_count, selected_item_digest, created_at)
                VALUES (?, ?, 1, 'ALL_ITEMS', 0, ?, ?)
                """, TENANT, runId, selectionDigest(List.of()), now);
        return new GoldenFixture(
                TENANT, definitionId, definitionPublicId, versionId,
                runId, runPublicId, actor, subject);
    }

    private PartialFixture seedPartial(
            String discriminator, boolean withCheckpoint) {
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID runAttemptId = uuid(discriminator + "-attempt-1");
        UUID actor = uuid(discriminator + "-actor");
        UUID subject = uuid(discriminator + "-subject");
        UUID successful = uuid(discriminator + "-success");
        UUID retryable = uuid(discriminator + "-retryable");
        UUID finalFailed = uuid(discriminator + "-final");
        OffsetDateTime now = databaseNow();

        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET session_replication_role = replica");
            try {
                insertDefinitionAndVersion(
                        discriminator, definitionId, definitionPublicId,
                        versionId, actor, now);
                jdbc.update("""
                        INSERT INTO sys_automation_runs (
                            run_id, public_id, tenant_id, definition_id, version_id,
                            version_number, lifecycle_state, selection_mode, scope_type,
                            requested_at, requested_by_actor_ref, idempotency_key,
                            correlation_id, input_ref, input_digest, expected_item_count,
                            concurrency_key_digest, retry_mode, max_attempts,
                            current_attempt, item_total, item_succeeded, item_failed,
                            command_receipt_id, worker_fence_epoch,
                            control_fence_epoch, created_by_actor_ref,
                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, 'PARTIAL', 'ALL_ITEMS', 'TENANT',
                                ?, ?, ?, ?, 'input:golden', ?, 3, ?,
                                'FAILED_ITEMS_ONLY', 3, 1, 3, 1, 2, ?, 1, 0, ?, ?, ?)
                        """, runId, runPublicId, TENANT, definitionId, versionId,
                        now, "principal:" + actor,
                        "golden-" + discriminator + "-0001",
                        uuid(discriminator + "-correlation"), DIGEST_A,
                        digest(discriminator + "-concurrency"),
                        uuid(discriminator + "-command-receipt"), actor, now, now);
                jdbc.update("""
                        INSERT INTO sys_automation_run_attempts (
                            run_attempt_id, tenant_id, run_id, attempt_number,
                            selection_mode, selected_item_count, selected_item_digest,
                            lifecycle_state, worker_fence_epoch,
                            started_at, completed_at, created_at)
                        VALUES (?, ?, ?, 1, 'ALL_ITEMS', 3, ?, 'PARTIAL', 1, ?, ?, ?)
                        """, runAttemptId, TENANT, runId,
                        selectionDigest(List.of(successful, retryable, finalFailed)),
                        now, now, now);
                insertTerminalItem(runId, successful, 1, "subject:successful",
                        "SUCCEEDED", "COMMITTED", DIGEST_B, null,
                        "SUCCEEDED", false, now, discriminator);
                insertTerminalItem(runId, retryable, 2, "subject:retryable",
                        "FAILED_RETRYABLE", "NO_EFFECT_CONFIRMED", null,
                        "RETRYABLE_FAILURE", "FAILED_RETRYABLE", true,
                        now, discriminator);
                insertTerminalItem(runId, finalFailed, 3, "subject:final",
                        "FAILED_FINAL", "COMMITTED", DIGEST_C,
                        "FINAL_FAILURE", "FAILED_FINAL", false,
                        now, discriminator);
                if (withCheckpoint) {
                    jdbc.update("""
                            INSERT INTO sys_automation_run_checkpoints (
                                tenant_id, run_id, run_attempt_id, attempt_number,
                                checkpoint_ordinal, processed_item_count,
                                processed_item_digest, output_digest,
                                writer_authority_kind, authority_fence_epoch,
                                control_fence_epoch, committed_at)
                            VALUES (?, ?, ?, 1, 1, 3, ?, ?, 'WORKER', 1, 0, ?)
                            """, TENANT, runId, runAttemptId, DIGEST_D, DIGEST_E, now);
                }
            } finally {
                jdbc.execute("SET session_replication_role = origin");
            }
        });
        return new PartialFixture(
                TENANT, runId, runPublicId, subject,
                successful, retryable, finalFailed);
    }

    private void insertDefinitionAndVersion(
            String discriminator,
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            UUID actor,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'HRM', ?, ?, ?)
                """, definitionId, definitionPublicId, TENANT,
                "gold." + discriminator, "Golden " + discriminator,
                actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_versions (
                    version_id, tenant_id, definition_id, version_number,
                    lifecycle_state, definition_payload, definition_digest,
                    execution_policy, execution_policy_digest,
                    manual_concurrency_key_template, compensation_policy_digest,
                    manifest_retry_mode, manifest_max_attempts,
                    handler_owner_service, handler_key, handler_contract_version,
                    allowed_scope_types, handler_manifest_state,
                    handler_manifest_revision, handler_manifest_digest,
                    handler_signature_key_id, handler_signature,
                    handler_signature_digest, input_schema_ref,
                    input_schema_digest, receipt_schema_ref,
                    receipt_schema_digest, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 'ACTIVE', '{}'::jsonb, ?, '{}'::jsonb, ?,
                        'tenant:{tenantId}', ?, 'FAILED_ITEMS_ONLY', 3,
                        'dwp-hrm-server', 'hris.hrm.test', 'AutomationInput.v1',
                        ARRAY['TENANT']::TEXT[], 'ACTIVE', 1, ?,
                        'golden-signing-key', 'golden-signature', ?,
                        'schema:input:v1', ?, 'schema:receipt:v1', ?, ?, ?, ?)
                """, versionId, TENANT, definitionId, DIGEST_A, DIGEST_B,
                DIGEST_C, DIGEST_D, DIGEST_E, DIGEST_A, DIGEST_B,
                actor, now, now);
        jdbc.update("""
                UPDATE sys_automation_definitions
                   SET lifecycle_state = 'ACTIVE', active_version_number = 1
                 WHERE tenant_id = ? AND definition_id = ?
                """, TENANT, definitionId);
    }

    private void insertTerminalItem(
            UUID runId,
            UUID itemPublicId,
            long sequence,
            String subjectRef,
            String itemState,
            String effectState,
            String resultDigest,
            String errorCode,
            String receiptState,
            boolean retryable,
            OffsetDateTime now,
            String discriminator) {
        UUID itemId = uuid(discriminator + "-item-" + sequence);
        UUID attemptId = uuid(discriminator + "-item-attempt-" + sequence);
        String effectKey = "effect-" + discriminator + "-" + sequence + "-0001";
        String receiptDigest = "COMMITTED".equals(effectState)
                ? digest(discriminator + "-receipt-" + sequence) : null;
        jdbc.update("""
                INSERT INTO sys_automation_run_items (
                    run_item_id, public_id, tenant_id, run_id, item_sequence,
                    subject_ref, subject_reference_digest, lifecycle_state,
                    attempt_count, result_digest, error_code, effect_state,
                    effect_idempotency_key, effect_request_digest,
                    effect_receipt_digest, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                """, itemId, itemPublicId, TENANT, runId, sequence,
                subjectRef, digest(subjectRef), itemState, resultDigest,
                errorCode, effectState, effectKey, DIGEST_D,
                receiptDigest, now);
        jdbc.update("""
                INSERT INTO sys_automation_item_attempts (
                    item_attempt_id, tenant_id, run_id, run_item_id,
                    attempt_ordinal, run_revision_snapshot,
                    receipt_state, accepted_at, next_attempt_at,
                    result_ref, result_digest, error_code,
                    retryable_snapshot, correlation_id,
                    effect_state, effect_authority_kind,
                    effect_idempotency_key, effect_request_digest,
                    effect_receipt_id, effect_receipt_digest,
                    effect_dispatch_authority_fence,
                    effect_dispatch_control_fence,
                    effect_resolution_authority_fence,
                    effect_resolution_control_fence,
                    effect_resolution_mode, effect_committed_at,
                    started_at, completed_at, created_at)
                VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WORKER',
                        ?, ?, ?, ?, 1, 0, 1, 0, 'DISPATCH_FINALIZE', ?, ?, ?, ?)
                """, attemptId, TENANT, runId, itemId, receiptState, now,
                retryable ? now.plusMinutes(1) : null,
                "SUCCEEDED".equals(receiptState)
                        ? "result:" + itemPublicId : null,
                resultDigest, errorCode, retryable,
                uuid(discriminator + "-item-correlation-" + sequence),
                effectState, effectKey, DIGEST_D,
                receiptDigest == null ? null
                        : uuid(discriminator + "-effect-receipt-" + sequence),
                receiptDigest,
                "COMMITTED".equals(effectState) ? now : null,
                now, now, now);
    }

    private static AutomationRunItemReceiptV1 successfulReceipt(
            AutomationInvocationV1 invocation,
            AutomationEffectRequest request) {
        return new AutomationRunItemReceiptV1(
                invocation.invocationId(), request.runRevision(),
                request.itemId(), request.itemSequence(), request.subjectRef(),
                "SUCCEEDED", request.attemptNumber(), request.acceptedAt(),
                request.acceptedAt().plusSeconds(1),
                "result:" + request.itemId(), DIGEST_D, null, false, null,
                invocation.correlationId());
    }

    private static AutomationRunItemReceiptV1 retryableFailureReceipt(
            AutomationInvocationV1 invocation,
            AutomationEffectRequest request) {
        return new AutomationRunItemReceiptV1(
                invocation.invocationId(), request.runRevision(),
                request.itemId(), request.itemSequence(), request.subjectRef(),
                "FAILED", request.attemptNumber(), request.acceptedAt(),
                request.acceptedAt().plusSeconds(1), null, null,
                "RETRYABLE_FAILURE", true,
                request.acceptedAt().plusSeconds(30),
                invocation.correlationId());
    }

    private static AutomationEffectRequest effectRequest(
            RuntimeRun run,
            AutomationExecutionRepository.PreparedItem item) {
        return new AutomationEffectRequest(
                item.itemPublicId(), item.itemSequence(), item.subjectRef(),
                run.run().objectRevision(), run.attemptNumber(), item.acceptedAt(),
                item.effectIdempotencyKey(), item.effectRequestDigest());
    }

    private void expireWorkerLease(
            GoldenFixture fixture, OffsetDateTime expiredAt) {
        expireWorkerLease(fixture, 1, expiredAt);
    }

    private void expireWorkerLease(
            GoldenFixture fixture, int attemptNumber,
            OffsetDateTime expiredAt) {
        jdbc.update("""
                UPDATE sys_automation_runs
                   SET worker_leased_until = ?
                 WHERE tenant_id = ? AND run_id = ?
                """, expiredAt, fixture.tenantId(), fixture.runId());
        jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET worker_leased_until = ?
                 WHERE tenant_id = ? AND run_id = ? AND attempt_number = ?
                """, expiredAt, fixture.tenantId(), fixture.runId(),
                attemptNumber);
    }

    private List<UUID> attemptedItems(PartialFixture fixture, int attempt) {
        return attemptedItems(fixture.tenantId(), fixture.runId(), attempt);
    }

    private List<UUID> attemptedItems(
            long tenantId, UUID runId, int attempt) {
        return jdbc.query("""
                SELECT i.public_id
                  FROM sys_automation_item_attempts a
                  JOIN sys_automation_run_items i
                    ON i.tenant_id = a.tenant_id
                   AND i.run_id = a.run_id
                   AND i.run_item_id = a.run_item_id
                 WHERE a.tenant_id = ? AND a.run_id = ?
                   AND a.attempt_ordinal = ?
                 ORDER BY i.public_id
                """, (rs, row) -> rs.getObject("public_id", UUID.class),
                tenantId, runId, attempt);
    }

    private Map<String, Object> itemEvidence(
            PartialFixture fixture, UUID itemPublicId) {
        return jdbc.queryForMap("""
                SELECT lifecycle_state, effect_state, attempt_count
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                """, fixture.tenantId(), fixture.runId(), itemPublicId);
    }

    private int checkpointCount(PartialFixture fixture) {
        return checkpointCount(fixture.tenantId(), fixture.runId());
    }

    private int checkpointCount(long tenantId, UUID runId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_checkpoints
                 WHERE tenant_id = ? AND run_id = ?
                """, Integer.class, tenantId, runId);
    }

    private int itemAttemptCount(
            GoldenFixture fixture, UUID itemPublicId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_item_attempts a
                  JOIN sys_automation_run_items i
                    ON i.tenant_id = a.tenant_id
                   AND i.run_id = a.run_id
                   AND i.run_item_id = a.run_item_id
                 WHERE a.tenant_id = ? AND a.run_id = ? AND i.public_id = ?
                """, Integer.class, fixture.tenantId(), fixture.runId(),
                itemPublicId);
    }

    private int runAttemptCount(PartialFixture fixture) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_attempts
                 WHERE tenant_id = ? AND run_id = ?
                """, Integer.class, fixture.tenantId(), fixture.runId());
    }

    private Throwable rollbackFailure(Runnable operation) {
        return catchThrowable(() -> transactions.executeWithoutResult(status -> {
            try {
                operation.run();
            } finally {
                status.setRollbackOnly();
            }
        }));
    }

    private static String selectionDigest(List<UUID> itemIds) {
        String material = itemIds.stream().map(UUID::toString).sorted()
                .collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
        return digest(material);
    }

    private static String digest(String material) {
        return AutomationDomainPolicy.sha256(
                material.getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime databaseNow() {
        return jdbc.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class);
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static final class RecordingHandler
            implements TypedAutomationHandlerPort {
        private final HandlerIdentity identity;
        private final AtomicInteger invocations = new AtomicInteger();

        private RecordingHandler(HandlerIdentity identity) {
            this.identity = identity;
        }

        @Override
        public HandlerIdentity identity() {
            return identity;
        }

        @Override
        public List<AutomationItemPlan> materialize(
                AutomationInvocationV1 invocation) {
            throw new UnsupportedOperationException(
                    "materialization is persisted before this evidence boundary");
        }

        @Override
        public List<AutomationRunItemReceiptV1> invoke(
                AutomationInvocationV1 invocation,
                List<AutomationEffectRequest> selectedItems) {
            invocations.incrementAndGet();
            return selectedItems.stream()
                    .map(item -> successfulReceipt(invocation, item))
                    .toList();
        }

        int invocations() {
            return invocations.get();
        }
    }

    private record GoldenFixture(
            long tenantId,
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            UUID runId,
            UUID runPublicId,
            UUID actor,
            UUID subject) {
    }

    private record PartialFixture(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            UUID subject,
            UUID successfulItem,
            UUID retryableItem,
            UUID finalFailedItem) {
    }

    private record Golden051Checkpoint(
            GoldenFixture fixture,
            UUID successfulItem,
            UUID retryableItem) {
    }

    private record Golden051Crash(
            Golden051Checkpoint checkpointed,
            LeaseGrant worker) {
    }
}
