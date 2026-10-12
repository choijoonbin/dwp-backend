package com.dwp.services.people.hr.performance.populationfreeze;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** IAQ-021 request and projection contract. */
public final class PerformanceReviewOperationsDtos {

    private PerformanceReviewOperationsDtos() {
    }

    public record QueryRequest(
            int limit,
            Filter filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            String cursor) {
    }

    public record Filter(
            ReferenceFilter reviewAssignment,
            QueueFilter workQueue,
            StatusFilter dueStatus,
            LocalDate effectiveOn) {

        static Filter empty() {
            return new Filter(null, null, null, null);
        }
    }

    public record ReferenceFilter(
            List<String> codes,
            List<String> publicIds) {
    }

    public record QueueFilter(
            Integer minimumOpenCount,
            Integer maximumOpenCount,
            List<String> slaStates) {
    }

    public record StatusFilter(
            List<String> codes,
            Long minimumRevision) {
    }

    public record Projection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<Item> items) {
    }

    public record QueryState(
            String state,
            String scopeDecisionRevision,
            String freshness,
            String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {
    }

    public record FieldDecisionSummary(
            int viewCount,
            int maskedCount,
            int omittedCount) {
    }

    public record PartialFailure(
            String componentKey,
            String errorCode,
            UUID correlationId,
            boolean retryable) {
    }

    public record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
    }

    public record Item(
            String itemPublicId,
            String title,
            String statusCode,
            FieldGroups fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldGroups(
            Object REVIEW_ASSIGNMENT,
            Object WORK_QUEUE,
            Object DUE_STATUS) {
    }

    public record ViewedReferenceField(
            String decision,
            ReferenceValue value) {
    }

    public record ViewedQueueField(
            String decision,
            QueueValue value) {
    }

    public record ViewedStatusField(
            String decision,
            StatusValue value) {
    }

    public record MaskedField(
            String decision,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object value,
            String maskReason,
            String maskedDisplayKey) {
    }

    public record ReferenceValue(
            String publicId,
            String code,
            String label,
            long revision) {
    }

    public record QueueValue(
            int openCount,
            Instant oldestAt,
            String slaStatus,
            long revision) {
    }

    public record StatusValue(
            String code,
            long revision) {
    }

    public record DeepLink(
            String route,
            String resourcePublicId) {
    }

    public record AvailableAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
    }
}
