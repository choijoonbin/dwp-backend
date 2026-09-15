package com.dwp.services.approval.domain;

import java.time.Instant;
import java.util.List;

public final class ApprovalAdminTrendDtos {

    private ApprovalAdminTrendDtos() {
    }

    public record Trend(
            Instant generatedAt,
            int windowHours,
            int bucketHours,
            List<Bucket> buckets) {
        public static Trend empty() {
            return new Trend(Instant.EPOCH, 72, 6, List.of());
        }
    }

    public record Bucket(
            Instant startsAt,
            Instant endsAt,
            int submittedRequests,
            int completedRequests,
            int slaBreaches,
            int unresolvedDeliveryUpdates,
            int inFlightRequests,
            int slaEligibleTasks) {
    }
}
