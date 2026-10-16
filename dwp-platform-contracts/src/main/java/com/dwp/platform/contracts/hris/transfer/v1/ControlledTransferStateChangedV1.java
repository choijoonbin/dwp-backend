package com.dwp.platform.contracts.hris.transfer.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict typed payload for SYS-EVT-008. Object bytes and storage paths are never carried. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ControlledTransferStateChangedV1(
        UUID transferPublicId,
        OwnerModule ownerModule,
        TransferKind transferKind,
        String purposeCode,
        State state,
        String objectVersion,
        String objectDigest,
        long byteSize,
        String mediaType,
        String metadataSnapshotDigest,
        Long egressPolicyRevision,
        String receiptDigest,
        Instant occurredAt) {

    private static final Pattern PURPOSE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    public ControlledTransferStateChangedV1 {
        Objects.requireNonNull(transferPublicId, "transferPublicId must not be null");
        Objects.requireNonNull(ownerModule, "ownerModule must not be null");
        Objects.requireNonNull(transferKind, "transferKind must not be null");
        require(PURPOSE.matcher(required(purposeCode, "purposeCode")).matches(),
                "purposeCode is not canonical");
        Objects.requireNonNull(state, "state must not be null");
        requiredLength(objectVersion, "objectVersion", 1, 128);
        digest(objectDigest, "objectDigest");
        require(byteSize >= 1L && byteSize <= 5_368_709_120L,
                "byteSize is outside the controlled-transfer boundary");
        require(supportedMediaType(required(mediaType, "mediaType")),
                "mediaType is not allowlisted");
        digest(metadataSnapshotDigest, "metadataSnapshotDigest");
        if (transferKind == TransferKind.INGEST) {
            require(egressPolicyRevision == null,
                    "INGEST must not carry an egress policy revision");
        } else {
            require(egressPolicyRevision != null && egressPolicyRevision >= 1L,
                    "Outbound transfer requires a positive egress policy revision");
        }
        if (receiptDigest != null) {
            digest(receiptDigest, "receiptDigest");
        }
        if (state == State.COMPLETED) {
            require(receiptDigest != null, "COMPLETED transfer requires a receipt digest");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static boolean supportedMediaType(String value) {
        return switch (value) {
            case "text/csv", "application/json", "application/pdf",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> true;
            default -> false;
        };
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requiredLength(
            String value, String field, int minimum, int maximum) {
        int length = required(value, field).length();
        require(length >= minimum && length <= maximum, field + " length is invalid");
    }

    private static void digest(String value, String field) {
        require(DIGEST.matcher(required(value, field)).matches(),
                field + " is not a canonical SHA-256 digest");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public enum OwnerModule { HRM, PER, TIM, PAY, SYS }

    public enum TransferKind { INGEST, EXPORT, DELIVERY, EXCHANGE }

    public enum State {
        REQUESTED, SCANNING, READY, IN_PROGRESS, COMPLETED,
        QUARANTINED, FAILED, EXPIRED, CANCELLED
    }
}
