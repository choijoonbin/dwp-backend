package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class Hrm013PayrollDrainContinuationCallbackTest {
    @Test
    void drainOutcomeUsesTheFrozenRootAndDeterministicTerminalChild() {
        long tenantId = 41L;
        Instant occurred = Instant.parse("2026-10-27T04:00:00Z");
        Instant rootAt = Instant.parse("2026-10-26T23:00:00Z");
        UUID root = UUID.fromString("1c36c59e-b557-4f39-9f13-eb8be42063e2");
        String rootDigest = "a".repeat(64);
        var dispatch = Hrm013PayrollCallbackAuthorityTest.dispatch(tenantId);
        byte[] exact = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, dispatch);
        var store = mock(Hrm013PayrollTaskDispatchStore.class);
        when(store.dispatch(tenantId, dispatch.dispatchEventId())).thenReturn(Optional.of(
                Hrm013PayrollCallbackAuthorityTest.record(tenantId, dispatch, exact,
                        Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                        root, rootAt, rootDigest)));
        var service = new Hrm013PayrollTaskDispatchService(store,
                Clock.fixed(occurred, ZoneOffset.UTC));

        service.recordOutcome(tenantId, dispatch.dispatchEventId(), "COMPLETED",
                null, occurred);

        ArgumentCaptor<Hrm013PayrollTaskDispatchStore.Outcome> captured =
                ArgumentCaptor.forClass(Hrm013PayrollTaskDispatchStore.Outcome.class);
        org.mockito.Mockito.verify(store).recordOutcome(captured.capture());
        var outcome = captured.getValue();
        assertThat(outcome.callbackEventId()).isEqualTo(
                Hrm013WorkloadProofV1.terminalChildEventId(tenantId, root, 1,
                        Hrm013SeparationBridgeV1.HRM_008_PAYROLL.bindingId()));
        assertThat(outcome.drainRootWorkItemId()).isEqualTo(root);
        assertThat(outcome.drainRootCreatedAt()).isEqualTo(rootAt);
        assertThat(outcome.drainRootPayloadSha256()).isEqualTo(rootDigest);
        assertThat(Hrm013SeparationBridgeV1.decodeTaskReceipt(
                outcome.exactCallbackBytes()).status()).isEqualTo("COMPLETED");
    }
}
