package com.dwp.services.payroll.input.closedtime;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.contracts.hris.xcon.v1.Xcon010TimePeriodReopenedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon011PayrollTimeHandoffReadyV1;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.payroll.input.closedtime.ClosedTimeTestFixtures.CORRELATION_ID;
import static com.dwp.services.payroll.input.closedtime.ClosedTimeTestFixtures.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClosedTimeResultContractVerifierTest {

    @Test
    void recomputesCanonicalOrderedSourceAndPayloadDigests() {
        Xcon008ClosedTimeResultV1 result = ClosedTimeTestFixtures.result();

        assertThat(ClosedTimeResultContractVerifier.sourceDigest(result.lines()))
                .isEqualTo(result.sourceDigest());
        assertThat(payloadDigest(result)).isEqualTo(result.payloadDigest());

        var verified = verify(ClosedTimeTestFixtures.handoff(), result);
        assertThat(verified.tenantId()).isEqualTo(TENANT_ID);
        assertThat(verified.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(verified.signal().closedTimeResultId())
                .isEqualTo(verified.result().closedTimeResultId());
    }

    @Test
    void rejectsLineTamperingOrderDriftAndSourceCountDrift() {
        Xcon008ClosedTimeResultV1 original = ClosedTimeTestFixtures.result();
        Xcon008ClosedTimeResultV1.ClosedTimeLine first =
                original.lines().getFirst();
        Xcon008ClosedTimeResultV1.ClosedTimeLine quantityTamper =
                new Xcon008ClosedTimeResultV1.ClosedTimeLine(
                        first.workerId(), first.assignmentId(), first.payCode(),
                        new BigDecimal("9"), first.currency(),
                        first.sourceEntryCount(), first.sourceEntryDigest(),
                        first.unit());
        Xcon008ClosedTimeResultV1 payloadTamper = copy(
                original, List.of(quantityTamper, original.lines().get(1)),
                original.ledgerRevisionFrom(), original.ledgerRevisionTo(),
                original.workerCount(), original.sourceDigest(),
                original.payloadDigest());

        assertMismatch(payloadTamper, "payloadDigest");

        Xcon008ClosedTimeResultV1 orderTamper = copy(
                original, List.of(original.lines().get(1), first),
                original.ledgerRevisionFrom(), original.ledgerRevisionTo(),
                original.workerCount(), original.sourceDigest(),
                original.payloadDigest());
        assertMismatch(orderTamper, "sourceDigest");

        Xcon008ClosedTimeResultV1.ClosedTimeLine countTamper =
                new Xcon008ClosedTimeResultV1.ClosedTimeLine(
                        first.workerId(), first.assignmentId(), first.payCode(),
                        first.quantity(), first.currency(),
                        first.sourceEntryCount() + 1, first.sourceEntryDigest(),
                        first.unit());
        Xcon008ClosedTimeResultV1 sourceCountTamper = copy(
                original, List.of(countTamper, original.lines().get(1)),
                original.ledgerRevisionFrom(), original.ledgerRevisionTo(),
                original.workerCount(), original.sourceDigest(),
                original.payloadDigest());
        assertMismatch(sourceCountTamper, "sourceDigest");
    }

    @Test
    void rejectsIdentityCountWorkerAndRevisionDrift() {
        Xcon011PayrollTimeHandoffReadyV1 signal = ClosedTimeTestFixtures.handoff();
        Xcon008ClosedTimeResultV1 result = ClosedTimeTestFixtures.result();
        Xcon011PayrollTimeHandoffReadyV1 wrongIdentity = newSignal(
                signal, signal.closeRevision(), UUID.randomUUID(),
                signal.lineCount(), signal.workerCount());
        assertThatThrownBy(() -> verify(wrongIdentity, result))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining("closedTimeResultId");

        Xcon011PayrollTimeHandoffReadyV1 wrongCount = newSignal(
                signal, signal.closeRevision(), signal.closedTimeResultId(),
                signal.lineCount() + 1, signal.workerCount());
        assertThatThrownBy(() -> verify(wrongCount, result))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining("lineCount");

        Xcon008ClosedTimeResultV1 wrongWorkers = copy(
                result, result.lines(), result.ledgerRevisionFrom(),
                result.ledgerRevisionTo(), result.workerCount() + 1,
                result.sourceDigest(), result.payloadDigest());
        Xcon011PayrollTimeHandoffReadyV1 matchingWrongWorkers = newSignal(
                signal, signal.closeRevision(), signal.closedTimeResultId(),
                signal.lineCount(), signal.workerCount() + 1);
        assertThatThrownBy(() -> verify(matchingWrongWorkers, wrongWorkers))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining("workerCount");

        Xcon011PayrollTimeHandoffReadyV1 wrongRevision = newSignal(
                signal, signal.closeRevision() + 1,
                signal.closedTimeResultId(), signal.lineCount(),
                signal.workerCount());
        assertThatThrownBy(() -> verify(wrongRevision, result))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining("closeRevision");

        Xcon008ClosedTimeResultV1 reversedLedger = copy(
                result, result.lines(), 21, 20, result.workerCount(),
                result.sourceDigest(), result.payloadDigest());
        assertMismatch(reversedLedger, "ledgerRevisionRange");
    }

    @Test
    void requiresSourceEvidenceAttestationBoundToTheExactHandoff() {
        Xcon011PayrollTimeHandoffReadyV1 signal = ClosedTimeTestFixtures.handoff();
        Xcon008ClosedTimeResultV1 result = ClosedTimeTestFixtures.result();
        var wrongEvidence = ClosedTimeResultContractVerifier.attestSourceEvidence(
                TENANT_ID, CORRELATION_ID, signal.handoffId(),
                UUID.randomUUID(), signal.sourceDigest());

        assertThatThrownBy(() -> ClosedTimeResultContractVerifier.verifyHandoff(
                TENANT_ID, CORRELATION_ID, signal, result, wrongEvidence))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining("attestedResultId");
    }

    @Test
    void verifiedTokensAreFactoryOnlyAndReopenMustMoveForward() {
        assertThat(ClosedTimeResultContractVerifier.VerifiedHandoff.class
                .getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(ClosedTimeResultContractVerifier.VerifiedReopen.class
                .getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));

        var handoff = verify(
                ClosedTimeTestFixtures.handoff(), ClosedTimeTestFixtures.result());
        var reopen = ClosedTimeResultContractVerifier.verifyReopen(
                TENANT_ID, CORRELATION_ID, ClosedTimeTestFixtures.reopen(), handoff);
        assertThat(reopen.invalidatedResultId())
                .isEqualTo(handoff.result().closedTimeResultId());

        Xcon010TimePeriodReopenedV1 sameRevision =
                new Xcon010TimePeriodReopenedV1(
                        handoff.result().closePeriodId(), 1, 1,
                        "CORRECTION_REQUIRED");
        assertThatThrownBy(() -> ClosedTimeResultContractVerifier.verifyReopen(
                TENANT_ID, CORRELATION_ID, sameRevision, handoff))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining("newRevision");

        Xcon010TimePeriodReopenedV1 revisionGap =
                new Xcon010TimePeriodReopenedV1(
                        handoff.result().closePeriodId(), 3, 1,
                        "CORRECTION_REQUIRED");
        assertThatThrownBy(() -> ClosedTimeResultContractVerifier.verifyReopen(
                TENANT_ID, CORRELATION_ID, revisionGap, handoff))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining("newRevision");
    }

    private static ClosedTimeResultContractVerifier.VerifiedHandoff verify(
            Xcon011PayrollTimeHandoffReadyV1 signal,
            Xcon008ClosedTimeResultV1 result) {
        return ClosedTimeResultContractVerifier.verifyHandoff(
                TENANT_ID, CORRELATION_ID, signal, result,
                ClosedTimeTestFixtures.sourceEvidence());
    }

    private static void assertMismatch(
            Xcon008ClosedTimeResultV1 result, String field) {
        assertThatThrownBy(() -> verify(ClosedTimeTestFixtures.handoff(), result))
                .isInstanceOf(ClosedTimeResultContractVerifier
                        .ContractMismatchException.class)
                .hasMessageContaining(field);
    }

    private static String payloadDigest(Xcon008ClosedTimeResultV1 result) {
        return ClosedTimeResultContractVerifier.payloadDigest(
                result.closePeriodId(), result.closeRevision(),
                result.closedTimeResultId(), result.ledgerRevisionFrom(),
                result.ledgerRevisionTo(), result.payPeriodId(),
                result.payrollEntityId(), result.ruleDigest(),
                result.sourceDigest(), result.supersedesResultId(),
                result.workerCount(), result.lines());
    }

    private static Xcon008ClosedTimeResultV1 copy(
            Xcon008ClosedTimeResultV1 original,
            List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines,
            long ledgerRevisionFrom,
            long ledgerRevisionTo,
            int workerCount,
            String sourceDigest,
            String payloadDigest) {
        return new Xcon008ClosedTimeResultV1(
                original.closePeriodId(), original.closeRevision(),
                original.closedTimeResultId(), ledgerRevisionFrom,
                ledgerRevisionTo, lines.size(), lines,
                original.payPeriodId(), payloadDigest,
                original.payrollEntityId(), original.ruleDigest(),
                sourceDigest, original.supersedesResultId(), workerCount);
    }

    private static Xcon011PayrollTimeHandoffReadyV1 newSignal(
            Xcon011PayrollTimeHandoffReadyV1 original,
            long closeRevision,
            UUID resultId,
            int lineCount,
            int workerCount) {
        return new Xcon011PayrollTimeHandoffReadyV1(
                original.closePeriodId(), closeRevision, resultId,
                original.handoffId(), lineCount, original.payPeriodId(),
                original.payloadDigest(), original.payrollEntityId(),
                original.ruleDigest(), original.sourceDigest(), workerCount);
    }
}
