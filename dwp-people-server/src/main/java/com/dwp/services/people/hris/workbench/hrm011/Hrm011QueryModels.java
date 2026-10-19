package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed IAQ-008 v2 transport and projection model. */
public final class Hrm011QueryModels {
    public static final String PROJECTION_VERSION = "2";
    public static final String VARIANT = "CONTRACTS_COMPENSATION_BASIS";
    public static final String INVALIDATION_EVENT =
            "dwp.hris.access.assignment.changed.v1";

    private Hrm011QueryModels() {
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
            MetadataFilter contractMetadata,
            MoneyFilter compensationBasis,
            LocalDate effectiveOn) {
    }

    public record MetadataFilter(List<String> keys, List<String> versions) {
        public MetadataFilter {
            keys = keys == null ? List.of() : List.copyOf(keys);
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    public record MoneyFilter(
            String currency,
            String minimumAmount,
            String maximumAmount) {
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
            String componentKey, String errorCode, UUID correlationId, boolean retryable) {
    }

    public record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
    }

    public record Item(
            String itemPublicId,
            long resourceVersion,
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

    public record MetadataValue(String key, String version, String digest) {
    }

    public record MoneyValue(String amount, String currency) {
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

    /** One SQL row; service rejects zero/multiple rows per relationship. */
    public record SourceRow(
            long workerId,
            UUID subjectPersonPublicId,
            String title,
            EmploymentContractDtos.RelationshipSnapshot relationship,
            EmploymentContractDtos.AssignmentSnapshot assignment,
            EmploymentContractDtos.CompensationBasisSnapshot compensationBasis,
            long authoritativeCandidateCount) {
    }
}
