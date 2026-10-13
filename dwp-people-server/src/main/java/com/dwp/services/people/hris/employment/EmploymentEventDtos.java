package com.dwp.services.people.hris.employment;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Closed transport types for BASE-TFR-HRM-008. */
public final class EmploymentEventDtos {

    public static final String PURPOSE = "HR_LIFECYCLE";

    private EmploymentEventDtos() {
    }

    @Schema(name = "EmploymentEventCommand")
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateCommand(
            @NotNull @Valid CommandContext context,
            @NotBlank @Pattern(regexp = "HIRE|CONCURRENT_EMPLOYMENT") String eventType,
            @NotNull JsonNode payload) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CommandContext(
            @Min(0) long expectedVersion,
            @NotBlank @Size(min = 16, max = 128) String idempotencyKey,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,79}$") String purpose,
            @NotNull Instant effectiveAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record OwnerRef(
            @NotBlank @Pattern(regexp = "HRM") String owner,
            @NotBlank @Size(min = 3, max = 160) String schemaId,
            @NotNull UUID publicId,
            @Min(0) long revision,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String contentHash,
            Instant validFrom,
            Instant validToExclusive) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AssignmentDraft(
            @NotNull @Valid OwnerRef organizationRef,
            @NotNull @Valid OwnerRef positionRef,
            @NotNull @Valid OwnerRef jobRef,
            @NotNull @Valid OwnerRef workLocationRef,
            @NotNull LocalDate validFrom,
            LocalDate validToExclusive,
            boolean primary,
            @NotNull BigDecimal fte) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HirePayload(
            @NotNull UUID personPublicId,
            @NotNull @Valid OwnerRef legalEmployerRef,
            @NotBlank @Pattern(regexp = "EMPLOYEE|CONTINGENT|INTERN|APPRENTICE")
            String employmentType,
            @NotNull LocalDate relationshipStart,
            @NotBlank @Pattern(regexp = "CREATE_NEW") String workerCreation,
            @NotNull @Valid AssignmentDraft assignment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConcurrentEmploymentPayload(
            @NotNull UUID workerPublicId,
            @Min(0) long expectedWorkerVersion,
            @NotNull @Valid OwnerRef legalEmployerRef,
            @NotNull LocalDate relationshipStart,
            @NotNull @Valid AssignmentDraft assignment) {
    }

    @Schema(name = "EmptyCommandV1")
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EmptyCommand() {
    }

    @Schema(name = "AssignmentImpactV1")
    public record AssignmentImpact(
            UUID eventPublicId,
            LocalDate effectiveDate,
            List<String> blockingIssues,
            List<String> warnings,
            String impactDigest) {
        public AssignmentImpact {
            blockingIssues = List.copyOf(blockingIssues);
            warnings = List.copyOf(warnings);
        }
    }

    @Schema(name = "EmploymentEventV1")
    public record EventView(
            UUID eventPublicId,
            String eventType,
            String lifecycleState,
            LocalDate effectiveDate,
            Map<String, Object> payload,
            List<String> validationFindings,
            String impactDigest,
            long targetOwnerVersion,
            long version,
            Long approvalDecisionRevision,
            UUID publishedWorkerPublicId,
            UUID publishedRelationshipPublicId,
            UUID publishedAssignmentPublicId,
            Instant validatedAt,
            Instant submittedAt,
            Instant approvedAt,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
        public EventView {
            payload = Map.copyOf(payload);
            validationFindings = List.copyOf(validationFindings);
        }
    }

    @Schema(name = "EmploymentAssignmentV1")
    public record AssignmentView(
            UUID personPublicId,
            UUID workerPublicId,
            String workerStatus,
            long workerVersion,
            UUID relationshipPublicId,
            String relationshipType,
            LocalDate relationshipStart,
            LocalDate relationshipEnd,
            long relationshipVersion,
            UUID assignmentPublicId,
            String assignmentStatus,
            LocalDate validFrom,
            LocalDate validToExclusive,
            boolean primary,
            String fte,
            long assignmentVersion,
            OwnerRef legalEmployerRef,
            OwnerRef organizationRef,
            OwnerRef positionRef,
            OwnerRef jobRef,
            OwnerRef workLocationRef) {
    }

    @Schema(name = "EmploymentAssignmentWorkbenchV1")
    public record AssignmentWorkbench(
            Instant asOf,
            List<AssignmentView> assignments) {
        public AssignmentWorkbench {
            assignments = List.copyOf(assignments);
        }
    }

    @Schema(name = "EmploymentDocumentV1")
    public record EmploymentDocument(
            UUID workerPublicId,
            UUID relationshipPublicId,
            UUID assignmentPublicId,
            String relationshipType,
            LocalDate validFrom,
            LocalDate validToExclusive,
            String status,
            long revision) {
    }

    @Schema(name = "EmploymentDocumentsV1")
    public record EmploymentDocuments(
            Instant asOf,
            List<EmploymentDocument> documents) {
        public EmploymentDocuments {
            documents = List.copyOf(documents);
        }
    }

    @Schema(name = "AssignmentApprovalDecisionV1")
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ApprovalDecision(
            @NotNull UUID approvalRequestId,
            @NotNull UUID decisionEventId,
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotNull Instant decidedAt,
            @NotNull UUID decisionActorPublicId,
            @Min(1) long approvalPolicyRevision,
            @Min(0) long expectedEventVersion,
            @NotNull UUID correlationId,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String payloadDigest) {
    }

    @Schema(name = "AssignmentApprovalResultV1")
    public record ApprovalResult(
            UUID eventPublicId,
            String lifecycleState,
            long version,
            boolean replayed) {
    }

    record NormalizedCommand(
            CommandContext context,
            String eventType,
            UUID personPublicId,
            UUID workerPublicId,
            long expectedOwnerVersion,
            OwnerRef legalEmployerRef,
            String employmentType,
            LocalDate relationshipStart,
            AssignmentDraft assignment,
            Map<String, Object> payload,
            String contextJson,
            String payloadJson) {
    }

    record StoredResult(
            UUID eventPublicId,
            String lifecycleState,
            long eventVersion) {
    }

    record CommandResult(
            CommandReceiptV1 receipt,
            EventView event,
            AssignmentImpact impact,
            boolean replayed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "HrisEmploymentProblemV1")
    public record Problem(
            String type,
            String title,
            int status,
            String code,
            UUID correlationId,
            boolean retryable,
            String safeDetail,
            Long currentVersion,
            UUID receiptId) {
    }
}
