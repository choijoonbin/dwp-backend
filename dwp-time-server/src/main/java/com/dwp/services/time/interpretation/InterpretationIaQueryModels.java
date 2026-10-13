package com.dwp.services.time.interpretation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact IAQ-041 request and INTERPRETATION projection transport shapes. */
public final class InterpretationIaQueryModels {

    private static final Set<Integer> PROBLEM_STATUSES = Set.of(
            400, 401, 403, 404, 409, 429, 503);

    public record StatusFilter(List<String> codes, Long minimumRevision) {
        public StatusFilter {
            codes = codes == null ? null : List.copyOf(codes);
        }
    }

    public record PolicyFilter(List<String> policyKeys, Long minimumRevision) {
        public PolicyFilter {
            policyKeys = policyKeys == null ? null : List.copyOf(policyKeys);
        }
    }

    public record AggregateFilter(Long minimumItemCount, Long maximumItemCount) {}

    public record InterpretationFilter(
            StatusFilter interpretationResult,
            PolicyFilter ruleVersion,
            AggregateFilter traceSummary,
            LocalDate effectiveOn) {

        public static InterpretationFilter empty() {
            return new InterpretationFilter(null, null, null, null);
        }
    }

    public record InterpretationQueryRequest(
            int limit,
            InterpretationFilter filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            String cursor) {

        public InterpretationQueryRequest {
            sort = sort == null ? null : List.copyOf(sort);
        }
    }

    public record QueryState(
            String state,
            String scopeDecisionRevision,
            String freshness,
            @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {

        public QueryState {
            partialFailures = partialFailures == null ? List.of() : List.copyOf(partialFailures);
        }
    }

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

    public record StatusValue(String code, long revision) {}

    public record PolicyValue(
            String policyKey,
            long revision,
            LocalDate effectiveFrom,
            @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveTo,
            String status) {}

    public record AggregateValue(long itemCount, long revision, Instant asOf) {}

    public sealed interface FieldDecision<T>
            permits ViewedField, MaskedField {

        String decision();

        T value();

        static <T> FieldDecision<T> view(T value) {
            return new ViewedField<>("VIEW", value);
        }

        static <T> FieldDecision<T> mask(String reason, String displayKey) {
            return new MaskedField<>("MASK", null, reason, displayKey);
        }
    }

    public record ViewedField<T>(String decision, T value) implements FieldDecision<T> {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MaskedField<T>(
            String decision,
            T value,
            String maskReason,
            String maskedDisplayKey) implements FieldDecision<T> {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InterpretationFieldGroups(
            FieldDecision<StatusValue> INTERPRETATION_RESULT,
            FieldDecision<PolicyValue> RULE_VERSION,
            FieldDecision<AggregateValue> TRACE_SUMMARY) {}

    public record InterpretationItem(
            String itemPublicId,
            String title,
            String statusCode,
            InterpretationFieldGroups fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {

        public InterpretationItem {
            availableActions = availableActions == null
                    ? List.of()
                    : List.copyOf(availableActions);
        }
    }

    public record InterpretationProjection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<InterpretationItem> items) {

        public InterpretationProjection {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** The exact closed error shape referenced by the IAQ-041 contract. */
    public record Problem(
            String type,
            String title,
            int status,
            String code,
            UUID correlationId,
            boolean retryable) {

        public Problem {
            if (type == null || !URI.create(type).isAbsolute()
                    || title == null || title.isBlank() || title.length() > 256
                    || !PROBLEM_STATUSES.contains(status)
                    || code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")
                    || correlationId == null) {
                throw new IllegalArgumentException("IAQ-041 problem details are invalid");
            }
        }
    }

    private InterpretationIaQueryModels() {}
}
