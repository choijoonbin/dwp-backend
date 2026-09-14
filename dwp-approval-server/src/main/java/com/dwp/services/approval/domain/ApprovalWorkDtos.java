package com.dwp.services.approval.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApprovalWorkDtos {
    private ApprovalWorkDtos() { }

    public enum TaskView { INBOX, DELEGATED, COMPLETED }
    public enum RequestView { SUBMITTED, DRAFTS, DELETED, ARCHIVE, NEEDS_INFO }
    public enum DueFilter { ALL, OVERDUE, TODAY }
    public enum Sort { PRIORITY, NEWEST, OLDEST }

    public record SearchFilter(
            @Size(max = 200) String query,
            @Pattern(regexp = "|PENDING|CLAIMED|INFO_REQUESTED|APPROVED|REJECTED|DRAFT|SUBMITTED|IN_REVIEW|NEEDS_INFO|WITHDRAWN|CANCELLED")
            String status,
            @Pattern(regexp = "|LOW|NORMAL|HIGH|URGENT") String priority,
            UUID workflowId,
            DueFilter due,
            @Min(0) @Max(100000) int page,
            @Min(1) @Max(100) int size,
            Sort sort,
            @Min(0) @Max(100) Integer minRiskScore) {
        public SearchFilter(String query, String status, String priority, UUID workflowId,
                            DueFilter due, int page, int size, Sort sort) {
            this(query, status, priority, workflowId, due, page, size, sort, null);
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Page<T>(List<T> items, long totalElements, int totalPages,
                          int page, int size, boolean hasNext, Instant evaluatedAt) {
        public static <T> Page<T> of(List<T> items, long total, int page, int size, Instant at) {
            return new Page<>(List.copyOf(items), total, (int) ((total + size - 1) / size),
                    page, size, (long) (page + 1) * size < total, at);
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DraftRevision(int revision, String payloadSha256, String changeType,
                                Long changedBy, String reason, Instant createdAt,
                                boolean recoverable, String recoveryReason) { }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DraftRevisionDetail(DraftRevision revision, Map<String, Object> payload,
                                      Map<String, Object> draftSnapshot) { }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DraftCommand(
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @NotBlank @Size(max = 2000) String reason) { }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RecoverDraft(
            @NotNull @Min(1) Integer revision,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
            @NotBlank @Size(max = 2000) String reason) { }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DraftState(UUID requestId, long version, int payloadRevision,
                             Instant deletedAt, Long deletedBy) { }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DraftReceipt(String commandType, String route, DraftState draft,
                               Instant completedAt) { }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DraftReconciliation(String idempotencyKey, List<DraftReceipt> receipts) { }
}
