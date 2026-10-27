package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedOriginal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RetrySelection;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RunRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationItemPlan;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.ExternalEffectEvidencePort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.ReconciliationEvidence;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
class AutomationGoldenExecutionEvidenceTest extends AutomationGoldenExecutionFixture {

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
}
