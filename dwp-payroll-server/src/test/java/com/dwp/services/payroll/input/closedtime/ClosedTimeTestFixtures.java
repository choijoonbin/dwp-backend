package com.dwp.services.payroll.input.closedtime;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.contracts.hris.xcon.v1.Xcon010TimePeriodReopenedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon011PayrollTimeHandoffReadyV1;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

final class ClosedTimeTestFixtures {

    static final long TENANT_ID = 41L;
    static final UUID CORRELATION_ID = uuid("40000000-0000-4000-8000-000000000007");
    static final UUID CLOSE_PERIOD_ID = uuid("10000000-0000-4000-8000-000000000007");
    static final UUID RESULT_ID = uuid("20000000-0000-4000-8000-000000000007");
    static final UUID HANDOFF_ID = uuid("30000000-0000-4000-8000-000000000007");
    static final UUID PAYROLL_ENTITY_ID =
            uuid("50000000-0000-4000-8000-000000000007");
    static final UUID PAY_PERIOD_ID = uuid("60000000-0000-4000-8000-000000000007");
    static final UUID WORKER_ID = uuid("70000000-0000-4000-8000-000000000007");
    static final UUID ASSIGNMENT_ID = uuid("80000000-0000-4000-8000-000000000007");
    static final UUID SECOND_WORKER_ID =
            uuid("90000000-0000-4000-8000-000000000007");
    static final UUID SECOND_ASSIGNMENT_ID =
            uuid("a0000000-0000-4000-8000-000000000007");
    static final String RULE_DIGEST = "2".repeat(64);

    private ClosedTimeTestFixtures() {
    }

    static Xcon008ClosedTimeResultV1 result() {
        List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines = lines();
        String sourceDigest = ClosedTimeResultContractVerifier.sourceDigest(lines);
        String payloadDigest = ClosedTimeResultContractVerifier.payloadDigest(
                CLOSE_PERIOD_ID, 1, RESULT_ID, 10, 20, PAY_PERIOD_ID,
                PAYROLL_ENTITY_ID, RULE_DIGEST, sourceDigest, null, 2, lines);
        return new Xcon008ClosedTimeResultV1(
                CLOSE_PERIOD_ID, 1, RESULT_ID, 10, 20, lines.size(),
                lines, PAY_PERIOD_ID, payloadDigest,
                PAYROLL_ENTITY_ID, RULE_DIGEST, sourceDigest, null, 2);
    }

    static Xcon011PayrollTimeHandoffReadyV1 handoff() {
        Xcon008ClosedTimeResultV1 result = result();
        return new Xcon011PayrollTimeHandoffReadyV1(
                CLOSE_PERIOD_ID, 1, RESULT_ID, HANDOFF_ID,
                result.lineCount(), PAY_PERIOD_ID, result.payloadDigest(),
                PAYROLL_ENTITY_ID, RULE_DIGEST, result.sourceDigest(),
                result.workerCount());
    }

    static Xcon010TimePeriodReopenedV1 reopen() {
        return new Xcon010TimePeriodReopenedV1(
                CLOSE_PERIOD_ID, 2, 1, "CORRECTION_REQUIRED");
    }

    static ClosedTimeResultContractVerifier.SourceEvidenceAttestation
            sourceEvidence() {
        Xcon011PayrollTimeHandoffReadyV1 signal = handoff();
        return ClosedTimeResultContractVerifier.attestSourceEvidence(
                TENANT_ID, CORRELATION_ID, signal.handoffId(),
                signal.closedTimeResultId(), signal.sourceDigest());
    }

    static List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines() {
        return List.of(
                new Xcon008ClosedTimeResultV1.ClosedTimeLine(
                        WORKER_ID, ASSIGNMENT_ID, "REGULAR",
                        new BigDecimal("8.000000"), null, 2,
                        "4".repeat(64), "HOUR"),
                new Xcon008ClosedTimeResultV1.ClosedTimeLine(
                        SECOND_WORKER_ID, SECOND_ASSIGNMENT_ID, "OVERTIME",
                        new BigDecimal("2.500000"), null, 1,
                        "5".repeat(64), "HOUR"));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
