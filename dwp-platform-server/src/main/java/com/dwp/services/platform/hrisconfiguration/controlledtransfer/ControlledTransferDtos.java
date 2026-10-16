package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ControlledTransferDtos {

    private ControlledTransferDtos() {
    }

    public enum OwnerModule { HRM, PER, TIM, PAY, SYS }

    public enum TransferKind { INGEST, EXPORT, DELIVERY, EXCHANGE }

    public enum Classification { CONFIDENTIAL, RESTRICTED }

    public enum Freshness { FRESH_ONLY, ALLOW_STALE }

    public enum TransferState {
        REQUESTED, SCANNING, READY, IN_PROGRESS, COMPLETED,
        QUARANTINED, FAILED, EXPIRED, CANCELLED
    }

    public enum ReceiptStatus {
        ACCEPTED, RUNNING, SUCCEEDED, REJECTED, FAILED, RESULT_UNKNOWN
    }

    public record MalwareScanSnapshot(
            @NotBlank @Size(max = 64) String scannerKey,
            @NotBlank @Size(max = 64) String scannerVersion,
            @NotBlank @Pattern(regexp = "^CLEAN$") String verdict,
            @NotNull OffsetDateTime scannedAt,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String objectDigest,
            @Min(1) @Max(5_368_709_120L) long byteSize) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown malwareScanSnapshot property: " + name);
        }
    }

    public record ToctouBinding(
            @NotNull UUID uploadSessionId,
            @NotBlank @Size(max = 256) String storageETag,
            @NotBlank @Size(max = 128) String boundObjectVersion,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String boundDigest,
            @Min(1) @Max(5_368_709_120L) long boundByteSize,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String snapshotDigest) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown toctouBinding property: " + name);
        }
    }

    public record FileTransferCommand(
            @NotNull OwnerModule ownerModule,
            @NotNull TransferKind transferKind,
            UUID recipientPolicyRef,
            @Min(1) Long egressPolicyRevision,
            @NotBlank @Pattern(regexp = "^obj_[A-Za-z0-9_-]{16,128}$") String objectRef,
            @NotBlank @Size(max = 128) String objectVersion,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String objectDigest,
            @Min(1) @Max(5_368_709_120L) long byteSize,
            @NotBlank @Pattern(regexp = "^(text/csv|application/json|application/pdf|application/vnd\\.openxmlformats-officedocument\\.spreadsheetml\\.sheet)$") String mediaType,
            @NotNull @Valid MalwareScanSnapshot malwareScanSnapshot,
            @NotNull @Valid ToctouBinding toctouBinding,
            @NotNull Classification classification,
            @NotBlank @Size(min = 3, max = 64) String purposeCode,
            @NotNull OffsetDateTime expiresAt) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown FileTransferCommand property: " + name);
        }
    }

    public record CatalogItem(
            UUID publicId,
            String key,
            String type,
            String state,
            long version,
            OffsetDateTime updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CatalogResponse(
            OffsetDateTime asOf,
            String nextCursor,
            List<CatalogItem> items,
            OffsetDateTime freshUntil,
            OffsetDateTime staleAfter) {

        public CatalogResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(
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
}
