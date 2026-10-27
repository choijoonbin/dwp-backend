package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.CompensationClaim;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandReservation;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderItemEvidence;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationRuntimeServiceTest extends IntegrationRuntimeFixture {

    @Test
    void dispatchCommitsClaimBeforeProviderAndCarriesFrozenTypedBoundary()
            throws Exception {
        Authority authority = authority(1L, "LEGAL_ENTITY", LEGAL_ENTITY);
        ExecutionRecord accepted = execution(
                ExecutionState.ACCEPTED, 1, false,
                "LEGAL_ENTITY", LEGAL_ENTITY);
        ExecutionRecord dispatched = execution(
                ExecutionState.DISPATCHED, 3, true,
                "LEGAL_ENTITY", LEGAL_ENTITY);
        ExecutionRecord acknowledged = execution(
                ExecutionState.ACKNOWLEDGED, 4, true,
                "LEGAL_ENTITY", LEGAL_ENTITY);
        AttemptRecord attempt = attempt();
        ExecutionResponse response = response(ExecutionState.ACKNOWLEDGED, 4);
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(accepted, dispatched);
        when(executions.beginDispatch(accepted, accepted.attemptFence(), NOW))
                .thenReturn(dispatched);
        when(executions.currentAttempts(dispatched)).thenReturn(List.of(attempt));
        when(provider.dispatch(any())).thenReturn(evidence(
                ProviderEvidence.Outcome.ACKNOWLEDGED, PROVIDER_KEY,
                REQUEST_DIGEST, true));
        when(executions.applyProviderEvidence(
                eq(dispatched), any(), eq(ExecutionState.ACKNOWLEDGED), eq(NOW)))
                .thenReturn(acknowledged);
        when(repository.executionResponse(TENANT, EXECUTION)).thenReturn(response);

        assertThat(service.dispatch(authority, EXECUTION)).isEqualTo(response);

        InOrder order = inOrder(transactionManager, executions, provider);
        order.verify(transactionManager).getTransaction(any());
        order.verify(executions).beginDispatch(accepted, 1L, NOW);
        order.verify(transactionManager).commit(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(provider).dispatch(any());
        order.verify(transactionManager).commit(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(executions).applyProviderEvidence(
                eq(dispatched), any(), eq(ExecutionState.ACKNOWLEDGED), eq(NOW));
        order.verify(transactionManager).commit(any());

        ArgumentCaptor<IntegrationRuntimePorts.ProviderRequest> request =
                ArgumentCaptor.forClass(IntegrationRuntimePorts.ProviderRequest.class);
        verify(provider).dispatch(request.capture());
        assertThat(request.getValue().execution().scopeType())
                .isEqualTo("LEGAL_ENTITY");
        assertThat(request.getValue().execution().scopeId())
                .isEqualTo(LEGAL_ENTITY);
        assertThat(request.getValue().execution().idempotencyKey())
                .isEqualTo("owner-command-key-0001");
        assertThat(request.getValue().execution().mode()).isEqualTo("EXECUTE");
        assertThat(request.getValue().execution().direction())
                .isEqualTo("OUTBOUND");
        assertThat(request.getValue().providerIdempotencyKey())
                .isEqualTo(PROVIDER_KEY);
        assertThat(request.getValue().selectedAttempts())
                .extracting(IntegrationRuntimePorts.ProviderItemAttempt::itemPublicId)
                .containsExactly(ITEM);
        assertThat(request.getValue().endpoint()).isEqualTo(
                URI.create("https://api.example.com/hris"));
        assertThat(request.getValue().secretHandle().handleId())
                .isEqualTo("secret-handle");
        verify(repository).markOriginalReceiptOutcome(
                acknowledged, ReceiptStatus.SUCCEEDED, NOW);
        verifyNoInteractions(operations);
    }

    @Test
    void mismatchedDispatchEvidenceBecomesDurableUnknownAndRecordsException() {
        Authority authority = authority(1L, "TENANT", null);
        ExecutionRecord accepted = execution(
                ExecutionState.ACCEPTED, 1, false, "TENANT", null);
        ExecutionRecord dispatched = execution(
                ExecutionState.DISPATCHED, 3, true, "TENANT", null);
        ExecutionRecord unknown = execution(
                ExecutionState.RESULT_UNKNOWN, 4, true, "TENANT", null);
        AttemptRecord attempt = attempt();
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(accepted, dispatched);
        when(executions.beginDispatch(accepted, 1L, NOW)).thenReturn(dispatched);
        when(executions.currentAttempts(dispatched)).thenReturn(List.of(attempt));
        when(provider.dispatch(any())).thenReturn(evidence(
                ProviderEvidence.Outcome.ACKNOWLEDGED,
                "wrong-provider-key-0001", REQUEST_DIGEST, true));
        when(executions.applyProviderEvidence(
                eq(dispatched), any(), eq(ExecutionState.RESULT_UNKNOWN), eq(NOW)))
                .thenReturn(unknown);
        when(repository.executionResponse(TENANT, EXECUTION))
                .thenReturn(response(ExecutionState.RESULT_UNKNOWN, 4));

        service.dispatch(authority, EXECUTION);

        ArgumentCaptor<ProviderEvidence> persisted =
                ArgumentCaptor.forClass(ProviderEvidence.class);
        verify(executions).applyProviderEvidence(eq(dispatched),
                persisted.capture(), eq(ExecutionState.RESULT_UNKNOWN), eq(NOW));
        assertThat(persisted.getValue().outcome())
                .isEqualTo(ProviderEvidence.Outcome.RESULT_UNKNOWN);
        assertThat(persisted.getValue().providerIdempotencyKey())
                .isEqualTo(PROVIDER_KEY);
        assertThat(persisted.getValue().items())
                .extracting(ProviderItemEvidence::itemPublicId)
                .containsExactly(ITEM);
        verify(operations).record(argThat(exception ->
                exception.category().equals("PROVIDER_EVIDENCE_UNBOUND")
                        && exception.executionId().equals(EXECUTION)));
        verify(repository).markOriginalReceiptOutcome(
                unknown, ReceiptStatus.RESULT_UNKNOWN, NOW);
    }

    @Test
    void reconcileReplayReturnsReceiptBeforeReadingMutableExecution() {
        Authority authority = authority(7L, "TENANT", null);
        ReconcileCommand command = new ReconcileCommand(
                "ref_providerreceipt0001", DIGEST, 1, NOW);
        CommandReceipt running = receipt(ReceiptStatus.SUCCEEDED, 7L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(running, false));

        assertThat(service.reconcile(authority, EXECUTION, command))
                .isSameAs(running);

        verify(repository).beginCommand(any(), eq(NOW));
        verify(repository, never()).lockExecution(anyLong(), any());
        verifyNoInteractions(executions, provider, operations);
    }

    @Test
    void reconciliationClaimCommitsBeforeEffectAndFinalizesTerminalEvidence() {
        Authority authority = authority(3L, "TENANT", null);
        ReconcileCommand command = new ReconcileCommand(
                "ref_providerreceipt0001", DIGEST, 1, NOW);
        ExecutionRecord acknowledged = execution(
                ExecutionState.ACKNOWLEDGED, 3, true, "TENANT", null);
        ExecutionRecord reconciling = execution(
                ExecutionState.RECONCILING, 4, true, "TENANT", null);
        ExecutionRecord reconciled = execution(
                ExecutionState.RECONCILED, 5, true, "TENANT", null);
        AttemptRecord attempt = attempt();
        CommandReceipt accepted = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(ReceiptStatus.RUNNING, 4L);
        CommandReceipt succeeded = receipt(ReceiptStatus.SUCCEEDED, 5L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(acknowledged, reconciling);
        when(executions.currentAttempts(acknowledged))
                .thenReturn(List.of(attempt));
        when(executions.beginReconciliation(
                acknowledged, command.providerReceiptRef(),
                command.providerReceiptDigest(), NOW)).thenReturn(reconciling);
        when(repository.progressCommand(any(), eq(accepted.receiptId()),
                eq(EXECUTION), eq(4L), anyString(), eq(NOW))).thenReturn(running);
        when(provider.reconcile(any())).thenReturn(evidence(
                ProviderEvidence.Outcome.RECONCILED,
                PROVIDER_KEY, REQUEST_DIGEST, true));
        when(executions.applyProviderEvidence(
                reconciling, evidence(ProviderEvidence.Outcome.RECONCILED,
                        PROVIDER_KEY, REQUEST_DIGEST, true),
                ExecutionState.RECONCILED, NOW)).thenReturn(reconciled);
        when(repository.finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(EXECUTION), eq(5L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        assertThat(service.reconcile(authority, EXECUTION, command))
                .isSameAs(succeeded);

        ArgumentCaptor<IntegrationRuntimePorts.ReconcileRequest> request =
                ArgumentCaptor.forClass(
                        IntegrationRuntimePorts.ReconcileRequest.class);
        verify(provider).reconcile(request.capture());
        assertThat(request.getValue().providerReceiptHandle().handleId())
                .isEqualTo("provider-receipt-handle");
        assertThat(request.getValue().providerReceiptDigest())
                .isEqualTo(DIGEST);

        InOrder order = inOrder(transactionManager, repository, provider);
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).beginCommand(any(), eq(NOW));
        order.verify(repository).lockExecution(TENANT, EXECUTION);
        order.verify(transactionManager).commit(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(provider).reconcile(any());
        order.verify(transactionManager).commit(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).lockExecution(TENANT, EXECUTION);
        order.verify(repository).finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(EXECUTION), eq(5L),
                anyString(), eq(NOW));
        order.verify(transactionManager).commit(any());
        verifyNoInteractions(operations);
    }

    @Test
    void retryReservesReceiptBeforeStateAndUsesExactFailedOnlySet() {
        Authority authority = authority(3L, "TENANT", null);
        ExecutionRecord failed = execution(
                ExecutionState.FAILED, 3, true, "TENANT", null);
        ExecutionRecord acceptedRetry = execution(
                ExecutionState.ACCEPTED, 4, true, "TENANT", null);
        String failedSetDigest = IntegrationDomainPolicy.sha256(
                List.of(ITEM).toString());
        RetryCommand command = new RetryCommand(
                List.of(ITEM), failedSetDigest, 1, "TRANSIENT_PROVIDER");
        CommandReceipt accepted = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt succeeded = receipt(ReceiptStatus.SUCCEEDED, 4L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockExecution(TENANT, EXECUTION)).thenReturn(failed);
        when(executions.retryableItemIds(failed)).thenReturn(List.of(ITEM));
        when(executions.createRetryAttempts(
                failed, List.of(ITEM), failedSetDigest,
                authority.actorId(), NOW)).thenReturn(acceptedRetry);
        when(repository.finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(EXECUTION), eq(4L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        assertThat(service.retry(authority, EXECUTION, command))
                .isSameAs(succeeded);

        InOrder order = inOrder(repository, executions);
        order.verify(repository).beginCommand(any(), eq(NOW));
        order.verify(repository).lockExecution(TENANT, EXECUTION);
        order.verify(executions).retryableItemIds(failed);
        order.verify(executions).createRetryAttempts(
                failed, List.of(ITEM), failedSetDigest,
                authority.actorId(), NOW);
        verifyNoInteractions(provider, operations);
    }

    @Test
    void retryBudgetExhaustionAtomicallyDeadLettersWithoutNewAttempt() {
        Authority authority = authority(3L, "TENANT", null);
        ExecutionRecord failed = execution(
                ExecutionState.FAILED, 3, true, "TENANT", null, 3);
        ExecutionRecord deadLetter = execution(
                ExecutionState.DEAD_LETTER, 4, true, "TENANT", null, 3);
        String failedSetDigest = IntegrationDomainPolicy.sha256(
                List.of(ITEM).toString());
        RetryCommand command = new RetryCommand(
                List.of(ITEM), failedSetDigest, 1, "BUDGET_EXHAUSTED");
        CommandReceipt accepted = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt succeeded = receipt(ReceiptStatus.SUCCEEDED, 4L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockExecution(TENANT, EXECUTION)).thenReturn(failed);
        when(executions.retryableItemIds(failed)).thenReturn(List.of(ITEM));
        when(executions.deadLetter(failed, authority.actorId(), NOW))
                .thenReturn(deadLetter);
        when(repository.finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(EXECUTION), eq(4L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        assertThat(service.retry(authority, EXECUTION, command))
                .isSameAs(succeeded);

        verify(executions).deadLetter(failed, authority.actorId(), NOW);
        verify(executions, never()).createRetryAttempts(
                any(), anyList(), anyString(), anyLong(), any());
        verify(repository).appendExecutionEventAndAudit(
                any(), eq(deadLetter), eq("EXECUTION_DEAD_LETTER"), eq(NOW));
    }

    @Test
    void nonRetryableProviderFailureAutoDeadLetters() {
        Authority authority = authority(1L, "TENANT", null);
        ExecutionRecord accepted = execution(
                ExecutionState.ACCEPTED, 1, false, "TENANT", null);
        ExecutionRecord dispatched = execution(
                ExecutionState.DISPATCHED, 3, true, "TENANT", null);
        ExecutionRecord failed = execution(
                ExecutionState.FAILED, 4, true, "TENANT", null);
        ExecutionRecord deadLetter = execution(
                ExecutionState.DEAD_LETTER, 5, true, "TENANT", null);
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(accepted, dispatched);
        when(executions.beginDispatch(accepted, 1L, NOW)).thenReturn(dispatched);
        when(executions.currentAttempts(dispatched)).thenReturn(List.of(attempt()));
        ProviderEvidence evidence = failureEvidence(false);
        when(provider.dispatch(any())).thenReturn(evidence);
        when(executions.applyProviderEvidence(
                dispatched, evidence, ExecutionState.FAILED, NOW))
                .thenReturn(failed);
        when(executions.retryableItemIds(failed)).thenReturn(List.of());
        when(executions.deadLetter(failed, authority.actorId(), NOW))
                .thenReturn(deadLetter);
        when(repository.executionResponse(TENANT, EXECUTION))
                .thenReturn(response(ExecutionState.DEAD_LETTER, 5));

        service.dispatch(authority, EXECUTION);

        verify(executions).deadLetter(failed, authority.actorId(), NOW);
        verify(repository).markOriginalReceiptOutcome(
                deadLetter, ReceiptStatus.FAILED, NOW);
        verify(repository).appendExecutionEventAndAudit(
                any(), eq(deadLetter), eq("PROVIDER_DEAD_LETTER"), eq(NOW));
    }

    @Test
    void retryableProviderFailureRemainsFailedForExplicitRetry() {
        Authority authority = authority(1L, "TENANT", null);
        ExecutionRecord accepted = execution(
                ExecutionState.ACCEPTED, 1, false, "TENANT", null);
        ExecutionRecord dispatched = execution(
                ExecutionState.DISPATCHED, 3, true, "TENANT", null);
        ExecutionRecord failed = execution(
                ExecutionState.FAILED, 4, true, "TENANT", null);
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(accepted, dispatched);
        when(executions.beginDispatch(accepted, 1L, NOW)).thenReturn(dispatched);
        when(executions.currentAttempts(dispatched)).thenReturn(List.of(attempt()));
        ProviderEvidence evidence = failureEvidence(true);
        when(provider.dispatch(any())).thenReturn(evidence);
        when(executions.applyProviderEvidence(
                dispatched, evidence, ExecutionState.FAILED, NOW))
                .thenReturn(failed);
        when(executions.retryableItemIds(failed)).thenReturn(List.of(ITEM));
        when(repository.executionResponse(TENANT, EXECUTION))
                .thenReturn(response(ExecutionState.FAILED, 4));

        service.dispatch(authority, EXECUTION);

        verify(executions, never()).deadLetter(any(), anyLong(), any());
        verify(repository).markOriginalReceiptOutcome(
                failed, ReceiptStatus.FAILED, NOW);
        verify(repository).appendExecutionEventAndAudit(
                any(), eq(failed), eq("PROVIDER_FAILED"), eq(NOW));
    }

    @Test
    void preDispatchCancellationSettlesTheOriginalExecutionReceipt() {
        Authority authority = authority(1L, "TENANT", null);
        CancelCommand command = new CancelCommand("OWNER_REQUEST", null);
        ExecutionRecord acceptedExecution = execution(
                ExecutionState.ACCEPTED, 1, false, "TENANT", null);
        ExecutionRecord cancelled = execution(
                ExecutionState.CANCELLED, 2, false, "TENANT", null);
        CommandReceipt acceptedReceipt = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt succeeded = receipt(ReceiptStatus.SUCCEEDED, 2L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(acceptedReceipt, true));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(acceptedExecution);
        when(executions.cancel(acceptedExecution, null, null, null, null,
                authority.actorId(), NOW)).thenReturn(cancelled);
        when(repository.finishCommand(any(), eq(acceptedReceipt.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(EXECUTION), eq(2L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        assertThat(service.cancel(authority, EXECUTION, command))
                .isSameAs(succeeded);

        verify(repository).markOriginalReceiptOutcome(
                cancelled, ReceiptStatus.FAILED, NOW);
        verifyNoInteractions(provider, operations);
    }

    @Test
    void compensationUsesItsOwnIdempotencyKeyAndCommandDigest() {
        Authority authority = authority(3L, "TENANT", null);
        CancelCommand command = new CancelCommand(
                "OWNER_REQUEST", "policy_abcdefghijklmnop");
        ExecutionRecord current = execution(
                ExecutionState.ACKNOWLEDGED, 3, true, "TENANT", null);
        ExecutionRecord claimed = execution(
                ExecutionState.ACKNOWLEDGED, 4, true, "TENANT", null);
        ExecutionRecord cancelled = execution(
                ExecutionState.CANCELLED, 5, true, "TENANT", null);
        AttemptRecord attempt = attempt();
        CommandReceipt accepted = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(ReceiptStatus.RUNNING, 4L);
        CommandReceipt succeeded = receipt(ReceiptStatus.SUCCEEDED, 5L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(current, claimed);
        when(executions.currentAttempts(current)).thenReturn(List.of(attempt));
        when(executions.claimCompensation(
                eq(current), anyString(), eq(3L), eq(NOW)))
                .thenAnswer(invocation -> new CompensationClaim(
                        claimed, invocation.getArgument(1), true));
        when(repository.progressCommand(any(), eq(accepted.receiptId()),
                eq(EXECUTION), eq(4L), anyString(), eq(NOW))).thenReturn(running);
        when(provider.compensate(any())).thenAnswer(invocation -> {
            IntegrationRuntimePorts.CompensationRequest request = invocation.getArgument(0);
            return new ProviderEvidence(
                    ProviderEvidence.Outcome.CANCELLED,
                    request.compensationIdempotencyKey(),
                    request.commandRequestDigest(),
                    "ref_compensation0001", DIGEST,
                    1, 0, 0, 0, null, NOW, List.of());
        });
        when(executions.cancel(eq(claimed), eq(command.compensationPolicyRef()),
                anyString(), eq("ref_compensation0001"), eq(DIGEST),
                eq(authority.actorId()), eq(NOW))).thenReturn(cancelled);
        when(repository.finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(EXECUTION), eq(5L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        assertThat(service.cancel(authority, EXECUTION, command))
                .isSameAs(succeeded);

        ArgumentCaptor<IntegrationRuntimePorts.CompensationRequest> request =
                ArgumentCaptor.forClass(
                        IntegrationRuntimePorts.CompensationRequest.class);
        verify(provider).compensate(request.capture());
        assertThat(request.getValue().compensationIdempotencyKey())
                .startsWith("sys008-comp-")
                .isNotEqualTo(ITEM_KEY);
        assertThat(request.getValue().commandRequestDigest())
                .matches("^[a-f0-9]{64}$");
        assertThat(request.getValue().compensationPolicyHandle().handleId())
                .isEqualTo("compensation-policy-handle");
        assertThat(request.getValue().compensationPolicyDigest())
                .matches("^[a-f0-9]{64}$");
        assertThat(request.getValue().original().execution().idempotencyKey())
                .isEqualTo("owner-command-key-0001");
        verify(repository).markOriginalReceiptOutcome(
                cancelled, ReceiptStatus.FAILED, NOW);
        verifyNoInteractions(operations);
    }

    @Test
    void compensationFinalizerRejectsARevisionThatMovedAfterTheGlobalClaim() {
        Authority authority = authority(3L, "TENANT", null);
        CancelCommand command = new CancelCommand(
                "OWNER_REQUEST", "policy_abcdefghijklmnop");
        ExecutionRecord current = execution(
                ExecutionState.ACKNOWLEDGED, 3, true, "TENANT", null);
        ExecutionRecord claimed = execution(
                ExecutionState.ACKNOWLEDGED, 4, true, "TENANT", null);
        ExecutionRecord moved = execution(
                ExecutionState.ACKNOWLEDGED, 5, true, "TENANT", null);
        CommandReceipt accepted = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(ReceiptStatus.RUNNING, 4L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(current, moved);
        when(executions.currentAttempts(current)).thenReturn(List.of(attempt()));
        when(executions.claimCompensation(
                eq(current), anyString(), eq(3L), eq(NOW)))
                .thenAnswer(invocation -> new CompensationClaim(
                        claimed, invocation.getArgument(1), true));
        when(repository.progressCommand(any(), eq(accepted.receiptId()),
                eq(EXECUTION), eq(4L), anyString(), eq(NOW))).thenReturn(running);
        when(provider.compensate(any())).thenAnswer(invocation -> {
            IntegrationRuntimePorts.CompensationRequest request =
                    invocation.getArgument(0);
            return new ProviderEvidence(
                    ProviderEvidence.Outcome.CANCELLED,
                    request.compensationIdempotencyKey(),
                    request.commandRequestDigest(),
                    "ref_compensation0001", DIGEST,
                    1, 0, 0, 0, null, NOW, List.of());
        });

        assertThatThrownBy(() -> service.cancel(authority, EXECUTION, command))
                .hasMessageContaining(
                        "SYS008_COMPENSATION_FINALIZER_FENCE_CONFLICT");

        verify(executions, never()).cancel(any(), anyString(), anyString(),
                anyString(), anyString(), anyLong(), any());
        verify(repository, never()).finishCommand(any(), any(), any(), any(),
                anyLong(), anyString(), any());
        verifyNoInteractions(operations);
    }

    @Test
    void secondPrincipalObservesExecutionGlobalCompensationWinner() {
        Authority authority = authority(
                3L, "TENANT", null, "second-principal-cancel-key");
        CancelCommand command = new CancelCommand(
                "OWNER_REQUEST", "policy_abcdefghijklmnop");
        ExecutionRecord alreadyClaimed = execution(
                ExecutionState.ACKNOWLEDGED, 4, true, "TENANT", null);
        CommandReceipt accepted = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(ReceiptStatus.RUNNING, 4L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(alreadyClaimed);
        when(executions.currentAttempts(alreadyClaimed))
                .thenReturn(List.of(attempt()));
        when(executions.claimCompensation(
                eq(alreadyClaimed), anyString(), eq(3L), eq(NOW)))
                .thenAnswer(invocation -> new CompensationClaim(
                        alreadyClaimed, invocation.getArgument(1), false));
        when(repository.progressCommand(any(), eq(accepted.receiptId()),
                eq(EXECUTION), eq(4L), anyString(), eq(NOW)))
                .thenReturn(running);

        assertThat(service.cancel(authority, EXECUTION, command))
                .isSameAs(running);

        verifyNoInteractions(provider, operations);
        verify(repository, never()).appendExecutionEventAndAudit(
                any(), any(), eq("COMPENSATION_CLAIMED"), any());
        ArgumentCaptor<CommandSeal> receiptSeal =
                ArgumentCaptor.forClass(CommandSeal.class);
        verify(repository).beginCommand(receiptSeal.capture(), eq(NOW));
        assertThat(receiptSeal.getValue().idempotencyKey())
                .isEqualTo("second-principal-cancel-key");
    }

    @Test
    void staleRunningReconciliationBecomesDurableUnknownWithoutProviderRecall() {
        Authority authority = authority(3L, "TENANT", null);
        ReconcileCommand command = new ReconcileCommand(
                "ref_providerreceipt0001", DIGEST, 1, NOW);
        ExecutionRecord reconciling = execution(
                ExecutionState.RECONCILING, 4, true, "TENANT", null);
        ExecutionRecord unknown = execution(
                ExecutionState.RESULT_UNKNOWN, 5, true, "TENANT", null);
        CommandReceipt running = receiptAt(
                ReceiptStatus.RUNNING, 4L, NOW.minusMinutes(6));
        CommandReceipt resultUnknown = receipt(ReceiptStatus.RESULT_UNKNOWN, 5L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(running, false));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(reconciling, reconciling);
        when(executions.currentAttempts(reconciling))
                .thenReturn(List.of(attempt()));
        when(executions.applyProviderEvidence(
                eq(reconciling), any(), eq(ExecutionState.RESULT_UNKNOWN), eq(NOW)))
                .thenReturn(unknown);
        when(repository.finishCommand(any(), eq(running.receiptId()),
                eq(ReceiptStatus.RESULT_UNKNOWN), eq(EXECUTION), eq(5L),
                anyString(), eq(NOW))).thenReturn(resultUnknown);

        assertThat(service.reconcile(authority, EXECUTION, command))
                .isSameAs(resultUnknown);

        verifyNoInteractions(provider);
        verify(operations).record(argThat(exception ->
                exception.category().equals("STALE_RECONCILIATION_CLAIM")));
        verify(executions).applyProviderEvidence(
                eq(reconciling), any(), eq(ExecutionState.RESULT_UNKNOWN), eq(NOW));
    }

    @Test
    void staleRunningCompensationOnFailedExecutionIsDurableWithoutProviderRecall() {
        Authority authority = authority(3L, "TENANT", null);
        CancelCommand command = new CancelCommand(
                "OWNER_REQUEST", "policy_abcdefghijklmnop");
        ExecutionRecord claimed = execution(
                ExecutionState.FAILED, 4, true, "TENANT", null);
        ExecutionRecord durableUnknown = execution(
                ExecutionState.FAILED, 5, true, "TENANT", null);
        CommandReceipt running = receiptAt(
                ReceiptStatus.RUNNING, 4L, NOW.minusMinutes(6));
        CommandReceipt resultUnknown = receipt(ReceiptStatus.RESULT_UNKNOWN, 5L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(running, false));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(claimed, claimed);
        when(executions.currentAttempts(claimed)).thenReturn(List.of(attempt()));
        when(executions.markCompensationResultUnknown(
                eq(claimed), anyString(), eq(NOW))).thenReturn(durableUnknown);
        when(repository.finishCommand(any(), eq(running.receiptId()),
                eq(ReceiptStatus.RESULT_UNKNOWN), eq(EXECUTION), eq(5L),
                anyString(), eq(NOW))).thenReturn(resultUnknown);

        assertThat(service.cancel(authority, EXECUTION, command))
                .isSameAs(resultUnknown);

        verifyNoInteractions(provider);
        ArgumentCaptor<IntegrationRuntimePorts.OperationsException> exception =
                ArgumentCaptor.forClass(
                        IntegrationRuntimePorts.OperationsException.class);
        verify(operations).record(exception.capture());
        verify(executions).markCompensationResultUnknown(
                claimed, exception.getValue().evidenceReference(), NOW);
        verify(repository).appendExecutionEventAndAudit(
                any(), eq(durableUnknown),
                eq("COMPENSATION_RESULT_UNKNOWN"), eq(NOW));
        verify(repository).markOriginalReceiptOutcome(
                durableUnknown, ReceiptStatus.RESULT_UNKNOWN, NOW);
    }

    @Test
    void currentLegalEntityAuthorityIsRequiredBeforeAnyProviderEffect() {
        UUID otherLegalEntity = UUID.fromString(
                "80000000-0000-4000-8000-000000000099");
        Authority authority = authority(1L, "LEGAL_ENTITY", otherLegalEntity);
        when(executions.lockExecutionAuthorized(
                TENANT, EXECUTION, OTHER_DIGEST, "LEGAL_ENTITY", otherLegalEntity))
                .thenThrow(new IllegalStateException("SYS008_EXECUTION_NOT_FOUND"));

        assertThatThrownBy(() -> service.dispatch(authority, EXECUTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SYS008_EXECUTION_NOT_FOUND");

        verify(executions).lockExecutionAuthorized(
                TENANT, EXECUTION, OTHER_DIGEST, "LEGAL_ENTITY", otherLegalEntity);
        verifyNoInteractions(provider, operations);
    }

    @Test
    void everyFrozenDefinitionAndMappingDriftFailsClosedBeforeProvider()
            throws Exception {
        var definition = definitionBoundary();
        var mapping = mappingBoundary();
        List<BoundaryCase> cases = List.of(
                new BoundaryCase("endpoint",
                        definitionBoundary(URI.create(
                                "https://other.example.com/hris"),
                                definition.secretRef(), definition.egressPolicyKey(),
                                definition.endpointPolicyRevision(),
                                definition.secretRevision()), mapping),
                new BoundaryCase("secretRef",
                        definitionBoundary(definition.endpoint(),
                                "secret_bbbbbbbbbbbbbbbb",
                                definition.egressPolicyKey(),
                                definition.endpointPolicyRevision(),
                                definition.secretRevision()), mapping),
                new BoundaryCase("egressPolicyKey",
                        definitionBoundary(definition.endpoint(),
                                definition.secretRef(), "other-egress",
                                definition.endpointPolicyRevision(),
                                definition.secretRevision()), mapping),
                new BoundaryCase("endpointPolicyRevision",
                        definitionBoundary(definition.endpoint(),
                                definition.secretRef(), definition.egressPolicyKey(),
                                70, definition.secretRevision()), mapping),
                new BoundaryCase("secretRevision",
                        definitionBoundary(definition.endpoint(),
                                definition.secretRef(), definition.egressPolicyKey(),
                                definition.endpointPolicyRevision(), 80), mapping),
                new BoundaryCase("secretHandle",
                        definitionBoundary(definition.endpoint(),
                                definition.secretRef(), definition.egressPolicyKey(),
                                definition.endpointPolicyRevision(),
                                definition.secretRevision(), "other-secret-handle"),
                        mapping),
                new BoundaryCase("mappingRef", definition,
                        mappingBoundary("OUTBOUND", "ref_bbbbbbbbbbbbbbbb",
                                DIGEST, "mapping-handle")),
                new BoundaryCase("mappingDigest", definition,
                        mappingBoundary("OUTBOUND", "ref_abcdefghijklmnop",
                                OTHER_DIGEST, "mapping-handle")),
                new BoundaryCase("mappingDirection", definition,
                        mappingBoundary("INBOUND", "ref_abcdefghijklmnop",
                                DIGEST, "mapping-handle")),
                new BoundaryCase("sourceSchemaDigest", definition,
                        mappingBoundary("OUTBOUND", "ref_abcdefghijklmnop",
                                DIGEST, OTHER_DIGEST, OTHER_DIGEST,
                                "mapping-handle")),
                new BoundaryCase("targetSchemaDigest", definition,
                        mappingBoundary("OUTBOUND", "ref_abcdefghijklmnop",
                                DIGEST, DIGEST, DIGEST, "mapping-handle")),
                new BoundaryCase("mappingHandle", definition,
                        mappingBoundary("OUTBOUND", "ref_abcdefghijklmnop",
                                DIGEST, "other-mapping-handle")));

        for (BoundaryCase boundaryCase : cases) {
            initialize(boundary(boundaryCase.definition(),
                    boundaryCase.mapping()));
            Authority authority = authority(1L, "TENANT", null);
            ExecutionRecord accepted = execution(
                    ExecutionState.ACCEPTED, 1, false, "TENANT", null);
            ExecutionRecord dispatched = execution(
                    ExecutionState.DISPATCHED, 3, true, "TENANT", null);
            ExecutionRecord unknown = execution(
                    ExecutionState.RESULT_UNKNOWN, 4, true, "TENANT", null);
            when(repository.lockExecution(TENANT, EXECUTION))
                    .thenReturn(accepted, dispatched);
            when(executions.beginDispatch(accepted, 1L, NOW))
                    .thenReturn(dispatched);
            when(executions.currentAttempts(dispatched))
                    .thenReturn(List.of(attempt()));
            when(executions.applyProviderEvidence(
                    eq(dispatched), any(),
                    eq(ExecutionState.RESULT_UNKNOWN), eq(NOW)))
                    .thenReturn(unknown);
            when(repository.executionResponse(TENANT, EXECUTION))
                    .thenReturn(response(ExecutionState.RESULT_UNKNOWN, 4));

            ExecutionResponse result = service.dispatch(authority, EXECUTION);

            assertThat(result.state()).as(boundaryCase.name())
                    .isEqualTo(ExecutionState.RESULT_UNKNOWN);
            verify(provider, never()).dispatch(any());
            verify(operations).record(argThat(exception ->
                    exception.category().equals("PROVIDER_RESULT_UNKNOWN")));
        }
    }

    @Test
    void compensationKeyAndDigestDoNotDependOnCallerIdempotencyKey()
            throws Exception {
        IntegrationRuntimePorts.CompensationRequest first =
                invokeSuccessfulCompensation("caller-key-00000001");
        IntegrationRuntimePorts.CompensationRequest second =
                invokeSuccessfulCompensation("caller-key-00000002");

        assertThat(first.compensationIdempotencyKey())
                .isEqualTo(second.compensationIdempotencyKey());
        assertThat(first.commandRequestDigest())
                .isEqualTo(second.commandRequestDigest());
        assertThat(first.compensationPolicyHandle().handleId())
                .isEqualTo("compensation-policy-handle");
    }
}
