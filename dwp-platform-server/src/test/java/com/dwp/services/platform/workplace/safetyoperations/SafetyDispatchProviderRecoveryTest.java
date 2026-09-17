package com.dwp.services.platform.workplace.safetyoperations;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyDispatchRecoveryRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SafetyDispatchProviderRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void unknownOutcomeUsesProviderGetOnlyLookupWithoutRedispatch() {
        SafetyIncidentRepository incidents = mock(SafetyIncidentRepository.class);
        SafetyDispatchRecoveryRepository recovery = mock(SafetyDispatchRecoveryRepository.class);
        SafetyConnectorService connectors = mock(SafetyConnectorService.class);
        TransactionOperations transactions = immediateTransactions();
        ControlledProvider provider = new ControlledProvider();
        SafetyDispatchService service = new SafetyDispatchService(incidents, recovery,
                connectors, List.of(provider), transactions, CLOCK);
        UUID attemptId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        DispatchWorkRow dispatch = new DispatchWorkRow(attemptId, 42, DeliveryChannel.APP_PUSH,
                "a".repeat(64), 9L, incidentId, "Evacuate", "Use north stairs",
                Severity.CRITICAL);
        RecoveryWorkRow lookup = new RecoveryWorkRow(attemptId, 42, incidentId,
                DeliveryChannel.APP_PUSH, "provider-operation-7");
        when(incidents.claimDispatch(eq(42L), eq(attemptId), any())).thenReturn(true);
        when(incidents.recordOutcome(any())).thenReturn(true);
        when(recovery.claim(eq(lookup), any())).thenReturn(true);
        when(recovery.record(eq(lookup), any(), any())).thenReturn(true);

        assertThat(service.dispatch(dispatch)).isTrue();
        assertThat(service.reconcile(lookup)).isTrue();
        assertThat(provider.dispatchCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(1);
        verify(incidents).recordOutcome(argThat(outcome ->
                outcome.state() == AttemptState.RESULT_UNKNOWN
                        && "provider-operation-7".equals(outcome.providerOperationReference())));
        verify(recovery).record(eq(lookup), argThat(result ->
                result.state() == AttemptState.DELIVERED), any());
    }

    @Test
    void uncertainExceptionPreservesOperationReferenceForRecovery() {
        SafetyIncidentRepository incidents = mock(SafetyIncidentRepository.class);
        SafetyDispatchRecoveryRepository recovery = mock(SafetyDispatchRecoveryRepository.class);
        SafetyConnectorService connectors = mock(SafetyConnectorService.class);
        ControlledProvider provider = new ControlledProvider();
        provider.throwUnknown = true;
        SafetyDispatchService service = new SafetyDispatchService(incidents, recovery,
                connectors, List.of(provider), immediateTransactions(), CLOCK);
        UUID attemptId = UUID.randomUUID();
        when(incidents.claimDispatch(eq(42L), eq(attemptId), any())).thenReturn(true);
        when(incidents.recordOutcome(any())).thenReturn(true);

        service.dispatch(new DispatchWorkRow(attemptId, 42, DeliveryChannel.APP_PUSH,
                "b".repeat(64), 10L, UUID.randomUUID(), "message", "action", Severity.URGENT));

        verify(incidents).recordOutcome(argThat(outcome ->
                outcome.state() == AttemptState.RESULT_UNKNOWN
                        && "provider-operation-7".equals(outcome.providerOperationReference())));
        assertThat(provider.dispatchCalls).hasValue(1);
        assertThat(provider.lookupCalls).hasValue(0);
    }

    @Test
    void unavailableProviderDefersWithoutConsumingLookupAttemptBudget() {
        SafetyIncidentRepository incidents = mock(SafetyIncidentRepository.class);
        SafetyDispatchRecoveryRepository recovery = mock(SafetyDispatchRecoveryRepository.class);
        SafetyConnectorService connectors = mock(SafetyConnectorService.class);
        SafetyDispatchService service = new SafetyDispatchService(incidents, recovery,
                connectors, List.of(), immediateTransactions(), CLOCK);
        RecoveryWorkRow work = new RecoveryWorkRow(UUID.randomUUID(), 42, UUID.randomUUID(),
                DeliveryChannel.EMAIL, null, new SafetyDispatchProvider.ProviderContext(
                        DeliveryChannel.EMAIL, "MISSING", 3,
                        "secret-manager://workplace/missing/v3"));
        when(recovery.deferUnavailable(eq(work), any())).thenReturn(true);

        assertThat(service.reconcile(work)).isTrue();

        verify(recovery).deferUnavailable(eq(work), any());
        verify(recovery, never()).claim(any(), any());
    }

    @SuppressWarnings("unchecked")
    private static TransactionOperations immediateTransactions() {
        TransactionOperations operations = mock(TransactionOperations.class);
        when(operations.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0))
                        .doInTransaction(mock(TransactionStatus.class)));
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(operations).executeWithoutResult(any());
        return operations;
    }

    private static final class ControlledProvider implements SafetyDispatchProvider {
        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private final AtomicInteger lookupCalls = new AtomicInteger();
        private boolean throwUnknown;

        @Override
        public boolean supports(DeliveryChannel channel) {
            return channel == DeliveryChannel.APP_PUSH;
        }

        @Override
        public DispatchResult dispatch(DispatchRequest request) {
            dispatchCalls.incrementAndGet();
            if (throwUnknown) throw new OutcomeUnknownException(
                    "timeout after acceptance", "provider-operation-7");
            return new DispatchResult(AttemptState.RESULT_UNKNOWN,
                    "provider-operation-7", "TIMEOUT", "provider-accepted");
        }

        @Override
        public DispatchResult lookupStatus(LookupRequest request) {
            lookupCalls.incrementAndGet();
            return new DispatchResult(AttemptState.DELIVERED,
                    request.providerOperationReference(), "DELIVERED", "provider-receipt-7");
        }
    }
}
