package com.dwp.services.notification.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public final class NotificationNoiseQualityModels {

    private NotificationNoiseQualityModels() {
    }

    public enum NoiseTimeRange {
        LAST_24_HOURS(24, ChronoUnit.HOURS, "hour"),
        LAST_7_DAYS(7, ChronoUnit.DAYS, "day"),
        LAST_30_DAYS(30, ChronoUnit.DAYS, "day");

        private final long amount;
        private final ChronoUnit unit;
        private final String bucket;

        NoiseTimeRange(long amount, ChronoUnit unit, String bucket) {
            this.amount = amount;
            this.unit = unit;
            this.bucket = bucket;
        }

        public Instant windowStart(Instant generatedAt) {
            return generatedAt.minus(amount, unit);
        }

        public String bucket() {
            return bucket;
        }
    }

    public enum NoiseFindingSeverity {
        INFO,
        WARNING,
        CRITICAL
    }

    public enum NoiseRisk {
        HIGH_MUTE_RATE,
        LOW_ACTION_CONVERSION,
        DEDUPLICATION_OPPORTUNITY
    }

    public record NoiseQualityQuery(
            NoiseTimeRange range,
            String search,
            NoiseFindingSeverity severity,
            NoiseRisk risk) {

        public NoiseQualityQuery {
            range = range == null ? NoiseTimeRange.LAST_30_DAYS : range;
            search = search == null || search.isBlank() ? null : search.trim();
            if (search != null && search.length() > 120) {
                throw new IllegalArgumentException("Noise quality search must be 120 characters or fewer.");
            }
        }

        public static NoiseQualityQuery defaults() {
            return new NoiseQualityQuery(NoiseTimeRange.LAST_30_DAYS, null, null, null);
        }
    }

    public record NoiseTypeMetric(
            UUID contractId,
            String appKey,
            String typeKey,
            String ownerTeam,
            long cohortSize,
            long volume,
            Double muteRate,
            Double deduplicationRate,
            Double actionConversionRate,
            String findingCode,
            String findingSeverity,
            String findingTarget,
            String findingTargetKey) {
    }

    public record NoiseTrendPoint(
            Instant bucketStart,
            long cohortSize,
            long volume,
            Double muteRate,
            Double deduplicationRate,
            Double actionConversionRate) {
    }

    public record FourEyesGovernance(
            String state,
            long publishedPolicyCount,
            long draftPolicyCount,
            boolean reviewerSeparationRequired,
            Instant updatedAt) {
    }

    public record NoiseQuality(
            boolean partial,
            List<String> unavailableSources,
            String message,
            boolean sufficientCohort,
            int minimumCohortSize,
            Long observedCohortSize,
            Double muteRate,
            Double deduplicationRate,
            Double actionConversionRate,
            Long fatigueExposedUsers,
            List<NoiseTypeMetric> noisyTypes,
            List<NoiseTrendPoint> trend,
            FourEyesGovernance fourEyes,
            NoiseTimeRange range,
            Instant windowStart,
            Instant generatedAt) {

        public NoiseQuality {
            unavailableSources = List.copyOf(unavailableSources);
            noisyTypes = List.copyOf(noisyTypes);
            trend = List.copyOf(trend);
        }
    }

    record NoiseAggregateRow(
            UUID contractId,
            String appKey,
            String typeKey,
            String ownerTeam,
            long recipientCount,
            long volume,
            long mutingRecipients,
            long deduplicationEligibleOccurrences,
            long deduplicatedOccurrences,
            long actionable,
            long completedActions,
            String findingCode,
            String findingSeverity,
            String findingTarget,
            String findingTargetKey) {
    }

    record NoiseTrendRow(
            Instant bucketStart,
            long recipientCount,
            long volume,
            long mutingRecipients,
            long deduplicationEligibleOccurrences,
            long deduplicatedOccurrences,
            long actionable,
            long completedActions) {
    }

    record FourEyesGovernanceRow(
            long publishedPolicyCount,
            long draftPolicyCount,
            boolean publishedEvidenceValid,
            Instant updatedAt) {
    }
}
