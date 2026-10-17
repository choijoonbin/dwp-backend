package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.ClosedTimeResultService.VerifiedClosedTimeResultAccess;
import com.dwp.services.time.close.TimeCloseModels.PersistedClosedResult;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClosedTimeResultServiceAuthorizationTest {

    @Test
    void refetchReturnsOnlyTheResultBoundToTheCausalHandoffAndEveryDigestFact() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        ClosedTimeResultService service = new ClosedTimeResultService(
                repository, tenant -> tenant == TimeCloseTestFixtures.TENANT);
        when(repository.findHandoff(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.HANDOFF))
                .thenReturn(Optional.of(TimeCloseTestFixtures.pendingHandoff()));
        when(repository.findResult(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RESULT))
                .thenReturn(Optional.of(TimeCloseTestFixtures.persistedResult()));

        Xcon008ClosedTimeResultV1 result = service.refetch(
                TimeCloseTestFixtures.resultAccess());

        assertThat(result).isEqualTo(TimeCloseTestFixtures.result());
        verify(repository).findHandoff(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.HANDOFF);
        verify(repository).findResult(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RESULT);
    }

    @Test
    void disabledOrUnenrolledRefetchIsOpaqueAndMakesZeroOwnerCalls() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        ClosedTimeResultService service = new ClosedTimeResultService(
                repository, ignored -> false);

        assertOpaque(() -> service.refetch(TimeCloseTestFixtures.resultAccess()));

        verifyNoInteractions(repository);
    }

    @Test
    void unverifiedWorkloadProfileCannotReachOwnerStorage() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);

        assertThatThrownBy(() -> new VerifiedClosedTimeResultAccess(
                TimeCloseTestFixtures.TENANT, 19L, 23L, "browser-or-gateway",
                ClosedTimeResultService.BINDING, ClosedTimeResultService.PURPOSE,
                ClosedTimeResultService.PROJECTION, TimeCloseTestFixtures.CORRELATION,
                TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                TimeCloseTestFixtures.PERIOD, 3L, TimeCloseTestFixtures.PAYROLL_ENTITY,
                TimeCloseTestFixtures.PAY_PERIOD, 0, 0,
                TimeCloseTestFixtures.SOURCE_DIGEST, TimeCloseTestFixtures.RULE_DIGEST,
                TimeCloseTestFixtures.PAYLOAD_DIGEST))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void missingOrForeignHandoffIsOpaqueAndNeverLoadsResultBytes() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        ClosedTimeResultService service = new ClosedTimeResultService(repository, ignored -> true);
        when(repository.findHandoff(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.HANDOFF))
                .thenReturn(Optional.empty());

        assertOpaque(() -> service.refetch(TimeCloseTestFixtures.resultAccess()));

        verify(repository, never()).findResult(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RESULT);
    }

    @Test
    void everyEventBoundHandoffFactIsCheckedBeforeResultLookup() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        ClosedTimeResultService service = new ClosedTimeResultService(repository, ignored -> true);
        when(repository.findHandoff(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(TimeCloseTestFixtures.pendingHandoff()));
        UUID other = UUID.fromString("72000000-0000-4000-8000-000000000001");
        List<VerifiedClosedTimeResultAccess> mismatches = List.of(
                access(42L, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, other,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        other, TimeCloseTestFixtures.RESULT, TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, other, TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT, other, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 4L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L, other, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, other, 0, 0,
                        TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        1, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 1, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, "0".repeat(64),
                        TimeCloseTestFixtures.RULE_DIGEST, TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        "0".repeat(64), TimeCloseTestFixtures.PAYLOAD_DIGEST),
                access(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.CORRELATION,
                        TimeCloseTestFixtures.HANDOFF, TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PERIOD, 3L,
                        TimeCloseTestFixtures.PAYROLL_ENTITY, TimeCloseTestFixtures.PAY_PERIOD,
                        0, 0, TimeCloseTestFixtures.SOURCE_DIGEST,
                        TimeCloseTestFixtures.RULE_DIGEST, "0".repeat(64)));

        mismatches.forEach(value -> assertOpaque(() -> service.refetch(value)));

        verify(repository, never()).findResult(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resultHeaderMismatchIsOpaqueAfterCausalHandoffAuthorization() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        ClosedTimeResultService service = new ClosedTimeResultService(repository, ignored -> true);
        when(repository.findHandoff(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.HANDOFF))
                .thenReturn(Optional.of(TimeCloseTestFixtures.pendingHandoff()));
        Xcon008ClosedTimeResultV1 mismatched = new Xcon008ClosedTimeResultV1(
                TimeCloseTestFixtures.PERIOD, 3L, TimeCloseTestFixtures.RESULT,
                1L, 29L, 0, List.of(), TimeCloseTestFixtures.PAY_PERIOD,
                TimeCloseTestFixtures.PAYLOAD_DIGEST,
                TimeCloseTestFixtures.PAYROLL_ENTITY,
                TimeCloseTestFixtures.RULE_DIGEST, "0".repeat(64), null, 0);
        when(repository.findResult(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RESULT))
                .thenReturn(Optional.of(new PersistedClosedResult(
                        201L, mismatched, TimeCloseTestFixtures.NOW)));

        assertOpaque(() -> service.refetch(TimeCloseTestFixtures.resultAccess()));
    }

    @Test
    void receiptOwnershipPreProbeAndOriginRevalidationFailClosed() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseService inactive = service(repository, ignored -> false);
        TimeCloseReceiptOwnership.OriginAuthority origin =
                mock(TimeCloseReceiptOwnership.OriginAuthority.class);

        assertThat(inactive.owns(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT)).isFalse();
        assertThat(inactive.reauthorizedRead(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT, origin))
                .isEmpty();
        verifyNoInteractions(repository, origin);

        TimeCloseService active = service(repository,
                tenant -> tenant == TimeCloseTestFixtures.TENANT);
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT))
                .thenReturn(Optional.of(TimeCloseTestFixtures.storedReceipt()));
        when(origin.permits(TimeCloseTestFixtures.storedReceipt())).thenReturn(false, true);

        assertThat(active.owns(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT)).isTrue();
        assertThat(active.reauthorizedRead(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT, origin))
                .isEmpty();
        assertThat(active.reauthorizedRead(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT, origin))
                .contains(TimeCloseTestFixtures.storedReceipt().receipt());
        verify(origin, org.mockito.Mockito.times(2))
                .permits(TimeCloseTestFixtures.storedReceipt());
    }

    private static TimeCloseService service(
            TimeCloseRepository repository, TimeCloseActivationGate gate) {
        return new TimeCloseService(
                repository,
                mock(TimeCloseEvidenceSource.class),
                mock(TimeCloseApprovalVerifier.class),
                mock(TimeCloseStepUpVerifier.class),
                gate, Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC));
    }

    private static VerifiedClosedTimeResultAccess access(
            long tenantId,
            UUID correlationId,
            UUID handoffId,
            UUID resultId,
            UUID periodId,
            long closeRevision,
            UUID payrollEntityId,
            UUID payPeriodId,
            int workerCount,
            int lineCount,
            String sourceDigest,
            String ruleDigest,
            String payloadDigest) {
        return new VerifiedClosedTimeResultAccess(
                tenantId, 19L, 23L, ClosedTimeResultService.PAYROLL_WORKLOAD,
                ClosedTimeResultService.BINDING, ClosedTimeResultService.PURPOSE,
                ClosedTimeResultService.PROJECTION, correlationId, handoffId, resultId,
                periodId, closeRevision, payrollEntityId, payPeriodId,
                workerCount, lineCount, sourceDigest, ruleDigest, payloadDigest);
    }

    private static void assertOpaque(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work)
                .isInstanceOfSatisfying(BaseException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(failure.getMessage())
                            .isEqualTo("The requested closed-time result was not found.");
                });
    }
}
