package com.dwp.services.approval.hrisbridge.separation;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Closed persistence types for the APR-002 and HRM-010 prerequisite. */
public final class HrisSeparationPersistenceModels {
    public static final String REQUEST_BINDING = "SVC-PEP-APR-002";
    public static final String REQUEST_PURPOSE = "HRIS_SEPARATION_TASK_WAIVER_REQUEST";
    public static final String CALLBACK_BINDING = "SVC-PEP-HRM-010";
    public static final String CALLBACK_PURPOSE = "HRIS_SEPARATION_TASK_WAIVER_DECISION";
    public static final String PEOPLE_WORKLOAD = "dwp-people-server";
    public static final String APPROVAL_WORKLOAD = "dwp-approval-server";
    public static final String PEOPLE_SPIFFE = "spiffe://dwp/service/dwp-people-server";
    public static final String APPROVAL_SPIFFE = "spiffe://dwp/service/dwp-approval-server";
    public static final int MAXIMUM_ATTEMPTS = 10;

    private static final DateTimeFormatter SIX_DIGIT_INSTANT =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private HrisSeparationPersistenceModels() { }

    public enum GrantDecision { ADMIT, REVOKE }
    public enum WaiverDecision { APPROVED, REJECTED }
    public enum DeliveryState { PENDING, CLAIMED, RETRY, DELIVERED, DEAD, RESULT_UNKNOWN }

