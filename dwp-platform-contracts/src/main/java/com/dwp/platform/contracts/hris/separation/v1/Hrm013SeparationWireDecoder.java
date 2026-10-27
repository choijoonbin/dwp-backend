package com.dwp.platform.contracts.hris.separation.v1;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationWireValues.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import java.util.function.Function;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationWireValues.*;

/** Closed wire decoders with caller-supplied constructors to retain DTO identity. */
final class Hrm013SeparationWireDecoder {

    private static final int MAXIMUM_REQUEST_BYTES = 32_768;
    private static final int MAXIMUM_RESPONSE_BYTES = 16_384;

    private Hrm013SeparationWireDecoder() {
    }

    static <T> T decodeWaiverRequest(byte[] bytes, WaiverRequestFactory<T> factory, Function<T, byte[]> encoder) {
        JsonNode node = strictObject(bytes, MAXIMUM_REQUEST_BYTES, Set.of(
                "approvalRequestId", "casePublicId", "taskPublicId", "taskType",
                "caseMakerPublicId", "requestedByActorPublicId", "reasonCode",
                "taskOwnerRevision", "taskVersion", "requestedAt", "payloadSha256",
                "correlationId"));
        try {
            T body =
                    factory.create(
                            uuid(node, "approvalRequestId"), uuid(node, "casePublicId"),
                            uuid(node, "taskPublicId"), text(node, "taskType"),
                            uuid(node, "caseMakerPublicId"),
                            uuid(node, "requestedByActorPublicId"),
                            text(node, "reasonCode"), number(node, "taskOwnerRevision"),
                            number(node, "taskVersion"), instant(node, "requestedAt"),
                            text(node, "payloadSha256"), uuid(node, "correlationId"));
            requireCanonical(bytes, encoder.apply(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    @FunctionalInterface
    interface WaiverRequestFactory<T> {
        T create(
                UUID approvalRequestId,
                UUID casePublicId,
                UUID taskPublicId,
                String taskType,
                UUID caseMakerPublicId,
                UUID requestedByActorPublicId,
                String reasonCode,
                long taskOwnerRevision,
                long taskVersion,
                Instant requestedAt,
                String payloadSha256,
                UUID correlationId);
    }

    static <T> T decodeTaskReceipt(byte[] bytes, TaskReceiptFactory<T> factory, Function<T, byte[]> encoder) {
        JsonNode node = strictObject(bytes, MAXIMUM_REQUEST_BYTES, Set.of(
                "callbackEventId", "casePublicId", "taskPublicId", "taskType", "status",
                "ownerRevision", "externalReceiptPublicId", "originatingPayloadSha256",
                "occurredAt", "payloadSha256", "correlationId"));
        try {
            T body = factory.create(
                    uuid(node, "callbackEventId"), uuid(node, "casePublicId"),
                    uuid(node, "taskPublicId"), text(node, "taskType"),
                    text(node, "status"), number(node, "ownerRevision"),
                    uuid(node, "externalReceiptPublicId"),
                    text(node, "originatingPayloadSha256"), instant(node, "occurredAt"),
                    text(node, "payloadSha256"), uuid(node, "correlationId"));
            requireCanonical(bytes, encoder.apply(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    @FunctionalInterface
    interface TaskReceiptFactory<T> {
        T create(
                UUID callbackEventId,
                UUID casePublicId,
                UUID taskPublicId,
                String taskType,
                String status,
                long ownerRevision,
                UUID externalReceiptPublicId,
                String originatingPayloadSha256,
                Instant occurredAt,
                String payloadSha256,
                UUID correlationId);
    }

    static <T> T decodeReconciliationResult(
            byte[] bytes, ReconciliationResultFactory<T> factory, Function<T, byte[]> encoder) {
        JsonNode node = strictObject(bytes, MAXIMUM_REQUEST_BYTES, Set.of(
                "callbackEventId", "casePublicId", "result", "ownerRevision",
                "originatingPayloadSha256", "occurredAt", "payloadSha256",
                "correlationId"));
        try {
            T body =
                    factory.create(
                            uuid(node, "callbackEventId"), uuid(node, "casePublicId"),
                            text(node, "result"), number(node, "ownerRevision"),
                            text(node, "originatingPayloadSha256"),
                            instant(node, "occurredAt"), text(node, "payloadSha256"),
                            uuid(node, "correlationId"));
            requireCanonical(bytes, encoder.apply(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    @FunctionalInterface
    interface ReconciliationResultFactory<T> {
        T create(
                UUID callbackEventId,
                UUID casePublicId,
                String result,
                long ownerRevision,
                String originatingPayloadSha256,
                Instant occurredAt,
                String payloadSha256,
                UUID correlationId);
    }

    static <T> T decodeWaiverDecision(byte[] bytes, WaiverDecisionFactory<T> factory, Function<T, byte[]> encoder) {
        JsonNode node = strictObjectVariable(bytes, MAXIMUM_REQUEST_BYTES,
                Set.of("callbackEventId", "approvalRequestId", "casePublicId",
                        "taskPublicId", "decision", "decisionActorPublicId",
                        "ownerRevision", "decisionRevision", "reasonCode",
                        "originatingPayloadSha256", "occurredAt", "payloadSha256",
                        "correlationId"),
                Set.of("stepUpEvidenceId", "stepUpEvidenceAudience",
                        "stepUpEvidencePurpose", "stepUpEvidenceNonce",
                        "stepUpEvidenceDigest", "stepUpVerifiedAt",
                        "stepUpConsumedAt"));
        try {
            boolean approved = "APPROVED".equals(text(node, "decision"));
            Set<String> fields = fieldNames(node);
            Set<String> optional = Set.of("stepUpEvidenceId", "stepUpEvidenceAudience",
                    "stepUpEvidencePurpose", "stepUpEvidenceNonce",
                    "stepUpEvidenceDigest", "stepUpVerifiedAt", "stepUpConsumedAt");
            if (approved != fields.containsAll(optional)
                    || (!approved && fields.stream().anyMatch(optional::contains))) {
                throw Hrm013SafeErrorV1.malformed();
            }
            T body = factory.create(
                    uuid(node, "callbackEventId"), uuid(node, "approvalRequestId"),
                    uuid(node, "casePublicId"), uuid(node, "taskPublicId"),
                    text(node, "decision"), uuid(node, "decisionActorPublicId"),
                    number(node, "ownerRevision"), number(node, "decisionRevision"),
                    text(node, "reasonCode"), text(node, "originatingPayloadSha256"),
                    approved ? uuid(node, "stepUpEvidenceId") : null,
                    approved ? text(node, "stepUpEvidenceAudience") : null,
                    approved ? text(node, "stepUpEvidencePurpose") : null,
                    approved ? uuid(node, "stepUpEvidenceNonce") : null,
                    approved ? text(node, "stepUpEvidenceDigest") : null,
                    approved ? instant(node, "stepUpVerifiedAt") : null,
                    approved ? instant(node, "stepUpConsumedAt") : null,
                    instant(node, "occurredAt"), text(node, "payloadSha256"),
                    uuid(node, "correlationId"));
            requireCanonical(bytes, encoder.apply(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    @FunctionalInterface
    interface WaiverDecisionFactory<T> {
        T create(
                UUID callbackEventId,
                UUID approvalRequestId,
                UUID casePublicId,
                UUID taskPublicId,
                String decision,
                UUID decisionActorPublicId,
                long ownerRevision,
                long decisionRevision,
                String reasonCode,
                String originatingPayloadSha256,
                UUID stepUpEvidenceId,
                String stepUpEvidenceAudience,
                String stepUpEvidencePurpose,
                UUID stepUpEvidenceNonce,
                String stepUpEvidenceDigest,
                Instant stepUpVerifiedAt,
                Instant stepUpConsumedAt,
                Instant occurredAt,
                String payloadSha256,
                UUID correlationId);
    }

    static <T> T decodeWaiverReceipt(
            byte[] bytes, WaiverReceiptFactory<T> factory, Function<T, byte[]> encoder) {
        JsonNode node = strictObject(bytes, MAXIMUM_RESPONSE_BYTES, Set.of(
                "approvalRequestId", "requestRevision", "state", "requestDigest",
                "correlationId", "acceptedAt"));
        try {
            T receipt =
                    factory.create(
                            uuid(node, "approvalRequestId"),
                            number(node, "requestRevision"), text(node, "state"),
                            text(node, "requestDigest"), uuid(node, "correlationId"),
                            instant(node, "acceptedAt"));
            requireCanonical(bytes, encoder.apply(receipt));
            return receipt;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    @FunctionalInterface
    interface WaiverReceiptFactory<T> {
        T create(
                UUID approvalRequestId,
                long requestRevision,
                String state,
                String requestDigest,
                UUID correlationId,
                Instant acceptedAt);
    }

    static <T> T decodeCallbackReceipt(byte[] bytes, CallbackReceiptFactory<T> factory, Function<T, byte[]> encoder) {
        JsonNode node = strictObjectVariable(bytes, MAXIMUM_RESPONSE_BYTES,
                Set.of("receiptId", "callbackEventId", "callbackType", "casePublicId",
                        "ownerRevision", "outcome", "correlationId", "commandDigest",
                        "recordedAt"), Set.of("taskPublicId"));
        try {
            boolean hasTask = node.has("taskPublicId");
            T receipt = factory.create(
                    uuid(node, "receiptId"), uuid(node, "callbackEventId"),
                    text(node, "callbackType"), uuid(node, "casePublicId"),
                    hasTask ? uuid(node, "taskPublicId") : null,
                    number(node, "ownerRevision"), text(node, "outcome"),
                    uuid(node, "correlationId"), text(node, "commandDigest"),
                    instant(node, "recordedAt"));
            requireCanonical(bytes, encoder.apply(receipt));
            return receipt;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    @FunctionalInterface
    interface CallbackReceiptFactory<T> {
        T create(
                UUID receiptId,
                UUID callbackEventId,
                String callbackType,
                UUID casePublicId,
                UUID taskPublicId,
                long ownerRevision,
                String outcome,
                UUID correlationId,
                String commandDigest,
                Instant recordedAt);
    }
}
