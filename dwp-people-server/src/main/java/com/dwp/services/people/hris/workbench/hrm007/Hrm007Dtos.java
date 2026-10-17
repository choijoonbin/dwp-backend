package com.dwp.services.people.hris.workbench.hrm007;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed request and response types for IAQ-005, IAQ-007, and IAQ-013. */
public final class Hrm007Dtos {

    public static final String PROJECTION_VERSION = "1";
    public static final String INVALIDATION_EVENT =
            "dwp.hris.access.assignment.changed.v1";

    private Hrm007Dtos() {
    }

    public record QueryRequest(
            int limit,
            Object filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            String cursor) {
    }

    public record PeopleChangesFilter(
            AggregateFilter changeRequestSummary,
            StatusFilter limitedEmployment,
            StatusFilter approvalStatus,
            LocalDate effectiveOn) {
    }

    public record AppointmentsFilter(
            TemporalFilter appointment,
            ReferenceFilter assignment,
            EffectiveRangeFilter employment,
            LocalDate effectiveOn) {
    }

    public record PeopleEmploymentFilter(
            PolicyFilter policyMetadata,
            PolicyFilter employmentRule,
            AggregateFilter impactSummary,
            LocalDate effectiveOn) {
    }

    public record AggregateFilter(Integer minimumItemCount, Integer maximumItemCount) {
    }

    public record StatusFilter(List<String> codes, Long minimumRevision) {
    }

    public record TemporalFilter(
            Instant startFrom,
            Instant startTo,
            Instant endFrom,
            Instant endTo) {
    }

    public record ReferenceFilter(List<String> codes, List<String> publicIds) {
    }

    public record EffectiveRangeFilter(LocalDate activeOn, LocalDate from, LocalDate to) {
    }

    public record PolicyFilter(List<String> policyKeys, Long minimumRevision) {
    }

    public record Projection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<Item> items) {

        public Projection {
            items = items == null ? List.of() : List.copyOf(items);
        }
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

        public QueryState {
            partialFailures = partialFailures == null
                    ? List.of() : List.copyOf(partialFailures);
        }
    }

    public record FieldDecisionSummary(int viewCount, int maskedCount, int omittedCount) {
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
            Map<String, Object> fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {

        public Item {
            fieldGroups = fieldGroups == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(fieldGroups));
            availableActions = availableActions == null
                    ? List.of() : List.copyOf(availableActions);
        }
    }

    public record ViewedField(String decision, Object value) {
        public ViewedField(Object value) {
            this("VIEW", value);
        }
    }

    public record MaskedField(
            String decision,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object value,
            String maskReason,
            String maskedDisplayKey) {

        public MaskedField(String maskReason, String maskedDisplayKey) {
            this("MASK", null, maskReason, maskedDisplayKey);
        }
    }

    public record AggregateValue(int itemCount, long revision, Instant asOf) {
    }

    public record StatusValue(String code, long revision) {
    }

    public record TemporalValue(Instant startAt, Instant endAt, long durationMinutes) {
    }

    public record ReferenceValue(
            String publicId,
            String code,
            String label,
            long revision) {
    }

    public record EffectiveRangeValue(
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long revision) {
    }

    public record PolicyValue(
            String policyKey,
            long revision,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status) {
    }

    public record DeepLink(String route, String resourcePublicId) {
    }

    public record AvailableAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
    }
}
