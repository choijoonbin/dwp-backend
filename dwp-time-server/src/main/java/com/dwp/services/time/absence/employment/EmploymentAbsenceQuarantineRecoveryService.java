package com.dwp.services.time.absence.employment;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Non-browser, explicitly authorized release for non-event TIM-003 quarantines.
 * Event contract, payload-conflict and related DEAD evidence remain replay-owned.
 */
public final class EmploymentAbsenceQuarantineRecoveryService {

    public static final String CAPABILITY =
            "HRIS_TIM_EMPLOYMENT_PROJECTION_RECOVER";

    private static final Set<String> OPERATOR_RECOVERABLE_ERRORS = Set.of(
            "INVALID_REQUEST_BINDING",
            "INVALID_WORKLOAD_PROOF",
            "FORBIDDEN_OWNER_SCOPE",
            "OWNER_RELATIONSHIP_NOT_FOUND",
            "OWNER_CONFIGURATION_INVALID",
            "OWNER_CONTRACT_INVALID",
            "OWNER_RESPONSE_INVALID",
            "OWNER_SIGNATURE_INVALID",
            "STORED_PROJECTION_INTEGRITY_FAILURE");

    private final EmploymentAbsenceProjectionStore projections;
    private final EmploymentAbsenceProjectionRecoveryAuthority authority;
    private final EmploymentAbsenceAuditSink audit;
    private final EmploymentAbsenceActivationGate gate;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public EmploymentAbsenceQuarantineRecoveryService(
            EmploymentAbsenceProjectionStore projections,
            EmploymentAbsenceProjectionRecoveryAuthority authority,
            EmploymentAbsenceAuditSink audit,
            EmploymentAbsenceActivationGate gate,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.projections = projections;
        this.authority = authority;
        this.audit = audit;
        this.gate = gate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ReleaseOutcome release(ReleaseRequest request) {
        Objects.requireNonNull(request, "request");
        if (!gate.permits(request.tenantId())) return ReleaseOutcome.DENIED_DISABLED;
        boolean authorized;
        try {
            authorized = authority.authorized(
                    request.tenantId(), request.actor(), CAPABILITY,
                    request.correlationId());
        } catch (RuntimeException unavailable) {
            authorized = false;
        }
        if (!authorized) return ReleaseOutcome.DENIED_AUTHORITY;
        try {
            ReleaseOutcome outcome = transactions.execute(status -> releaseFenced(request));
            return outcome == null ? ReleaseOutcome.DENIED_STATE : outcome;
        } catch (JdbcEmploymentAbsenceProjectionStore.ProjectionCasLost stale) {
            return ReleaseOutcome.DENIED_STALE_VERSION;
        }
    }

    private ReleaseOutcome releaseFenced(ReleaseRequest request) {
        projections.lockTuple(request.tenantId(), request.relationshipPublicId());
        if (projections.hasBlockingInbox(
                request.tenantId(), request.relationshipPublicId())) {
            return ReleaseOutcome.DENIED_BLOCKER;
        }
        EmploymentAbsenceProjection current = projections.find(
                request.tenantId(), request.relationshipPublicId()).orElse(null);
        if (current == null
                || current.projectionVersion() != request.expectedProjectionVersion()
                || current.freshness()
                    != EmploymentAbsenceContract.FreshnessState.QUARANTINED
                || current.recoveryOutcome()
                    != EmploymentAbsenceContract.RecoveryOutcome.PERMANENT_FAILURE) {
            return current != null
                    && current.projectionVersion() != request.expectedProjectionVersion()
                    ? ReleaseOutcome.DENIED_STALE_VERSION
                    : ReleaseOutcome.DENIED_STATE;
        }
        if (current.requiredRelationshipRevision()
                > current.sharedOffsetSequence()) {
            return ReleaseOutcome.DENIED_STATE;
        }
        if (!OPERATOR_RECOVERABLE_ERRORS.contains(
                current.lastRecoveryErrorCode())) {
            return ReleaseOutcome.DENIED_ERROR_CODE;
        }
        EmploymentAbsenceProjection stored = projections.updateExpectedVersion(
                current.releaseQuarantine(clock.instant()),
                request.expectedProjectionVersion());
        audit.append(current.tenantId(), current.relationshipPublicId(),
                request.correlationId(), "TIM003_OPERATOR_QUARANTINE_RELEASE",
                "SUCCESS", Map.of(
                        "actor", request.actor(),
                        "reason", request.reason(),
                        "releasedErrorCode", current.lastRecoveryErrorCode(),
                        "projectionExpectedVersion", request.expectedProjectionVersion(),
                        "projectionResultVersion", stored.projectionVersion(),
                        "capability", CAPABILITY));
        return ReleaseOutcome.RELEASED;
    }

    public record ReleaseRequest(
            long tenantId,
            UUID relationshipPublicId,
            long expectedProjectionVersion,
            String actor,
            String reason,
            UUID correlationId) {
        public ReleaseRequest {
            EmploymentAbsenceContract.positive(tenantId, "tenantId");
            Objects.requireNonNull(relationshipPublicId, "relationshipPublicId");
            EmploymentAbsenceContract.positive(
                    expectedProjectionVersion, "expectedProjectionVersion");
            EmploymentAbsenceContract.bounded(actor, 160, "actor");
            EmploymentAbsenceContract.code(reason, "reason");
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public enum ReleaseOutcome {
        RELEASED,
        DENIED_DISABLED,
        DENIED_AUTHORITY,
        DENIED_BLOCKER,
        DENIED_STATE,
        DENIED_STALE_VERSION,
        DENIED_ERROR_CODE
    }
}
