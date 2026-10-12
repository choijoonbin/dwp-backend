package com.dwp.services.time.clockingestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Exact IAQ-034 and IAQ-042 request/projection shapes from the accepted HRIS schema. */
public final class ClockIaQueryModels {

    public record TemporalFilter(
            Instant startFrom,
            Instant startTo,
            Instant endFrom,
            Instant endTo) {}

    public record StatusFilter(List<String> codes, Long minimumRevision) {}

    public record AuditFilter(List<String> decisionCodes, String evidenceDigest) {}

    public record ReferenceFilter(List<String> codes, List<String> publicIds) {}

    public record MetadataFilter(List<String> keys, List<String> versions) {}

    public record ClockFilter(
            TemporalFilter clockEvent,
            LocalDate effectiveOn,
            StatusFilter ingestionStatus,
            AuditFilter qualityFinding) {

        public static ClockFilter empty() {
            return new ClockFilter(null, null, null, null);
        }
    }

    public record ExceptionFilter(
            LocalDate effectiveOn,
            StatusFilter exception,
            ReferenceFilter ownerDeepLink,
            MetadataFilter recoveryMetadata) {

        public static ExceptionFilter empty() {
            return new ExceptionFilter(null, null, null, null);
        }
    }

    public record ClockQueryRequest(
            int limit,
            ClockFilter filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            String cursor) {}

    public record ExceptionQueryRequest(
            int limit,
            ExceptionFilter filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            String cursor) {}

    public record QueryState(
            String state,
            String scopeDecisionRevision,
            String freshness,
            @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {}

    public record FieldDecisionSummary(int viewCount, int maskedCount, int omittedCount) {}

    public record PartialFailure(
            String componentKey,
            String errorCode,
            UUID correlationId,
            boolean retryable) {}

    public record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {}

    public record DeepLink(String route, String resourcePublicId) {}

    public record AvailableAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {}

    public record TemporalValue(
            Instant startAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant endAt,
            int durationMinutes) {}

    public record StatusValue(String code, long revision) {}

    public record AuditValue(
            String decisionCode,
            String evidenceDigest,
            Instant occurredAt,
            long revision) {}

    public record ReferenceValue(
            String publicId,
            String code,
            String label,
            long revision) {}

    public record MetadataValue(
            String key,
            String version,
            @JsonInclude(JsonInclude.Include.ALWAYS) String digest) {}

    public record ViewedTemporalField(String decision, TemporalValue value) {}

    public record ViewedStatusField(String decision, StatusValue value) {}

    public record ViewedAuditField(String decision, AuditValue value) {}

    public record ViewedReferenceField(String decision, ReferenceValue value) {}

    public record ViewedMetadataField(String decision, MetadataValue value) {}

    public record MaskedField(
            String decision,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object value,
            String maskReason,
            String maskedDisplayKey) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClockFieldGroups(
            Object CLOCK_EVENT,
            Object INGESTION_STATUS,
            Object QUALITY_FINDING) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExceptionFieldGroups(
            Object EXCEPTION,
            Object OWNER_DEEP_LINK,
            Object RECOVERY_METADATA) {}

    public record ClockItem(
            String itemPublicId,
            String title,
            String statusCode,
            ClockFieldGroups fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {}

    public record ExceptionItem(
            String itemPublicId,
            String title,
            String statusCode,
            ExceptionFieldGroups fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {}

    public record ClockProjection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<ClockItem> items) {}

    public record ExceptionProjection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<ExceptionItem> items) {}

    private ClockIaQueryModels() {}
}
