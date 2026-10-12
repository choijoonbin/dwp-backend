package com.dwp.services.people.hris.employeeservice.employeechange;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed transport and IAQ-009 projection types for the employee-change family. */
public final class EmployeeChangeDtos {

    public static final String DEEP_LINK_ROUTE =
            "/hr/operations/people/employee-change-requests";
    public static final String CREATE_ACTION =
            "hris.action.hrm.create-employee-change-request";
    public static final String SUBMIT_ACTION =
            "hris.action.hrm.submit-employee-change-request";
    public static final String APPLY_ACTION =
            "hris.action.hrm.apply-employee-change-request";

    private EmployeeChangeDtos() {
    }

    @Schema(name = "EmployeeChangeRequestCreateV1")
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRequest(
            @NotNull UUID targetPersonPublicId,
            @NotBlank @Pattern(regexp =
                    "PERSON|WORKER|EMPLOYMENT_RELATIONSHIP|ASSIGNMENT")
            String targetAggregateType,
            @NotNull UUID targetAggregatePublicId,
            @NotBlank @Size(max = 64) String changeType,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Size(max = 128) String patchSchemaKey,
            @NotNull @Min(1) Integer patchSchemaVersion,
            @NotEmpty @Size(max = 100) List<@Valid PatchOperation> patch,
            @NotNull @Size(max = 20) List<@Valid EvidenceReference> evidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PatchOperation(
            @NotBlank @Pattern(regexp = "add|replace|remove") String op,
            @NotBlank @Pattern(regexp = "^/.*") String path,
            Object value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EvidenceReference(
            @NotBlank @Pattern(regexp = "^obj_[A-Za-z0-9_-]{16,128}$") String objectRef,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String digest) {
    }

    @Schema(name = "EmptyCommandV1")
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EmptyCommand() {
    }

    public record AggregateFilter(
            @Min(0) Integer minimumItemCount,
            @Min(0) Integer maximumItemCount) {
    }

    public record StatusFilter(
            @Size(max = 50) List<String> codes,
            @Min(1) Long minimumRevision) {
    }

    public record EffectiveRangeFilter(
            LocalDate activeOn,
            LocalDate from,
            LocalDate to) {
    }

    public record WorkbenchFilter(
            AggregateFilter changeRequestSummary,
            StatusFilter approvalStatus,
            EffectiveRangeFilter employment,
            LocalDate effectiveOn) {
        public static WorkbenchFilter empty() {
            return new WorkbenchFilter(null, null, null, null);
        }
    }

    public record WorkbenchQuery(
            @Min(1) @Max(100) int limit,
            @NotNull WorkbenchFilter filter,
            @NotEmpty @Size(max = 3) List<String> sort,
            @NotBlank @Size(max = 35) String locale,
            @NotBlank @Size(max = 255) String timeZone,
            @NotNull UUID correlationId,
            Instant asOf,
            String cursor) {
    }

    public record WorkbenchProjection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<WorkbenchItem> items) {
        public WorkbenchProjection {
            items = List.copyOf(items);
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
            partialFailures = List.copyOf(partialFailures);
        }
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
            long maxAgeSeconds,
            String invalidationEvent,
            String authorizationDecisionRevision) {
    }

    public record WorkbenchItem(
            String itemPublicId,
            String title,
            String statusCode,
            Map<String, FieldDecision<?>> fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {
        public WorkbenchItem {
            fieldGroups = Map.copyOf(fieldGroups);
            availableActions = List.copyOf(availableActions);
        }
    }

    public sealed interface FieldDecision<T>
            permits ViewFieldDecision, MaskFieldDecision {
        String decision();

        static <T> FieldDecision<T> view(T value) {
            return new ViewFieldDecision<>("VIEW", value);
        }

        static <T> FieldDecision<T> mask(String reason, String displayKey) {
            return new MaskFieldDecision<>("MASK", null, reason, displayKey);
        }
    }

    public record ViewFieldDecision<T>(String decision, T value)
            implements FieldDecision<T> {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MaskFieldDecision<T>(
            String decision,
            T value,
            String maskReason,
            String maskedDisplayKey) implements FieldDecision<T> {
    }

    public record AggregateValue(int itemCount, long revision, Instant asOf) {
    }

    public record StatusValue(String code, long revision) {
    }

    public record EffectiveRangeValue(
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long revision) {
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
