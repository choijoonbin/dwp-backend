package com.dwp.services.approval.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit command-scoped evidence carried into the terminal vote transaction.
 *
 * <p>The value deliberately replaces request attributes or thread locals.  Ordinary
 * Approval commands carry {@link Kind#STANDARD}; HRM-013 commands carry the route
 * that admitted the decision and, for approval, the already verified and reserved
 * step-up evidence.</p>
 */
public record ApprovalTerminalDecisionContext(
        Kind kind,
        UUID taskId,
        UUID correlationId,
        StepUpEvidence stepUpEvidence) {

    public enum Kind {
        STANDARD,
        HRM013_APPROVE,
        HRM013_REJECT
    }

    public record StepUpEvidence(
            UUID evidenceId,
            UUID nonce,
            Instant verifiedAt,
            Instant consumedAt) {
        public StepUpEvidence {
            Objects.requireNonNull(evidenceId, "evidenceId");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(verifiedAt, "verifiedAt");
            Objects.requireNonNull(consumedAt, "consumedAt");
            if (evidenceId.equals(nonce) || verifiedAt.isAfter(consumedAt)
                    || consumedAt.isAfter(verifiedAt.plusSeconds(600))) {
                throw new IllegalArgumentException("HRM-013 step-up evidence is invalid.");
            }
        }
    }

    public ApprovalTerminalDecisionContext {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.STANDARD) {
            if (taskId != null || correlationId != null || stepUpEvidence != null) {
                throw new IllegalArgumentException("Standard decisions carry no HRM-013 evidence.");
            }
        } else {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(correlationId, "correlationId");
            if ((kind == Kind.HRM013_APPROVE) != (stepUpEvidence != null)) {
                throw new IllegalArgumentException("HRM-013 route and step-up evidence disagree.");
            }
        }
    }

    public static ApprovalTerminalDecisionContext standard() {
        return new ApprovalTerminalDecisionContext(Kind.STANDARD, null, null, null);
    }

    public static ApprovalTerminalDecisionContext hrisReject(UUID taskId, UUID correlationId) {
        return new ApprovalTerminalDecisionContext(
                Kind.HRM013_REJECT, taskId, correlationId, null);
    }

    public static ApprovalTerminalDecisionContext hrisApprove(
            UUID taskId, UUID correlationId, StepUpEvidence evidence) {
        return new ApprovalTerminalDecisionContext(
                Kind.HRM013_APPROVE, taskId, correlationId, evidence);
    }

    public boolean hrisSeparation() {
        return kind != Kind.STANDARD;
    }
}
