package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class Hrm013PayrollCallbackAuthorityTest {
    @Test
    void callbackIsBoundToTheOriginalDispatchLineage() {
        long tenantId = 41L;
        Instant occurred = Instant.parse("2026-10-27T01:02:03Z");
        var dispatch = dispatch(tenantId);
        byte[] exact = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, dispatch);
        var store = mock(Hrm013PayrollTaskDispatchStore.class);
        when(store.dispatch(tenantId, dispatch.dispatchEventId())).thenReturn(Optional.of(
                record(tenantId, dispatch, exact,
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, null, null)));
        var service = new Hrm013PayrollTaskDispatchService(store,
                Clock.fixed(occurred, ZoneOffset.UTC));

        service.recordOutcome(tenantId, dispatch.dispatchEventId(), "FAILED",
                UUID.fromString("465058a1-38fb-43b9-8017-50edfd31fb0d"), occurred);

        ArgumentCaptor<Hrm013PayrollTaskDispatchStore.Outcome> captured =
                ArgumentCaptor.forClass(Hrm013PayrollTaskDispatchStore.Outcome.class);
        org.mockito.Mockito.verify(store).recordOutcome(captured.capture());
        var callback = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                captured.getValue().exactCallbackBytes());
        assertThat(callback.casePublicId()).isEqualTo(dispatch.casePublicId());
        assertThat(callback.taskPublicId()).isEqualTo(dispatch.taskPublicId());
        assertThat(callback.originatingPayloadSha256())
                .isEqualTo(dispatch.payloadSha256());
        Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                Hrm013TaskDispatchV1.PAYROLL, dispatch,
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL, callback);
    }

    static Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch(long tenantId) {
        UUID task = UUID.fromString("1af26e53-b11a-4dda-ac1c-6f9a8d39356c");
        return Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                        Hrm013TaskDispatchV1.SCHEMA_NAME,
                        Hrm013TaskDispatchV1.SCHEMA_VERSION,
                        Hrm013TaskDispatchV1.dispatchEventId(tenantId, task, 3,
                                Hrm013TaskDispatchV1.PAYROLL.bindingId()),
                        UUID.fromString("837c3b63-b54b-49f0-9422-f6e3820502bb"), task,
                        Hrm013TaskDispatchV1.PAYROLL.taskType(), 1, 3,
                        Instant.parse("2026-10-27T00:00:00Z"), "0".repeat(64),
                        UUID.fromString("d6990436-76cf-4eb7-b97d-a98da96979fa"),
                        UUID.fromString("c42a7ac6-48ee-4015-9b8a-5b77d6ecb8b0"),
                        UUID.fromString("12032b90-92b0-467e-9f30-e70a4147a685"),
                        "VOLUNTARY", LocalDate.parse("2026-11-01"),
                        LocalDate.parse("2026-10-31")));
    }

    static Hrm013PayrollTaskDispatchStore.DispatchRecord record(long tenantId,
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch, byte[] exact,
            Hrm013WorkloadProofV1.GrantUseMode mode, UUID root, Instant rootAt,
            String rootDigest) {
        return new Hrm013PayrollTaskDispatchStore.DispatchRecord(
                tenantId, dispatch.dispatchEventId(), dispatch.casePublicId(),
                dispatch.taskPublicId(), dispatch.taskOwnerRevision(),
                dispatch.taskVersion(), dispatch.dispatchedAt(), dispatch.correlationId(),
                dispatch.payloadSha256(), exact, 7, 9, mode, root, rootAt, rootDigest);
    }
}
