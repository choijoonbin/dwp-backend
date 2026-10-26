package com.dwp.services.people.hris.separation.hrm013;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Closed persistence records for the bounded HRM-013 prerequisite stores. */
public final class Hrm013PersistenceModels {
    private Hrm013PersistenceModels() {
    }

    public record ReplayReservation(
            long tenantId,
            String bindingId,
            String callerWorkloadId,
            UUID jti,
            UUID requestNonce,
            Instant expiresAt) {
        public ReplayReservation {
            positive(tenantId, "tenantId");
            bindingId = required(bindingId, "bindingId");
            callerWorkloadId = required(callerWorkloadId, "callerWorkloadId");
            Objects.requireNonNull(jti, "jti");
            Objects.requireNonNull(requestNonce, "requestNonce");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (jti.equals(requestNonce)) {
                throw new IllegalArgumentException("jti and requestNonce must differ");
            }
        }
    }

    public record WaiverRequestFence(
            UUID publicId,
            long tenantId,
            long separationCaseId,
            long separationTaskId,
            UUID approvalRequestId,
            UUID casePublicId,
            UUID taskPublicId,
            long requestRevision,
            UUID caseMakerPublicId,
            UUID requestedByActorPublicId,
            String reasonCode,
            long taskOwnerRevision,
            long taskVersion,
            byte[] exactRequestBody,
            String requestBodySha256,
            String approvalRequestPayloadSha256,
            String commandDigest,
            UUID correlationId,
            Instant availableAt,
            Instant createdAt) {
        public WaiverRequestFence {
            Objects.requireNonNull(publicId, "publicId");
            positive(tenantId, "tenantId");
            positive(separationCaseId, "separationCaseId");
            positive(separationTaskId, "separationTaskId");
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(casePublicId, "casePublicId");
            Objects.requireNonNull(taskPublicId, "taskPublicId");
            positive(requestRevision, "requestRevision");
            Objects.requireNonNull(caseMakerPublicId, "caseMakerPublicId");
            Objects.requireNonNull(requestedByActorPublicId, "requestedByActorPublicId");
            reasonCode = required(reasonCode, "reasonCode");
            positive(taskOwnerRevision, "taskOwnerRevision");
            if (taskVersion < 0) throw new IllegalArgumentException("taskVersion must not be negative");
            exactRequestBody = bytes(exactRequestBody, 32768, "exactRequestBody");
            requestBodySha256 = digest(requestBodySha256, "requestBodySha256");
            if (!MessageDigest.isEqual(
                    sha256(exactRequestBody).getBytes(StandardCharsets.US_ASCII),
                    requestBodySha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("exactRequestBody digest mismatch");
            }
            approvalRequestPayloadSha256 = digest(
                    approvalRequestPayloadSha256, "approvalRequestPayloadSha256");
            commandDigest = digest(commandDigest, "commandDigest");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(availableAt, "availableAt");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        @Override
        public byte[] exactRequestBody() {
            return exactRequestBody.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof WaiverRequestFence that
                    && Arrays.deepEquals(equalityComponents(), that.equalityComponents());
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(equalityComponents());
        }

        private Object[] equalityComponents() {
            return new Object[] {
                publicId, tenantId, separationCaseId, separationTaskId,
                approvalRequestId, casePublicId, taskPublicId, requestRevision,
                caseMakerPublicId, requestedByActorPublicId, reasonCode,
                taskOwnerRevision, taskVersion, exactRequestBody, requestBodySha256,
                approvalRequestPayloadSha256, commandDigest, correlationId,
                availableAt, createdAt
            };
        }
    }

    public record CallbackAuthorityKey(
            long tenantId,
            String bindingId,
            String callerWorkloadId,
            UUID casePublicId,
            UUID taskPublicId,
            String purposeCode,
            UUID approvalRequestId) {
        public CallbackAuthorityKey {
            positive(tenantId, "tenantId");
            bindingId = required(bindingId, "bindingId");
            callerWorkloadId = required(callerWorkloadId, "callerWorkloadId");
            Objects.requireNonNull(casePublicId, "casePublicId");
            purposeCode = required(purposeCode, "purposeCode");
        }
    }

    public record CallbackAuthorityRecord(
            UUID publicId,
            CallbackAuthorityKey key,
            long separationCaseId,
            long separationTaskId,
            String callbackType,
            String audience,
            String taskType,
            UUID consumerEventId,
            long tenantGrantRevision,
            long authorizationRevision,
            long expectedNextOwnerRevision,
            UUID correlationId,
            String originatingPayloadSha256,
            long authorityRevision,
            String decision,
            UUID supersedesAuthorityPublicId,
            String authorityDigest,
            Instant createdAt,
            Instant closedAt) {
        public CallbackAuthorityRecord {
            Objects.requireNonNull(publicId, "publicId");
            Objects.requireNonNull(key, "key");
            positive(separationCaseId, "separationCaseId");
            if (separationTaskId < 0) {
                throw new IllegalArgumentException("separationTaskId must not be negative");
            }
            callbackType = required(callbackType, "callbackType");
            audience = required(audience, "audience");
            positive(tenantGrantRevision, "tenantGrantRevision");
            positive(authorizationRevision, "authorizationRevision");
            positive(expectedNextOwnerRevision, "expectedNextOwnerRevision");
            Objects.requireNonNull(correlationId, "correlationId");
            originatingPayloadSha256 = digest(
                    originatingPayloadSha256, "originatingPayloadSha256");
            positive(authorityRevision, "authorityRevision");
            decision = required(decision, "decision");
            authorityDigest = digest(authorityDigest, "authorityDigest");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record CallbackIdentity(
            long tenantId,
            String callerWorkloadId,
            UUID callbackEventId) {
        public CallbackIdentity {
            positive(tenantId, "tenantId");
            callerWorkloadId = required(callerWorkloadId, "callerWorkloadId");
            Objects.requireNonNull(callbackEventId, "callbackEventId");
        }
    }

    public record CallbackReceiptRecord(
            CallbackIdentity identity,
            long separationCaseId,
            long separationTaskId,
            String callbackType,
            String callbackValue,
            UUID externalReceiptPublicId,
            long ownerRevision,
            long tenantGrantRevision,
            long authorizationRevision,
            String payloadHash,
            String originatingPayloadSha256,
            String commandDigest,
            String requestBodySha256,
            String audience,
            String purposeCode,
            UUID correlationId,
            UUID approvalRequestId,
            String waiverDecision,
            Long decisionRevision,
            UUID decisionActorPublicId,
            UUID caseMakerPublicId,
            UUID requestedByActorPublicId,
            String reasonCode,
            UUID stepUpEvidenceId,
            String stepUpEvidenceAudience,
            String stepUpEvidencePurpose,
            String stepUpEvidenceNonceSha256,
            String stepUpEvidenceSha256,
            Instant stepUpVerifiedAt,
            Instant stepUpConsumedAt,
            UUID receiptPublicId,
            byte[] exactResponseBody,
            String responseBodySha256,
            String outcome,
            Instant occurredAt,
            Instant receivedAt) {
        public CallbackReceiptRecord {
            Objects.requireNonNull(identity, "identity");
            positive(separationCaseId, "separationCaseId");
            if (separationTaskId < 0) {
                throw new IllegalArgumentException("separationTaskId must not be negative");
            }
            callbackType = required(callbackType, "callbackType");
            callbackValue = required(callbackValue, "callbackValue");
            positive(ownerRevision, "ownerRevision");
            positive(tenantGrantRevision, "tenantGrantRevision");
            positive(authorizationRevision, "authorizationRevision");
            payloadHash = digest(payloadHash, "payloadHash");
            originatingPayloadSha256 = digest(
                    originatingPayloadSha256, "originatingPayloadSha256");
            commandDigest = digest(commandDigest, "commandDigest");
            requestBodySha256 = digest(requestBodySha256, "requestBodySha256");
            audience = required(audience, "audience");
            purposeCode = required(purposeCode, "purposeCode");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(receiptPublicId, "receiptPublicId");
            exactResponseBody = bytes(exactResponseBody, 16384, "exactResponseBody");
            responseBodySha256 = digest(responseBodySha256, "responseBodySha256");
            if (!MessageDigest.isEqual(
                    sha256(exactResponseBody).getBytes(StandardCharsets.US_ASCII),
                    responseBodySha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("exactResponseBody digest mismatch");
            }
            outcome = required(outcome, "outcome");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }

        @Override
        public byte[] exactResponseBody() {
            return exactResponseBody.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof CallbackReceiptRecord that
                    && Arrays.deepEquals(equalityComponents(), that.equalityComponents());
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(equalityComponents());
        }

        private Object[] equalityComponents() {
            return new Object[] {
                identity, separationCaseId, separationTaskId, callbackType,
                callbackValue, externalReceiptPublicId, ownerRevision,
                tenantGrantRevision, authorizationRevision, payloadHash,
                originatingPayloadSha256, commandDigest, requestBodySha256,
                audience, purposeCode, correlationId, approvalRequestId,
                waiverDecision, decisionRevision, decisionActorPublicId,
                caseMakerPublicId, requestedByActorPublicId, reasonCode,
                stepUpEvidenceId, stepUpEvidenceAudience, stepUpEvidencePurpose,
                stepUpEvidenceNonceSha256, stepUpEvidenceSha256,
                stepUpVerifiedAt, stepUpConsumedAt, receiptPublicId,
                exactResponseBody, responseBodySha256, outcome, occurredAt, receivedAt
            };
        }
    }

    public record WaiverDeliveryClaimRequest(
            long tenantId,
            UUID leaseToken,
            String leaseOwner,
            Instant leaseUntil) {
        public WaiverDeliveryClaimRequest {
            positive(tenantId, "tenantId");
            Objects.requireNonNull(leaseToken, "leaseToken");
            leaseOwner = required(leaseOwner, "leaseOwner");
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    public record WaiverDeliveryClaim(
            WaiverRequestFence fence,
            UUID leaseToken,
            String leaseOwner,
            int attemptCount,
            Instant leaseUntil) {
        public WaiverDeliveryClaim {
            Objects.requireNonNull(fence, "fence");
            Objects.requireNonNull(leaseToken, "leaseToken");
            leaseOwner = required(leaseOwner, "leaseOwner");
            if (attemptCount < 1 || attemptCount > 10) {
                throw new IllegalArgumentException("attemptCount must be between 1 and 10");
            }
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    public record WaiverDeliveryAcceptance(
            long tenantId,
            UUID approvalRequestId,
            UUID leaseToken,
            byte[] exactResponseBody,
            String responseBodySha256,
            Instant acceptedAt) {
        public WaiverDeliveryAcceptance {
            positive(tenantId, "tenantId");
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(leaseToken, "leaseToken");
            exactResponseBody = bytes(exactResponseBody, 16384, "exactResponseBody");
            responseBodySha256 = digest(responseBodySha256, "responseBodySha256");
            if (!MessageDigest.isEqual(
                    sha256(exactResponseBody).getBytes(StandardCharsets.US_ASCII),
                    responseBodySha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("exactResponseBody digest mismatch");
            }
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }

        @Override
        public byte[] exactResponseBody() {
            return exactResponseBody.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof WaiverDeliveryAcceptance that
                    && tenantId == that.tenantId
                    && approvalRequestId.equals(that.approvalRequestId)
                    && leaseToken.equals(that.leaseToken)
                    && MessageDigest.isEqual(exactResponseBody, that.exactResponseBody)
                    && responseBodySha256.equals(that.responseBodySha256)
                    && acceptedAt.equals(that.acceptedAt);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                    tenantId, approvalRequestId, leaseToken,
                    responseBodySha256, acceptedAt);
            return 31 * result + Arrays.hashCode(exactResponseBody);
        }
    }

    public record WaiverDeliveryFailure(
            long tenantId,
            UUID approvalRequestId,
            UUID leaseToken,
            boolean retryable,
            boolean remoteCommitAmbiguous,
            Instant retryAt,
            String failureCode) {
        public WaiverDeliveryFailure {
            positive(tenantId, "tenantId");
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(leaseToken, "leaseToken");
            if (retryable && retryAt == null) {
                throw new IllegalArgumentException("retryAt is required for a retryable failure");
            }
            if (!retryable && retryAt != null) {
                throw new IllegalArgumentException("retryAt is forbidden for a terminal failure");
            }
            failureCode = required(failureCode, "failureCode");
        }
    }

    public record WaiverFinalClaimRecovery(
            long tenantId,
            UUID approvalRequestId,
            UUID leaseToken,
            Instant observedAt,
            boolean remoteCommitAmbiguous,
            String failureCode) {
        public WaiverFinalClaimRecovery {
            positive(tenantId, "tenantId");
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(leaseToken, "leaseToken");
            Objects.requireNonNull(observedAt, "observedAt");
            failureCode = required(failureCode, "failureCode");
        }
    }

    public enum WaiverDeliveryDisposition {
        RETRY,
        ACCEPTED,
        DEAD,
        RESULT_UNKNOWN
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String tokenDigest(UUID value) {
        Objects.requireNonNull(value, "value");
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return value;
    }

    private static byte[] bytes(byte[] value, int maximum, String name) {
        if (value == null || value.length == 0 || value.length > maximum) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return value.clone();
    }

    private static void positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
