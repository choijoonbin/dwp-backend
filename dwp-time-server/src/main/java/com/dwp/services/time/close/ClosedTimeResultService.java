package com.dwp.services.time.close;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;
import com.dwp.services.time.close.TimeCloseModels.PersistedClosedResult;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Owner-local SVC-PEP-TIM-001 authorization seam.
 *
 * <p>The external trust adapter remains a central gate. It may call this seam only after mTLS,
 * workload proof, current tenant grant and policy revisions have been independently verified.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class ClosedTimeResultService {

    static final String PAYROLL_WORKLOAD = "dwp-payroll-server";
    static final String BINDING = "SVC-PEP-TIM-001";
    static final String PURPOSE = "HRIS_CLOSED_TIME_RESULT_REFETCH";
    static final String PROJECTION = "XCON-008";

    private final TimeCloseRepository repository;
    private final TimeCloseActivationGate activationGate;

    public ClosedTimeResultService(
            TimeCloseRepository repository,
            TimeCloseActivationGate activationGate) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate");
    }

    public Xcon008ClosedTimeResultV1 refetch(VerifiedClosedTimeResultAccess access) {
        Objects.requireNonNull(access, "access");
        if (!activationGate.allows(access.tenantId())) throw opaqueNotFound();
        repository.findHandoff(access.tenantId(), access.handoffId())
                .filter(value -> matches(value, access))
                .orElseThrow(ClosedTimeResultService::opaqueNotFound);
        PersistedClosedResult result = repository.findResult(
                        access.tenantId(), access.closedTimeResultId())
                .filter(value -> matches(value.result(), access))
                .orElseThrow(ClosedTimeResultService::opaqueNotFound);
        return result.result();
    }

    private static boolean matches(
            HandoffRecord handoff, VerifiedClosedTimeResultAccess access) {
        PayrollTimeHandoffStatusV1 value = handoff.projection();
        return handoff.tenantId() == access.tenantId()
                && handoff.correlationId().equals(access.correlationId())
                && value.handoffId().equals(access.handoffId())
                && value.closePeriodId().equals(access.closePeriodId())
                && value.closedTimeResultId().equals(access.closedTimeResultId())
                && value.closeRevision() == access.closeRevision()
                && value.payrollEntityId().equals(access.payrollEntityId())
                && value.payPeriodId().equals(access.payPeriodId())
                && value.workerCount() == access.workerCount()
                && value.lineCount() == access.lineCount()
                && value.sourceDigest().equals(access.sourceDigest())
                && value.ruleDigest().equals(access.ruleDigest())
                && value.payloadDigest().equals(access.payloadDigest());
    }

    private static boolean matches(
            Xcon008ClosedTimeResultV1 result,
            VerifiedClosedTimeResultAccess access) {
        return result.closePeriodId().equals(access.closePeriodId())
                && result.closedTimeResultId().equals(access.closedTimeResultId())
                && result.closeRevision() == access.closeRevision()
                && result.payrollEntityId().equals(access.payrollEntityId())
                && result.payPeriodId().equals(access.payPeriodId())
                && result.workerCount() == access.workerCount()
                && result.lineCount() == access.lineCount()
                && result.sourceDigest().equals(access.sourceDigest())
                && result.ruleDigest().equals(access.ruleDigest())
                && result.payloadDigest().equals(access.payloadDigest());
    }

    private static BaseException opaqueNotFound() {
        return new BaseException(
                ErrorCode.NOT_FOUND,
                "The requested closed-time result was not found.");
    }

    /** Result-bound facts emitted by the central trust adapter after owner grant revalidation. */
    public record VerifiedClosedTimeResultAccess(
            long tenantId,
            long tenantGrantRevision,
            long policyRevision,
            String callerWorkloadId,
            String bindingId,
            String purpose,
            String projection,
            UUID correlationId,
            UUID handoffId,
            UUID closedTimeResultId,
            UUID closePeriodId,
            long closeRevision,
            UUID payrollEntityId,
            UUID payPeriodId,
            int workerCount,
            int lineCount,
            String sourceDigest,
            String ruleDigest,
            String payloadDigest) {
        public VerifiedClosedTimeResultAccess {
            if (tenantId <= 0 || tenantGrantRevision <= 0 || policyRevision <= 0
                    || closeRevision <= 0 || workerCount < 0 || lineCount < 0) {
                throw new IllegalArgumentException("verified result access revisions are invalid");
            }
            if (!PAYROLL_WORKLOAD.equals(callerWorkloadId)
                    || !BINDING.equals(bindingId)
                    || !PURPOSE.equals(purpose)
                    || !PROJECTION.equals(projection)) {
                throw new IllegalArgumentException("verified result access profile is invalid");
            }
            TimeCloseModels.requireUuid(correlationId, "correlationId");
            TimeCloseModels.requireUuid(handoffId, "handoffId");
            TimeCloseModels.requireUuid(closedTimeResultId, "closedTimeResultId");
            TimeCloseModels.requireUuid(closePeriodId, "closePeriodId");
            TimeCloseModels.requireUuid(payrollEntityId, "payrollEntityId");
            TimeCloseModels.requireUuid(payPeriodId, "payPeriodId");
            TimeCloseModels.requireDigest(sourceDigest, "sourceDigest");
            TimeCloseModels.requireDigest(ruleDigest, "ruleDigest");
            TimeCloseModels.requireDigest(payloadDigest, "payloadDigest");
        }
    }
}
