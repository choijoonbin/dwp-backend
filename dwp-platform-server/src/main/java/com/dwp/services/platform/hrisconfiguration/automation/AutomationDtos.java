package com.dwp.services.platform.hrisconfiguration.automation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed owner DTOs for the sixteen SYS-006 operations. */
public final class AutomationDtos {

    private AutomationDtos() {
    }

    public enum OwnerDomain { HRM, PER, TIM, PAY }
    public enum VersionState {
        DRAFT, VALIDATED, PENDING_APPROVAL, APPROVED, ACTIVE, SUSPENDED, RETIRED
    }
    public enum ScheduleState {
        DRAFT, PENDING_APPROVAL, APPROVED, ACTIVE, SUSPENDED
    }
    public enum RunState {
        QUEUED, LEASED, RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELLED, DEAD_LETTER
    }
    public enum ItemState {
        PENDING, LEASED, RUNNING, SUCCEEDED, FAILED_RETRYABLE,
        FAILED_FINAL, CANCELLED, DEAD_LETTER
    }
    public enum EffectState {
        NOT_STARTED, PREPARED, NO_EFFECT_CONFIRMED, COMMITTED, RESULT_UNKNOWN
    }
    public enum CompensationState {
        NOT_REQUIRED, PENDING, RUNNING, SUCCEEDED, FAILED, RESULT_UNKNOWN
    }
    public enum RetryMode { NONE, FAILED_ITEMS_ONLY, WHOLE_INVOCATION }
    public enum SelectionMode { ALL_ITEMS, FAILED_ITEMS_ONLY }
    public enum ReceiptStatus {
        ACCEPTED, RUNNING, SUCCEEDED, REJECTED, FAILED, RESULT_UNKNOWN
    }
    public enum CancelEligibility {
        ELIGIBLE_NO_EFFECT, ELIGIBLE_ALL_REQUIRED_PATHS, INELIGIBLE
    }

