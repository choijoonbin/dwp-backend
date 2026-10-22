package com.dwp.services.people.hris.workbench.hrm012;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed IAQ-012 transport and redacted projection model. */
public final class Hrm012QueryModels {
    public static final String PROJECTION_VERSION = "1";
    public static final String VARIANT = "DATA_QUALITY";
    public static final String INVALIDATION_EVENT =
            "dwp.hris.access.assignment.changed.v1";
    public static final String DEEP_LINK = "/hr/operations/people/data-quality";

    private Hrm012QueryModels() {
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
        public QueryRequest {
            sort = sort == null ? List.of() : List.copyOf(sort);
        }
    }

    public record Filter(
            IngestionStatusFilter ingestionStatus,
            QualityFindingFilter qualityFinding,
            CommandReceiptFilter commandReceipt,
            LocalDate effectiveOn) {
    }

    public record IngestionStatusFilter(List<String> codes, Long minimumRevision) {
        public IngestionStatusFilter {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }

    public record QualityFindingFilter(
            List<String> decisionCodes, String evidenceDigest) {
        public QualityFindingFilter {
            decisionCodes = decisionCodes == null
                    ? List.of() : List.copyOf(decisionCodes);
        }
    }

    public record CommandReceiptFilter(List<UUID> receiptIds, List<String> states) {
        public CommandReceiptFilter {
            receiptIds = receiptIds == null ? List.of() : List.copyOf(receiptIds);
            states = states == null ? List.of() : List.copyOf(states);
        }
    }

    public record Projection(
            String projectionVersion,
            String variantKey,
            Instant asOf,
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
            String componentKey, String errorCode, UUID correlationId,
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
        public MaskedField(String reason, String displayKey) {
            this("MASK", null, reason, displayKey);
        }
    }

    public record StatusValue(String code, long revision) {
    }

    public record AuditValue(
            String decisionCode,
            String evidenceDigest,
            Instant occurredAt,
            long revision) {
    }

    public record ReceiptValue(
            UUID receiptId,
            String state,
            Instant occurredAt,
            long revision) {
    }

    public record DeepLink(String route, String resourcePublicId) {
    }

    /** Deliberately uninstantiated for IAQ-012; availableActions is always empty. */
    public record AvailableAction(String actionKey) {
    }

    /** One redacted run-grain row; no source value, secret or encrypted value is present. */
    public record SourceRow(
            UUID runPublicId,
            long runRevision,
            String ingestionState,
            LocalDate effectiveOn,
            Instant occurredAt,
            String findingDecisionCode,
            String findingEvidenceDigest,
            Instant findingOccurredAt,
            long findingRevision,
            UUID receiptPublicId,
            String receiptState,
            Instant receiptOccurredAt,
            long receiptRevision) {
    }
}
