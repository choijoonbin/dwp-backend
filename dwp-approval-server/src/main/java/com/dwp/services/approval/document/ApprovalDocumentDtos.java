package com.dwp.services.approval.document;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalDocumentDtos {
    private ApprovalDocumentDtos() { }

    public interface StrictInput {
        @JsonAnySetter default void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown document-tools input field: " + key);
        }
    }

    public enum OwnerType { REQUEST, TASK }
    public enum Intent { PRINT, DOWNLOAD }
    public enum FieldType { STRING, NUMBER, DECIMAL_STRING, BOOLEAN, STRING_LIST, OBJECT, OBJECT_LIST }
    public enum HoldOperation { PLACE, RELEASE }

    @Schema(name="ApprovalDocumentFieldRule", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FieldRule(@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,79}") String key,
                            @NotNull FieldType type, @Min(1) @Max(10000) int maxLength,
                            @NotNull @Size(max = 100) List<@Valid FieldRule> children,
                            @Min(1) @Max(50) Integer maxRows) implements StrictInput {
        public FieldRule(String key, FieldType type, int maxLength, List<FieldRule> children) {
            this(key, type, maxLength, children, type == FieldType.OBJECT_LIST ? 50 : null);
        }
    }

    @Schema(name="ApprovalDocumentRules", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Rules(boolean allowComments, boolean allowPrint, boolean allowJsonExport,
                        boolean allowArchiveExport, boolean includeComments, boolean includeEvidence,
                        @NotNull @Size(max = 3) List<String> allowedClassifications,
                        @NotNull @Size(max = 100) List<@Valid FieldRule> fields,
                        @Min(1) @Max(50) int maxBatchItems, @Min(1024) @Max(5242880) int maxBytes,
                        @Min(60) @Max(3600) int snapshotTtlSeconds,
                        @Min(1) @Max(3650) int evidenceRetentionDays) implements StrictInput { }

    @Schema(name="ApprovalDocumentPolicyRevision", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PolicyRevision(int revision, Rules rules, String sha256, Long makerUserId, Instant createdAt) { }
    @Schema(name="ApprovalDocumentPolicy", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Policy(UUID policyId, String resourceSetKey, long version,
                         PolicyRevision published, PolicyRevision pending) { }
    @Schema(name="ApprovalDocumentSavePolicy", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SavePolicy(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 120) String idempotencyKey,
                             @NotNull @Valid Rules rules) implements StrictInput { }
    @Schema(name="ApprovalDocumentPublishPolicy", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PublishPolicy(@NotNull @Min(0) Long expectedVersion,
                                @NotBlank @Size(max = 120) String idempotencyKey,
                                @NotBlank @Size(min = 10, max = 1000) String reviewComment) implements StrictInput { }

    @Schema(name="ApprovalDocumentTool", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Tool(boolean allowed, String reason) { }
    @Schema(name="ApprovalDocumentTools", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Tools(UUID requestId, UUID taskId, long requestVersion, Long taskVersion,
                        int payloadRevision, String payloadSha256, long policyVersion, long commentsVersion,
                        long holdVersion, boolean legalHold, Tool copyIdentifier, Tool history,
                        Tool comment, Tool print, Tool jsonExport, Tool attachments, Instant evaluatedAt,
                        UUID policyId, String resourceSetKey, Tool archiveExport, int maxBatchItems, boolean preservationPending) { }

    @Schema(name="ApprovalDocumentAppendComment", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AppendComment(@NotNull @Min(0) Long expectedVersion,
                                @NotNull @Min(0) Long expectedCommentsVersion,
                                @NotBlank @Size(max = 120) String idempotencyKey,
                                @NotBlank @Size(max = 2000) String text) implements StrictInput { }
    @Schema(name="ApprovalDocumentComment", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Comment(UUID commentId, UUID requestId, UUID sourceTaskId, long sequence,
                          long authorUserId, String text, Instant createdAt, Instant retainUntil) { }
    @Schema(name="ApprovalDocumentComments", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Comments(List<Comment> items, long totalElements, int page, int size,
                           long commentsVersion, Instant evaluatedAt) { }

    @Schema(name="ApprovalDocumentExport", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Export(@NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) Integer payloadRevision,
                         @NotNull @Min(0) Long expectedPolicyVersion, @NotNull Intent intent,
                         @NotBlank @Size(min = 4, max = 1000) String reason,
                         @NotBlank @Size(max = 120) String idempotencyKey) implements StrictInput { }
    @Schema(name="ApprovalDocumentArchiveItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ArchiveItem(@NotNull UUID requestId, @NotNull @Min(0) Long expectedVersion,
                              @NotNull @Min(1) Integer payloadRevision) implements StrictInput { }
    @Schema(name="ApprovalDocumentArchiveExport", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ArchiveExport(@NotNull @Size(min = 1, max = 50) List<@Valid ArchiveItem> items,
                                @NotNull @Min(0) Long expectedPolicyVersion,
                                @NotBlank @Size(min = 4, max = 1000) String reason,
                                @NotBlank @Size(max = 120) String idempotencyKey,
                                @NotNull UUID expectedPolicyId,
                                @NotBlank @Pattern(regexp="RS_[A-Z0-9_]{1,76}") String resourceSetKey) implements StrictInput { }

    @Schema(name="ApprovalDocumentField", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DocumentField(String key, FieldType type, String stringValue, BigDecimal numberValue,
                                Boolean booleanValue, List<String> stringValues, List<DocumentField> children, List<DocumentRow> rows) { }
    @Schema(name="ApprovalDocumentRow", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DocumentRow(int rowNumber, List<DocumentField> fields) { }
    @Schema(name="ApprovalDocumentEvidence", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Evidence(UUID eventId, String eventType, String actorType, String actorId,
                           String message, Instant occurredAt) { }
    @Schema(name="ApprovalDocument", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Document(UUID requestId, UUID sourceTaskId, String requestNumber, String title, String summary,
                           String status, String classification, long requestVersion, Long taskVersion,
                           int payloadRevision, String payloadSha256, List<DocumentField> fields,
                           List<Comment> comments, List<Evidence> evidence, boolean legalHold, Instant retainUntil, boolean preservationPending) { }
    @Schema(name="ApprovalGeneratedDocument", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GeneratedDocument(UUID exportId, String format, String mediaType, String fileName,
                                    String sha256, long sizeBytes, long policyVersion, Instant generatedAt,
                                    Instant expiresAt, Instant retainUntil, String content) { }

    @Schema(name="ApprovalDocumentHoldProposal", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record HoldProposal(@NotNull @Min(0) Long expectedVersion, @NotNull HoldOperation operation,
                               @NotBlank @Size(min = 10, max = 1000) String reason,
                               @NotBlank @Size(max = 120) String idempotencyKey) implements StrictInput { }
    @Schema(name="ApprovalDocumentPublishHold", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PublishHold(@NotNull @Min(0) Long expectedVersion, @NotNull UUID proposalId,
                              @NotBlank @Size(min = 10, max = 1000) String reviewComment,
                              @NotBlank @Size(max = 120) String idempotencyKey) implements StrictInput { }
    @Schema(name="ApprovalDocumentPendingHold", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PendingHold(UUID proposalId, HoldOperation operation, String reason, long makerUserId,
                              Instant createdAt) { }
    @Schema(name="ApprovalDocumentHoldEntry", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record HoldEntry(UUID entryId, long version, HoldOperation operation, long makerUserId,
                            long checkerUserId, String reason, String reviewComment, Instant occurredAt) { }
    @Schema(name="ApprovalDocumentHold", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Hold(UUID requestId, long version, boolean active, PendingHold pending,
                       List<HoldEntry> journal, String purgeState, Instant retainUntil, boolean preservationPending, boolean purgeEligible) { }
}
