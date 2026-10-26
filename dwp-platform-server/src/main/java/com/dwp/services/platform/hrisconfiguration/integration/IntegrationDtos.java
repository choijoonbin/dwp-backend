package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed owner DTOs for the twenty SYS-008 owner API operations. */
public final class IntegrationDtos {

    private IntegrationDtos() {}

    public enum DefinitionState {
        DRAFT, VALIDATED, TESTED, PENDING_APPROVAL, APPROVED,
        ACTIVE, SUSPENDED, RETIRED
    }

    public enum MappingState {
        DRAFT, VALIDATED, PENDING_APPROVAL, APPROVED,
        ACTIVE, SUSPENDED, RETIRED
    }

    public enum ExecutionState {
        ACCEPTED, TRANSFORMING, DISPATCHED, RESULT_UNKNOWN, ACKNOWLEDGED,
        RECONCILING, RECONCILED, PARTIAL, FAILED, DEAD_LETTER, CANCELLED
    }

    public enum ItemState {
        PENDING, TRANSFORMED, DISPATCHED, RESULT_UNKNOWN, ACCEPTED,
        REJECTED_RETRYABLE, REJECTED_FINAL, RECONCILED, CANCELLED
    }

    public enum AttemptState {
        READY, DISPATCHED, RESULT_UNKNOWN, ACKNOWLEDGED,
        RECONCILED, FAILED, CANCELLED
    }

    public enum ReceiptStatus {
        ACCEPTED, RUNNING, SUCCEEDED, REJECTED, FAILED, RESULT_UNKNOWN
    }

    public enum ConnectorType {
        HTTPS, SFTP, OBJECT_STORE, ERP, BANK, STATUTORY_PROVIDER
    }

    public enum CompatibilityMode { BACKWARD, FORWARD, FULL, EXACT }

    public enum Operation { IMPORT, EXPORT, DISPATCH, RECONCILE }

    public record ConnectorCommand(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9.-]{2,63}$")
            String connectorKey,
            @NotNull ConnectorType type,
            @NotNull URI endpoint,
            @NotBlank @Pattern(regexp = "^secret_[A-Za-z0-9_-]{16,128}$")
            String secretRef,
            @NotBlank @Size(max = 128) String egressPolicyKey) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown connector command property: " + name);
        }
    }

    public record ConnectorTestCommand(
            @NotBlank @Pattern(regexp = "^(AUTHENTICATION|CONNECTIVITY|SCHEMA)$")
            String testMode,
            @NotBlank @Size(max = 256) String sampleRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sampleDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown connector-test property: " + name);
        }
    }

    public record MappingDryRunCommand(
            @NotBlank @Size(max = 256) String sampleRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sampleDigest,
            @Min(1) @Max(1000) int maxRows,
            @NotNull OffsetDateTime asOf) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown mapping dry-run property: " + name);
        }
    }

    public record DefinitionValidationCommand(
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String definitionDigest,
            @Min(1) long endpointPolicyRevision,
            @Min(1) long secretRevision) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown definition-validation property: " + name);
        }
    }

    public record MappingCommand(
            @Min(1) long sourceSchemaVersion,
            @Min(1) long targetSchemaVersion,
            @NotBlank @Pattern(regexp = "^ref_[A-Za-z0-9_-]{16,240}$")
            String mappingRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String mappingDigest,
            @NotNull CompatibilityMode compatibilityMode,
            @NotNull OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo) {

        public MappingCommand {
            if (effectiveFrom != null && effectiveTo != null
                    && !effectiveTo.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException(
                        "effectiveTo must be later than effectiveFrom");
            }
        }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown mapping command property: " + name);
        }
    }

    public record MappingValidationCommand(
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String mappingDigest,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sourceSchemaDigest,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String targetSchemaDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown mapping-validation property: " + name);
        }
    }

    public record ApprovalRequestCommand(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9.-]{2,127}$")
            String approvalWorkflowKey,
            @Min(1) long subjectRevision,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String payloadDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown approval-request property: " + name);
        }
    }

    public record ApprovalDecisionCommand(
            @NotNull UUID approvalReceiptId,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String decisionDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown approval-decision property: " + name);
        }
    }

    public record ActivationCommand(
            @NotNull UUID approvalReceiptId,
            @NotNull OffsetDateTime effectiveAt) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown activation property: " + name);
        }
    }

    public record ExecutionCommand(
            @NotNull Operation operation,
            @NotBlank @Size(max = 256) String payloadRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String payloadDigest,
            @NotNull OffsetDateTime asOf) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown execution property: " + name);
        }
    }

    public record ReconcileCommand(
            @NotBlank @Pattern(regexp = "^ref_[A-Za-z0-9_-]{16,240}$")
            String providerReceiptRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$")
            String providerReceiptDigest,
            @Min(0) long expectedSourceCount,
            @NotNull OffsetDateTime asOf) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown reconcile property: " + name);
        }
    }

    public record RetryCommand(
            @NotNull @Size(min = 1, max = 10_000) List<UUID> failedItemIds,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$")
            String failedItemSetDigest,
            @Min(1) @Max(10_000) int expectedRejectedCount,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$")
            String reasonCode) {

        public RetryCommand {
            failedItemIds = failedItemIds == null
                    ? List.of() : List.copyOf(failedItemIds);
            if (failedItemIds.stream().distinct().count() != failedItemIds.size()) {
                throw new IllegalArgumentException("failedItemIds must be unique");
            }
        }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown retry property: " + name);
        }
    }

    public record CancelCommand(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$")
            String reasonCode,
            @Pattern(regexp = "^policy_[A-Za-z0-9_-]{16,200}$")
            String compensationPolicyRef) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown cancel property: " + name);
        }
    }

    public record ActionDescriptor(
            String method,
            String actionKey,
            String targetType,
            UUID targetPublicId,
            Long targetVersion,
            Long expectedRevision,
            String href,
            boolean stepUpRequired) {}

    public record ConnectorSummary(
            UUID publicId,
            String key,
            String type,
            String state,
            long version,
            OffsetDateTime updatedAt) {}

    public record CatalogResponse(
            OffsetDateTime asOf,
            List<ConnectorSummary> items,
            String nextCursor,
            OffsetDateTime freshUntil,
            OffsetDateTime staleAfter) {

        public CatalogResponse {
            items = List.copyOf(items);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            ReceiptStatus status,
            UUID correlationId,
            String requestDigest,
            OffsetDateTime createdAt,
            Long aggregateVersion,
            String resultRef) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutionResponse(
            UUID executionId,
            long mappingVersion,
            ExecutionState state,
            long sourceCount,
            long acceptedCount,
            long rejectedCount,
            long selectedItemCount,
            String selectedItemDigest,
            String reconciliationDigest,
            long aggregateVersion) {}

    public record FieldPolicy(
            Set<String> visible,
            Set<String> masked,
            Set<String> omitted,
            long revision) {

        public FieldPolicy {
            visible = Set.copyOf(visible);
            masked = Set.copyOf(masked);
            omitted = Set.copyOf(omitted);
            if (!java.util.Collections.disjoint(visible, masked)
                    || !java.util.Collections.disjoint(visible, omitted)
                    || !java.util.Collections.disjoint(masked, omitted)) {
                throw new IllegalArgumentException(
                        "field policy decisions must be disjoint");
            }
        }
    }

}
