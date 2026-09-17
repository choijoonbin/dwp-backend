package com.dwp.services.approval.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApprovalAnalyticsModels {
    private ApprovalAnalyticsModels() {
    }

    public enum Capability {
        VIEW,
        DRILL_DOWN
    }

    public record Scope(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            Set<Capability> capabilities) {
        public Scope {
            if (tenantId <= 0 || actorUserId <= 0
                    || resourceSetKey == null
                    || !resourceSetKey.matches("[A-Z][A-Z0-9_]{2,79}")) {
                throw new IllegalArgumentException("The analytics scope is invalid.");
            }
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        public boolean has(Capability capability) {
            return capabilities.contains(capability);
        }
    }

    public enum CohortDimension {
        WORKFLOW,
        FORM
    }

    public record Query(
            Instant from,
            Instant to,
            CohortDimension cohortDimension,
            int minimumCohortSize) {
    }

    public record MetricDefinition(
            String key,
            String label,
            String formula,
            String unit,
            List<String> exclusions) {
    }

    public record Coverage(
            int candidateRequests,
            int includedRequests,
            double includedPercent,
            Map<String, Integer> excludedData,
            Instant sourceThrough,
            Instant projectedAt) {
    }

    public record Metrics(
            boolean suppressed,
            String sampleBand,
            Integer sampleSize,
            Long cycleP50Seconds,
            Long cycleP90Seconds,
            Long stageWaitP50Seconds,
            Long stageWaitP90Seconds,
            Integer slaEligible,
            Integer slaBreached,
            Double slaCompliancePercent,
            Integer requestsWithRework,
            Integer delegatedRequests,
            Integer escalatedRequests,
            Integer routeConformantRequests,
            Double routeConformancePercent) {
    }

    public record Cohort(
            String key,
            Metrics metrics) {
    }

    public record StageWait(
            String stepKey,
            int sequence,
            Metrics metrics) {
    }

    public record Dashboard(
            Instant generatedAt,
            Query query,
            List<MetricDefinition> definitions,
            Coverage coverage,
            Metrics overall,
            List<Cohort> cohorts,
            List<StageWait> stageWaits) {
    }

    public record Representative(
            UUID requestId,
            String requestStatus,
            Instant submittedAt,
            Instant completedAt,
            Long cycleSeconds,
            int reworkCount,
            int delegationCount,
            int escalationCount,
            boolean routeConformant) {
    }

    @FunctionalInterface
    public interface RepresentativeAuthorizer {
        boolean canView(long tenantId, String resourceSetKey, long actorUserId, UUID requestId);
    }
}