    public record GrantWrite(
            UUID grantId,
            long tenantId,
            long grantRevision,
            long tenantGrantRevision,
            long authorizationRevision,
            GrantDecision decision,
            String peopleSigningKeyId,
            String approvalSigningKeyId,
            UUID supersedesGrantId,
            Instant recordedAt) {
        public GrantWrite {
            Objects.requireNonNull(grantId);
            Objects.requireNonNull(decision);
            Objects.requireNonNull(peopleSigningKeyId);
            Objects.requireNonNull(approvalSigningKeyId);
            Objects.requireNonNull(recordedAt);
            recordedAt = recordedAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public record TenantGrant(
            UUID grantId,
            long tenantId,
            long grantRevision,
            long tenantGrantRevision,
            long authorizationRevision,
            GrantDecision decision,
            String peopleSigningKeyId,
            String approvalSigningKeyId,
            UUID supersedesGrantId,
            String grantDigest,
            Instant recordedAt) { }

    public record RequestBindingWrite(
            UUID waiverBindingId,
            long tenantId,
            UUID grantId,
            long tenantGrantRevision,
            long authorizationRevision,
            UUID approvalRequestId,
            UUID casePublicId,
            UUID taskPublicId,
            String taskType,
            UUID caseMakerPublicId,
            UUID requestedByActorPublicId,
            String reasonCode,
            long requestedTaskOwnerRevision,
            long requestedTaskVersion,
            UUID workflowVersionId,
            UUID formVersionId,
            long frozenPolicyRevision,
            UUID originalCorrelationId,
            String approvalRequestPayloadSha256,
            byte[] exactRequestBody,
            String requestBodySha256,
            String commandDigest,
            byte[] exactResponseBody,
            String responseBodySha256,
            Instant requestedAt,
            Instant createdAt) {
        public RequestBindingWrite {
            exactRequestBody = exactRequestBody.clone();
            exactResponseBody = exactResponseBody.clone();
        }

        public String idempotencyKey() {
            return approvalRequestId.toString();
        }

        @Override
        public byte[] exactRequestBody() {
            return exactRequestBody.clone();
        }

        @Override
        public byte[] exactResponseBody() {
            return exactResponseBody.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RequestBindingWrite value
                    && Arrays.deepEquals(components(), value.components());
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(components());
        }

        private Object[] components() {
            return new Object[] {waiverBindingId, tenantId, grantId, tenantGrantRevision,
                    authorizationRevision, approvalRequestId, casePublicId, taskPublicId,
                    taskType, caseMakerPublicId, requestedByActorPublicId, reasonCode,
                    requestedTaskOwnerRevision, requestedTaskVersion, workflowVersionId,
                    formVersionId, frozenPolicyRevision, originalCorrelationId,
                    approvalRequestPayloadSha256, exactRequestBody, requestBodySha256,
                    commandDigest, exactResponseBody, responseBodySha256, requestedAt, createdAt};
        }
    }

    public record BindingReceipt(
            boolean replayed,
            UUID approvalRequestId,
            byte[] exactResponseBody,
            String responseBodySha256) {
        public BindingReceipt {
            exactResponseBody = exactResponseBody.clone();
        }

        @Override
        public byte[] exactResponseBody() {
            return exactResponseBody.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BindingReceipt value
                    && Arrays.deepEquals(components(), value.components());
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(components());
        }

        private Object[] components() {
            return new Object[] {replayed, approvalRequestId,
                    exactResponseBody, responseBodySha256};
        }
    }

    public record StepUpEvidence(
            UUID evidenceId,
            String audience,
            String purpose,
            UUID nonce,
            String evidenceSha256,
            Instant verifiedAt,
            Instant consumedAt) { }

    public record DecisionWrite(
            UUID outboxId,
            long tenantId,
            UUID grantId,
            long tenantGrantRevision,
            long authorizationRevision,
            UUID approvalRequestId,
            UUID casePublicId,
            UUID taskPublicId,
            UUID callbackEventId,
            WaiverDecision decision,
            UUID decisionActorPublicId,
            UUID caseMakerPublicId,
            UUID requestedByActorPublicId,
            String reasonCode,
            StepUpEvidence stepUp,
            Instant decisionOccurredAt,
            UUID originalCorrelationId,
            String approvalRequestPayloadSha256,
            String callbackPayloadSha256,
            byte[] exactCallbackBody,
            String callbackBodySha256,
            String commandDigest,
            Instant availableAt,
            Instant createdAt) {
        public DecisionWrite {
            exactCallbackBody = exactCallbackBody.clone();
        }

        public String callbackPath() {
            return "/internal/hris-people/v1/separation-cases/" + casePublicId
                    + "/tasks/" + taskPublicId + "/waiver-decisions";
        }

        @Override
        public byte[] exactCallbackBody() {
            return exactCallbackBody.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DecisionWrite value
                    && Arrays.deepEquals(components(), value.components());
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(components());
        }

        private Object[] components() {
            return new Object[] {outboxId, tenantId, grantId, tenantGrantRevision,
                    authorizationRevision, approvalRequestId, casePublicId, taskPublicId,
                    callbackEventId, decision, decisionActorPublicId, caseMakerPublicId,
                    requestedByActorPublicId, reasonCode, stepUp, decisionOccurredAt,
                    originalCorrelationId, approvalRequestPayloadSha256,
                    callbackPayloadSha256, exactCallbackBody, callbackBodySha256,
                    commandDigest, availableAt, createdAt};
        }
    }

    public record DeliveryClaim(
            UUID outboxId,
            long tenantId,
            UUID grantId,
            long tenantGrantRevision,
            long authorizationRevision,
            String approvalSigningKeyId,
            String callbackPath,
            byte[] exactCallbackBody,
            String callbackBodySha256,
            String commandDigest,
            UUID originalCorrelationId,
            UUID leaseToken,
            String leaseOwner,
            int attemptCount) {
        public DeliveryClaim {
            exactCallbackBody = exactCallbackBody.clone();
        }

        @Override
        public byte[] exactCallbackBody() {
            return exactCallbackBody.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DeliveryClaim value
                    && Arrays.deepEquals(components(), value.components());
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(components());
        }

        private Object[] components() {
            return new Object[] {outboxId, tenantId, grantId, tenantGrantRevision,
                    authorizationRevision, approvalSigningKeyId, callbackPath,
                    exactCallbackBody, callbackBodySha256, commandDigest,
                    originalCorrelationId, leaseToken, leaseOwner, attemptCount};
        }
    }

    public static String grantDigest(GrantWrite grant) {
        return sha256(lengthPrefixed(
                Long.toString(grant.tenantId()),
                Long.toString(grant.grantRevision()),
                Long.toString(grant.tenantGrantRevision()),
                Long.toString(grant.authorizationRevision()),
                grant.decision().name(),
                PEOPLE_SPIFFE,
                grant.peopleSigningKeyId(),
                APPROVAL_SPIFFE,
                grant.approvalSigningKeyId(),
                REQUEST_BINDING,
                REQUEST_PURPOSE,
                CALLBACK_BINDING,
                CALLBACK_PURPOSE,
                grant.supersedesGrantId() == null ? "" : grant.supersedesGrantId().toString(),
                SIX_DIGIT_INSTANT.format(grant.recordedAt())));
    }

    public static String canonicalUuidDigest(UUID value) {
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static byte[] lengthPrefixed(String... values) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            result.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            result.writeBytes(bytes);
        }
        return result.toByteArray();
    }
}
