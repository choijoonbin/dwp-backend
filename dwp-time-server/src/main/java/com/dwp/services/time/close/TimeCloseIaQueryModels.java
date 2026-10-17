package com.dwp.services.time.close;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-local Java shapes that serialize byte-for-shape with IAQ-039/043/044. */
public final class TimeCloseIaQueryModels {

    private TimeCloseIaQueryModels() {}

    public record TimeCloseProjection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<TimeCloseItem> items) {}

    public record QueryState(
            String state,
            String scopeDecisionRevision,
            String freshness,
            String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {}

    public record FieldDecisionSummary(
            int viewCount, int maskedCount, int omittedCount) {}

    public record PartialFailure(
            String componentKey, String errorCode, UUID correlationId, boolean retryable) {}

    public record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {}

    public record TimeCloseItem(
            String itemPublicId,
            String title,
            String statusCode,
            Map<String, Map<String, Object>> fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {}

    public record DeepLink(String route, String resourcePublicId) {}

    public record AvailableAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {}

    record EffectiveRangeFilter(LocalDate activeOn, LocalDate from, LocalDate to) {}

    record AggregateFilter(Long minimumItemCount, Long maximumItemCount) {}

    record StatusFilter(List<String> codes, Long minimumRevision) {}

    record ReceiptFilter(List<String> receiptIds, List<String> states) {}

    record TimeCloseQuery(
            int limit,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            String cursor,
            LocalDate effectiveOn,
            EffectiveRangeFilter timePeriod,
            AggregateFilter aggregate,
            StatusFilter status,
            ReceiptFilter receipt) {}

    record CloseProjectionRow(
            UUID periodId,
            String closeScope,
            LocalDate periodStart,
            LocalDate periodEnd,
            String status,
            long closeRevision,
            long version,
            Instant createdAt,
            Instant updatedAt,
            int blockerCount,
            int impactCount,
            UUID latestReceiptId,
            String latestReceiptState,
            Instant latestReceiptAt,
            Instant latestReceiptCompletedAt,
            Long latestReceiptRevision,
            UUID latestHandoffId,
            String latestHandoffState,
            Instant latestHandoffAt,
            Instant latestHandoffUpdatedAt,
            Long latestHandoffRevision) {}
}
