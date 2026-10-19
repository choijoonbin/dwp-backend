package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReferenceConfigurationDtos {

    private ReferenceConfigurationDtos() {
    }

    public enum Domain { HRM, PER, TIM, PAY, SYS }

    /** Exact population boundary allocated by PEP-SYS-011..047. */
    public enum ScopeType { LEGAL_ENTITY, POLICY_NODE }

    public enum LifecycleState {
        DRAFT, VALIDATED, SIMULATED, PENDING_APPROVAL, APPROVED, SCHEDULED,
        PUBLISHED, REJECTED, RETIRED, FAILED_ACTIVATION
    }

    public enum QueryState { COMPLETE, EMPTY, PARTIAL, UNAVAILABLE }

    public enum Freshness { CURRENT, STALE, UNAVAILABLE }

    public enum FreshnessPreference { FRESH_ONLY, ALLOW_STALE }

    /** OMIT is represented by the absence of a field group in the wire projection. */
    public enum FieldDecision { VIEW, MASK, OMIT }

    public record PopulationScope(
            @NotNull ScopeType type,
            @NotNull UUID id,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String digest,
            @NotBlank @Size(max = 128) String decisionRevision) {
    }

    public record PayloadReference(
            @NotBlank @Size(max = 256) String reference,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String digest) {
    }

    public record CreateVersionCommand(
            @NotNull Domain domain,
            @NotNull OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            @NotBlank @Size(max = 128) String schemaKey,
            @NotNull @Min(1) Integer schemaVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid PayloadReference payload,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode ownerPayload) {
        public CreateVersionCommand(Domain domain, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                    String schemaKey, Integer schemaVersion, PayloadReference payload) {
            this(domain, effectiveFrom, effectiveTo, schemaKey, schemaVersion, payload, null);
        }
        @Override public String toString() { return "CreateVersionCommand[ownerPayload=REDACTED]"; }
    }

    public record ValidationCommand(
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String inputScopeDigest,
            @NotNull @Min(1) Integer schemaRevision) {
    }

    public record SimulationCommand(
            @NotNull UUID validationReceiptId,
            @NotBlank @Pattern(regexp = "^ref_[A-Za-z0-9_-]{16,240}$") String sampleScopeRef,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String sampleScopeDigest) {
    }

    public record SubmitCommand(
            @NotNull UUID validationReceiptId,
            @NotNull UUID simulationReceiptId,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String impactDigest,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9.-]{2,127}$") String approvalWorkflowKey) {
    }

    public record ApprovalCommand(
            @NotNull UUID approvalReceiptId,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String approvalDecisionDigest) {
    }

    public record PublishCommand(
            @NotNull UUID approvalReceiptId,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String impactDigest,
            @NotNull OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo) {
    }

    public record ScheduleCommand(
            @NotNull UUID approvalReceiptId,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String impactDigest,
            @NotNull OffsetDateTime publishAt,
            @NotNull OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo) {
    }

    public record RollbackCommand(
            @NotNull @Min(1) Long targetPublishedVersion,
            @NotNull UUID approvalReceiptId,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String impactDigest,
            @NotNull OffsetDateTime effectiveFrom,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String reasonCode) {
    }

    public record RetireCommand(
            @NotNull UUID approvalReceiptId,
            @NotNull OffsetDateTime effectiveTo,
            @Min(1) Long replacementVersion,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$") String reasonCode) {
    }

    public record ValidationFact(
            UUID receiptId, String evidenceDigest, int schemaRevision,
            String populationScopeDigest, OffsetDateTime producedAt) {
    }

    public record SimulationFact(
            UUID receiptId, UUID validationReceiptId, String evidenceDigest,
            String impactDigest, OffsetDateTime producedAt, OffsetDateTime freshUntil) {
    }

    public record SubmissionFact(
            UUID receiptId, UUID validationReceiptId, UUID simulationReceiptId,
            String evidenceDigest, String approvalWorkflowKey, OffsetDateTime producedAt) {
    }

    public record ApprovalFact(
            UUID receiptId, UUID submissionReceiptId, String decisionDigest,
            String evidenceDigest, OffsetDateTime producedAt) {
    }

    public record VersionDetail(
            UUID configurationId,
            long version,
            Domain domain,
            ScopeType scopeType,
            UUID scopeId,
            LifecycleState state,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String schemaKey,
            int schemaVersion,
            String payloadReference,
            String payloadDigest,
            ValidationFact validation,
            SimulationFact simulation,
            SubmissionFact submission,
            ApprovalFact approval,
            long aggregateVersion,
            Long authoredBy,
            Long approvedBy,
            OffsetDateTime scheduledAt,
            Long supersedesVersion,
            Long replacementVersion,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record VersionResponse(
            UUID configurationId,
            long version,
            LifecycleState state,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            String schemaKey,
            int schemaVersion,
            String payloadDigest,
            UUID approvalReceiptId,
            long aggregateVersion) {
    }

    public record CatalogItem(
            UUID publicId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String key,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("type") Domain domain,
            @JsonInclude(JsonInclude.Include.NON_NULL) LifecycleState state,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long version,
            @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime updatedAt) {
    }

    public record CatalogResponse(
            OffsetDateTime asOf,
            @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor,
            List<CatalogItem> items,
            OffsetDateTime freshUntil,
            OffsetDateTime staleAfter) {
    }

    public record CatalogFilter(
            String key, Domain domain, LifecycleState state, Long minimumVersion) {
    }

    public record CatalogQuery(
            OffsetDateTime asOf,
            String cursor,
            String filter,
            List<String> fields,
            FreshnessPreference freshness,
            int limit) {
    }

    public record CommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String status,
            UUID correlationId,
            String requestDigest,
            OffsetDateTime createdAt,
            long aggregateVersion,
            String resultRef) {
    }

    public record WorkbenchFilter(
            List<String> policyKeys,
            Long minimumPolicyRevision,
            List<String> referencePublicIds,
            List<String> referenceCodes,
            List<String> effectiveStatuses,
            Long minimumStatusRevision,
            LocalDate effectiveOn) {
    }

    public record WorkbenchQuery(
            OffsetDateTime asOf,
            String cursor,
            @NotNull @Min(1) @Max(100) Integer limit,
            @NotNull WorkbenchFilter filter,
            @NotNull @Size(min = 1, max = 3) List<String> sort,
            @NotBlank @Size(min = 2, max = 35) String locale,
            @NotBlank @Size(max = 255) String timeZone,
            @NotNull UUID correlationId,
            List<String> fields,
            FreshnessPreference freshness) {

        public WorkbenchQuery(
                OffsetDateTime asOf,
                String cursor,
                Integer limit,
                WorkbenchFilter filter,
                List<String> sort,
                String locale,
                String timeZone,
                UUID correlationId) {
            this(asOf, cursor, limit, filter, sort, locale, timeZone, correlationId,
                    List.of("POLICY_VERSION", "REFERENCE_DATA", "EFFECTIVE_STATUS"),
                    FreshnessPreference.FRESH_ONLY);
        }
    }

    public record FieldValue<T>(
            FieldDecision decision,
            T value,
            @JsonInclude(JsonInclude.Include.NON_NULL) String maskReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) String maskedDisplayKey) {
        public static <T> FieldValue<T> view(T value) {
            return new FieldValue<>(FieldDecision.VIEW, value, null, null);
        }

        public static <T> FieldValue<T> masked(String reason, String displayKey) {
            return new FieldValue<>(FieldDecision.MASK, null, reason, displayKey);
        }
    }

    public record PolicyValue(
            String policyKey, long revision, LocalDate effectiveFrom,
            LocalDate effectiveTo, String status) {
    }

    public record ReferenceValue(String publicId, String code, String label, long revision) {
    }

    public record StatusValue(String code, long revision) {
    }

    public record DeepLink(String route, String resourcePublicId) {
    }

    public record AvailableAction(
            String actionKey, String method, String hrefTemplate,
            String idempotencyPolicy, String stepUpPolicy) {
    }

    public record WorkbenchItem(
            String itemPublicId, String title, String statusCode,
            Map<String, FieldValue<?>> fieldGroups, DeepLink deepLink,
            List<AvailableAction> availableActions) {
    }

    public record FieldDecisionSummary(long viewCount, long maskedCount, long omittedCount) {
    }

    public record PartialFailure(
            String componentKey, String errorCode, UUID correlationId, boolean retryable) {
    }

    public record CachePolicy(
            String authorizationDecisionRevision, String invalidationEvent, int maxAgeSeconds) {
    }

    public record WorkbenchQueryState(
            QueryState state,
            String scopeDecisionRevision,
            Freshness freshness,
            String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {
    }

    public record WorkbenchResponse(
            String projectionVersion,
            OffsetDateTime asOf,
            String variantKey,
            WorkbenchQueryState queryState,
            List<WorkbenchItem> items) {
    }
}
