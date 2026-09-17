package com.dwp.services.approval.deployment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

public final class ApprovalDeploymentCanaryModels {
    private ApprovalDeploymentCanaryModels() {
    }

    public enum CanaryState {
        RUNNING,
        PAUSED
    }

    public record ControlCommand(
            CanaryState state,
            String reason,
            long expectedPromotionVersion,
            long expectedControlVersion) {
    }

    public record TelemetryCommand(
            UUID telemetryId,
            int stableWeight,
            int canaryWeight,
            Map<String, Object> metrics,
            long expectedPromotionVersion,
            ExternalHealthEvidenceSubmission evidence) {
        public TelemetryCommand {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }
    }

    public record CanaryControl(
            UUID promotionId,
            CanaryState state,
            String reason,
            Long changedBy,
            Instant changedAt,
            long version) {
    }

    public record TelemetryObservation(
            UUID telemetryId,
            UUID promotionId,
            long promotionVersion,
            HealthOutcome outcome,
            int stableWeight,
            int canaryWeight,
            Map<String, Object> metrics,
            String payloadSha256,
            Instant sourceGeneratedAt,
            String verificationReference,
            long recordedBy,
            Instant recordedAt) {
    }

    public record CanaryView(
            Promotion promotion,
            CanaryControl control,
            TelemetryObservation latestTelemetry,
            List<TelemetryObservation> telemetry) {
    }

    public record LedgerEntry(
            long sequence,
            UUID journalId,
            String eventType,
            long actorUserId,
            Map<String, Object> payload,
            Instant occurredAt,
            String previousSha256,
            String entrySha256) {
    }

    public record LedgerView(
            UUID promotionId,
            String assuranceLevel,
            String rootSha256,
            List<LedgerEntry> entries,
            Instant verifiedAt) {
    }

    record JournalEvent(
            UUID journalId,
            String eventType,
            long actorUserId,
            Map<String, Object> payload,
            Instant occurredAt) {
    }
}