    /** SYS-API-017.POST is identity-only; executable fields are deliberately absent. */
    public record CreateDefinitionCommand(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9.-]{2,63}$")
            String automationKey,
            @NotBlank @Size(max = 120) String displayName,
            @Size(min = 1, max = 1000) String description,
            @NotNull OwnerDomain ownerDomain) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown create-definition property: " + name);
        }
    }

    /** Signed immutable PDX-004 version candidate. */
    public record CreateVersionCommand(
            @NotNull JsonNode definitionPayload,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String definitionDigest,
            @NotNull JsonNode executionPolicy,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String executionPolicyDigest,
            @NotBlank @Size(max = 300) String manualConcurrencyKeyTemplate,
            @Size(max = 240) String compensationPolicyRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String compensationPolicyDigest,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9._-]{2,127}$")
            String handlerOwnerService,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9._-]{2,127}$")
            String handlerKey,
            @NotBlank @Pattern(
                    regexp = "^[A-Za-z][A-Za-z0-9._-]{0,127}\\.v[1-9][0-9]*$")
            String handlerContractVersion,
            @Min(1) @Max(Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER)
            long handlerManifestRevision,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String handlerManifestDigest,
            @NotBlank @Size(max = 160) String signatureKeyId,
            @NotBlank @Size(max = 2048) String signature,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String signatureDigest,
            @NotBlank @Pattern(
                    regexp = "^[A-Za-z0-9][A-Za-z0-9:/._-]{0,511}$")
            String inputSchemaRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String inputSchemaDigest,
            @NotBlank @Pattern(
                    regexp = "^[A-Za-z0-9][A-Za-z0-9:/._-]{0,511}$")
            String receiptSchemaRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String receiptSchemaDigest,
            @NotNull @Size(min = 1, max = 6)
            List<@Pattern(regexp = "^[A-Z][A-Z0-9_]{2,39}$") String> allowedScopeTypes,
            @NotNull RetryMode retryMode,
            @Min(1) @Max(Sys006TypedArtifactContract.MAX_ATTEMPTS) int maxAttempts) {

        public CreateVersionCommand {
            allowedScopeTypes = allowedScopeTypes == null
                    ? List.of() : List.copyOf(allowedScopeTypes);
            if (!Set.of("GLOBAL", "TENANT", "LEGAL_ENTITY", "ORGANIZATION",
                    "POPULATION", "SUBJECT").containsAll(allowedScopeTypes)
                    || allowedScopeTypes.stream().distinct().count()
                    != allowedScopeTypes.size()) {
                throw new IllegalArgumentException(
                        "Only unique canonical PDX-004 scope types are allowed");
            }
        }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown create-version property: " + name);
        }
    }

    public record LifecycleCommand(
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String evidenceDigest,
            @Size(min = 1, max = 1000) String reason) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown lifecycle property: " + name);
        }
    }

    public record CreateScheduleCommand(
            @Min(1) @Max(Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER)
            long pinnedVersionNumber,
            @NotBlank @Size(max = 240) String expression,
            @NotBlank @Size(max = 80) String timezone,
            @NotBlank @Size(max = 80) String timezoneDatabaseRevision,
            @NotBlank @Size(max = 240) String inputTemplateRef,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String inputTemplateDigest,
            @NotBlank @Size(max = 300) String concurrencyKeyTemplate,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String schedulePolicyDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown create-schedule property: " + name);
        }
    }

    public record QueueRunCommand(
            @NotBlank @Pattern(
                    regexp = "^(GLOBAL|TENANT|LEGAL_ENTITY|ORGANIZATION|POPULATION|SUBJECT)$")
            String scopeType,
            UUID scopeId,
            @NotBlank @Pattern(
                    regexp = "^[A-Za-z0-9][A-Za-z0-9:/._-]{0,511}$")
            String inputReference,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String inputDigest,
            @NotNull @Min(0) @Max(1_000_000_000L) Long expectedItemCount) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown queue-run property: " + name);
        }
    }

    public record RetryRunCommand(
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String evidenceDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown retry-run property: " + name);
        }
    }

    public record CancelRunCommand(
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String evidenceDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown cancel-run property: " + name);
        }
    }

    public record ActionDescriptor(
            String method,
            String actionKey,
            String targetResourceType,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID targetPublicId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long targetVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long expectedObjectRevision,
            String resolvedHref,
            String idempotencyPolicy,
            String stepUpPolicy) {
    }

    public sealed interface CatalogItem
            permits DefinitionItem, VersionItem, ScheduleItem {
        String resourceType();
    }

    public record DefinitionItem(
            String resourceType,
            Object definitionPublicId,
            Object automationKey,
            Object ownerDomain,
            Object derivedLifecycleState,
            Object activeVersionNumber,
            Object objectRevision,
            List<ActionDescriptor> availableActions) implements CatalogItem {
        public DefinitionItem {
            availableActions = List.copyOf(availableActions);
        }
    }

    public record VersionItem(
            String resourceType,
            Object definitionPublicId,
            Object versionNumber,
            Object lifecycleState,
            Object handlerKey,
            Object handlerContractVersion,
            Object objectRevision,
            List<ActionDescriptor> availableActions) implements CatalogItem {
        public VersionItem {
            availableActions = List.copyOf(availableActions);
        }
    }

    public record ScheduleItem(
            String resourceType,
            Object schedulePublicId,
            Object definitionPublicId,
            Object pinnedVersionNumber,
            Object lifecycleState,
            Object timezone,
            Object nextFireAt,
            Object objectRevision,
            List<ActionDescriptor> availableActions) implements CatalogItem {
        public ScheduleItem {
            availableActions = List.copyOf(availableActions);
        }
    }

    public record MaskedField(String decision, String displayValue) {
        public MaskedField() {
            this("MASK", "••••");
        }
    }

    public record OmittedField(String decision) {
        public OmittedField() {
            this("OMIT");
        }
    }

    public record FieldDecisionSummary(
            Set<String> visible,
            Set<String> masked,
            Set<String> omitted,
            long fieldPolicyRevision) {
        public FieldDecisionSummary {
            visible = Set.copyOf(visible);
            masked = Set.copyOf(masked);
            omitted = Set.copyOf(omitted);
        }
    }

    public record CatalogResponse(
            OffsetDateTime asOf,
            List<CatalogItem> items,
            String nextCursor,
            OffsetDateTime freshUntil,
            OffsetDateTime staleAfter,
            List<ActionDescriptor> collectionActions,
            FieldDecisionSummary fieldDecisionSummary,
            long authorizationRevision,
            UUID correlationId) {
        public CatalogResponse {
            items = List.copyOf(items);
            collectionActions = List.copyOf(collectionActions);
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
            String resultRef) {
    }

    public record AttemptProjection(
            int attemptNumber,
            SelectionMode selectionMode,
            long selectedItemCount,
            String selectedItemDigest,
            RunState state,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {
    }

    public record ItemProjection(
            UUID itemPublicId,
            long itemSequence,
            ItemState state,
            int attemptCount,
            String resultDigest,
            String errorCode,
            EffectState effectState,
            CompensationState compensationState) {
    }

    public record CheckpointProjection(
            UUID checkpointPublicId,
            int attemptNumber,
            long checkpointOrdinal,
            long processedItemCount,
            String processedItemDigest,
            String outputDigest,
            OffsetDateTime committedAt) {
    }

    public record RunDetailResponse(
            UUID runPublicId,
            UUID definitionPublicId,
            long versionNumber,
            RunState state,
            long objectRevision,
            SelectionMode selectionMode,
            long itemTotal,
            long itemSucceeded,
            long itemFailed,
            String outputDigest,
            int retryBudgetRemaining,
            long retryEligibleItemCount,
            CancelEligibility cancelEligibility,
            List<AttemptProjection> attempts,
            List<ItemProjection> items,
            List<CheckpointProjection> checkpoints,
            String nextAttemptCursor,
            String nextItemCursor,
            String nextCheckpointCursor,
            List<ActionDescriptor> availableActions,
            long authorizationRevision,
            UUID correlationId) {
        public RunDetailResponse {
            attempts = List.copyOf(attempts);
            items = List.copyOf(items);
            checkpoints = List.copyOf(checkpoints);
            availableActions = List.copyOf(availableActions);
        }
    }
}
