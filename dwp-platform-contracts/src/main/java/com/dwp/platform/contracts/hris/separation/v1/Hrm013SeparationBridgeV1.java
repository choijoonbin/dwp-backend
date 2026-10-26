package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Closed wire contracts for APR-002 and HRM-008 through HRM-010.
 *
 * <p>This class owns wire shape and deterministic digests only.  It contains no
 * HTTP client, database, Spring or tenant-authority implementation.</p>
 */
public final class Hrm013SeparationBridgeV1 {

    public static final int MAXIMUM_REQUEST_BYTES = 32_768;
    public static final int MAXIMUM_RESPONSE_BYTES = 16_384;
    public static final String METHOD = "POST";
    public static final String CONTENT_TYPE = "application/json";
    public static final String PEOPLE = "dwp-people-server";
    public static final String APPROVAL = "dwp-approval-server";
    public static final String TIME = "dwp-time-server";
    public static final String PAYROLL = "dwp-payroll-server";
    public static final String AUTH = "dwp-auth-server";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9._-]{0,79}$");
    private static final Pattern ASCII_120 = Pattern.compile("^[\\x21-\\x7e]{1,120}$");

    public static final BindingProfile APR_002 = new BindingProfile(
            "SVC-PEP-APR-002", Operation.WAIVER_REQUEST, PEOPLE, APPROVAL,
            "HRIS_SEPARATION_TASK_WAIVER_REQUEST",
            "/internal/hris-approval/v1/separation-task-waiver-requests", Set.of());

    public static final BindingProfile HRM_008_TIME = new BindingProfile(
            "SVC-PEP-HRM-008", Operation.TASK_RECEIPT, TIME, PEOPLE,
            "HRIS_SEPARATION_TIME_TASK_RECEIPT",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/tasks/"
                    + "{taskPublicId}/receipts", Set.of("TIME_FINALIZATION"));

    public static final BindingProfile HRM_008_PAYROLL = new BindingProfile(
            "SVC-PEP-HRM-008", Operation.TASK_RECEIPT, PAYROLL, PEOPLE,
            "HRIS_SEPARATION_PAYROLL_TASK_RECEIPT",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/tasks/"
                    + "{taskPublicId}/receipts", Set.of("PAYROLL_SETTLEMENT"));

    public static final BindingProfile HRM_008_IDENTITY = new BindingProfile(
            "SVC-PEP-HRM-008", Operation.TASK_RECEIPT, AUTH, PEOPLE,
            "HRIS_SEPARATION_IDENTITY_TASK_RECEIPT",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/tasks/"
                    + "{taskPublicId}/receipts", Set.of("IDENTITY_ACCESS_REVOCATION"));

    public static final BindingProfile HRM_009_TIME = new BindingProfile(
            "SVC-PEP-HRM-009", Operation.RECONCILIATION_RESULT, TIME, PEOPLE,
            "HRIS_SEPARATION_TIME_RECONCILIATION",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/"
                    + "reconciliation-results", Set.of());

    public static final BindingProfile HRM_009_PAYROLL = new BindingProfile(
            "SVC-PEP-HRM-009", Operation.RECONCILIATION_RESULT, PAYROLL, PEOPLE,
            "HRIS_SEPARATION_PAYROLL_RECONCILIATION",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/"
                    + "reconciliation-results", Set.of());

    public static final BindingProfile HRM_010 = new BindingProfile(
            "SVC-PEP-HRM-010", Operation.WAIVER_DECISION, APPROVAL, PEOPLE,
            "HRIS_SEPARATION_TASK_WAIVER_DECISION",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/tasks/"
                    + "{taskPublicId}/waiver-decisions", Set.of());

    public static final List<BindingProfile> PROFILES = List.of(
            APR_002, HRM_008_TIME, HRM_008_PAYROLL, HRM_008_IDENTITY,
            HRM_009_TIME, HRM_009_PAYROLL, HRM_010);

    private Hrm013SeparationBridgeV1() {
    }

    public enum Operation {
        WAIVER_REQUEST,
        TASK_RECEIPT,
        RECONCILIATION_RESULT,
        WAIVER_DECISION
    }

    public record BindingProfile(
            String bindingId,
            Operation operation,
            String callerWorkloadId,
            String ownerWorkloadId,
            String purpose,
            String pathTemplate,
            Set<String> allowedTaskTypes) {
        public BindingProfile {
            requireCode(bindingId, "bindingId");
            Objects.requireNonNull(operation, "operation");
            requireWorkload(callerWorkloadId, "callerWorkloadId");
            requireWorkload(ownerWorkloadId, "ownerWorkloadId");
            requireCode(purpose, "purpose");
            if (pathTemplate == null || !pathTemplate.startsWith("/internal/")) {
                throw new IllegalArgumentException("pathTemplate is not internal");
            }
            allowedTaskTypes = Set.copyOf(
                    Objects.requireNonNull(allowedTaskTypes, "allowedTaskTypes"));
            allowedTaskTypes.forEach(value -> requireCode(value, "allowedTaskType"));
        }

        public String method() {
            return METHOD;
        }

        public String audience() {
            return ownerWorkloadId;
        }

        public String path(UUID casePublicId, UUID taskPublicId) {
            String result = pathTemplate;
            if (result.contains("{casePublicId}")) {
                result = result.replace("{casePublicId}",
                        Objects.requireNonNull(casePublicId, "casePublicId").toString());
            } else if (casePublicId != null) {
                throw new IllegalArgumentException("casePublicId is forbidden");
            }
            if (result.contains("{taskPublicId}")) {
                result = result.replace("{taskPublicId}",
                        Objects.requireNonNull(taskPublicId, "taskPublicId").toString());
            } else if (taskPublicId != null) {
                throw new IllegalArgumentException("taskPublicId is forbidden");
            }
            return result;
        }

        public boolean matchesPath(String exactPath) {
            if (exactPath == null || exactPath.indexOf('?') >= 0
                    || exactPath.indexOf('#') >= 0 || exactPath.endsWith("/")) {
                return false;
            }
            if (!pathTemplate.contains("{")) {
                return pathTemplate.equals(exactPath);
            }
            return exactPath.matches(pathRegex(pathTemplate));
        }

        public void requireTaskType(String taskType) {
            if (!allowedTaskTypes.isEmpty() && !allowedTaskTypes.contains(taskType)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        }
    }

    public sealed interface PayloadBody permits
            SeparationWaiverApprovalRequestV1,
            SeparationTaskReceiptV2,
            SeparationReconciliationResultV1,
            SeparationWaiverDecisionV2 {
        String schemaName();

        int schemaVersion();

        String payloadSha256();

        UUID correlationId();

        Map<String, Object> wireValues(boolean includePayloadSha256);

        PayloadBody withPayloadSha256(String payloadSha256);
    }

    public record SeparationWaiverApprovalRequestV1(
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
            UUID correlationId) implements PayloadBody {
        public SeparationWaiverApprovalRequestV1 {
            requireUuid(approvalRequestId, "approvalRequestId");
            requireUuid(casePublicId, "casePublicId");
            requireUuid(taskPublicId, "taskPublicId");
            requireCode(taskType, "taskType");
            requireUuid(caseMakerPublicId, "caseMakerPublicId");
            requireUuid(requestedByActorPublicId, "requestedByActorPublicId");
            requireCode(reasonCode, "reasonCode");
            requirePositive(taskOwnerRevision, "taskOwnerRevision");
            requireNonNegative(taskVersion, "taskVersion");
            requireInstant(requestedAt, "requestedAt");
            requireDigest(payloadSha256, "payloadSha256");
            requireUuid(correlationId, "correlationId");
        }

        @Override public String schemaName() {
            return "SeparationWaiverApprovalRequestV1";
        }

        @Override public int schemaVersion() {
            return 1;
        }

        @Override public Map<String, Object> wireValues(boolean includeDigest) {
            Map<String, Object> values = values(
                    "approvalRequestId", approvalRequestId,
                    "casePublicId", casePublicId,
                    "taskPublicId", taskPublicId,
                    "taskType", taskType,
                    "caseMakerPublicId", caseMakerPublicId,
                    "requestedByActorPublicId", requestedByActorPublicId,
                    "reasonCode", reasonCode,
                    "taskOwnerRevision", taskOwnerRevision,
                    "taskVersion", taskVersion,
                    "requestedAt", requestedAt,
                    "correlationId", correlationId);
            if (includeDigest) values.put("payloadSha256", payloadSha256);
            return values;
        }

        @Override public SeparationWaiverApprovalRequestV1 withPayloadSha256(String digest) {
            return new SeparationWaiverApprovalRequestV1(
                    approvalRequestId, casePublicId, taskPublicId, taskType,
                    caseMakerPublicId, requestedByActorPublicId, reasonCode,
                    taskOwnerRevision, taskVersion, requestedAt, digest, correlationId);
        }
    }

    public record SeparationTaskReceiptV2(
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
            UUID correlationId) implements PayloadBody {
        public SeparationTaskReceiptV2 {
            requireUuid(callbackEventId, "callbackEventId");
            requireUuid(casePublicId, "casePublicId");
            requireUuid(taskPublicId, "taskPublicId");
            requireCode(taskType, "taskType");
            requireOneOf(status, "status", "COMPLETED", "FAILED");
            requirePositive(ownerRevision, "ownerRevision");
            requireUuid(externalReceiptPublicId, "externalReceiptPublicId");
            requireDigest(originatingPayloadSha256, "originatingPayloadSha256");
            requireInstant(occurredAt, "occurredAt");
            requireDigest(payloadSha256, "payloadSha256");
            requireUuid(correlationId, "correlationId");
        }

        @Override public String schemaName() { return "SeparationTaskReceiptV2"; }
        @Override public int schemaVersion() { return 2; }

        @Override public Map<String, Object> wireValues(boolean includeDigest) {
            Map<String, Object> values = values(
                    "callbackEventId", callbackEventId,
                    "casePublicId", casePublicId,
                    "taskPublicId", taskPublicId,
                    "taskType", taskType,
                    "status", status,
                    "ownerRevision", ownerRevision,
                    "externalReceiptPublicId", externalReceiptPublicId,
                    "originatingPayloadSha256", originatingPayloadSha256,
                    "occurredAt", occurredAt,
                    "correlationId", correlationId);
            if (includeDigest) values.put("payloadSha256", payloadSha256);
            return values;
        }

        @Override public SeparationTaskReceiptV2 withPayloadSha256(String digest) {
            return new SeparationTaskReceiptV2(
                    callbackEventId, casePublicId, taskPublicId, taskType, status,
                    ownerRevision, externalReceiptPublicId, originatingPayloadSha256,
                    occurredAt, digest, correlationId);
        }
    }

    public record SeparationReconciliationResultV1(
            UUID callbackEventId,
            UUID casePublicId,
            String result,
            long ownerRevision,
            String originatingPayloadSha256,
            Instant occurredAt,
            String payloadSha256,
            UUID correlationId) implements PayloadBody {
        public SeparationReconciliationResultV1 {
            requireUuid(callbackEventId, "callbackEventId");
            requireUuid(casePublicId, "casePublicId");
            requireOneOf(result, "result", "CONSISTENT", "CORRECTION_REQUIRED");
            requirePositive(ownerRevision, "ownerRevision");
            requireDigest(originatingPayloadSha256, "originatingPayloadSha256");
            requireInstant(occurredAt, "occurredAt");
            requireDigest(payloadSha256, "payloadSha256");
            requireUuid(correlationId, "correlationId");
        }

        @Override public String schemaName() {
            return "SeparationReconciliationResultV1";
        }
        @Override public int schemaVersion() { return 1; }

        @Override public Map<String, Object> wireValues(boolean includeDigest) {
            Map<String, Object> values = values(
                    "callbackEventId", callbackEventId,
                    "casePublicId", casePublicId,
                    "result", result,
                    "ownerRevision", ownerRevision,
                    "originatingPayloadSha256", originatingPayloadSha256,
                    "occurredAt", occurredAt,
                    "correlationId", correlationId);
            if (includeDigest) values.put("payloadSha256", payloadSha256);
            return values;
        }

        @Override public SeparationReconciliationResultV1 withPayloadSha256(String digest) {
            return new SeparationReconciliationResultV1(
                    callbackEventId, casePublicId, result, ownerRevision,
                    originatingPayloadSha256, occurredAt, digest, correlationId);
        }
    }

    public record SeparationWaiverDecisionV2(
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
            UUID correlationId) implements PayloadBody {
        public SeparationWaiverDecisionV2 {
            requireUuid(callbackEventId, "callbackEventId");
            requireUuid(approvalRequestId, "approvalRequestId");
            requireUuid(casePublicId, "casePublicId");
            requireUuid(taskPublicId, "taskPublicId");
            requireOneOf(decision, "decision", "APPROVED", "REJECTED");
            requireUuid(decisionActorPublicId, "decisionActorPublicId");
            requirePositive(ownerRevision, "ownerRevision");
            requirePositive(decisionRevision, "decisionRevision");
            if (ownerRevision != decisionRevision) {
                throw new IllegalArgumentException(
                        "decisionRevision must equal ownerRevision");
            }
            requireCode(reasonCode, "reasonCode");
            requireDigest(originatingPayloadSha256, "originatingPayloadSha256");
            if ("APPROVED".equals(decision)) {
                requireUuid(stepUpEvidenceId, "stepUpEvidenceId");
                requireExactAscii(stepUpEvidenceAudience, "stepUpEvidenceAudience");
                requireExactAscii(stepUpEvidencePurpose, "stepUpEvidencePurpose");
                if (!PEOPLE.equals(stepUpEvidenceAudience)
                        || !"HRIS_SEPARATION_TASK_WAIVER_DECISION"
                        .equals(stepUpEvidencePurpose)) {
                    throw new IllegalArgumentException("step-up audience or purpose mismatch");
                }
                requireUuid(stepUpEvidenceNonce, "stepUpEvidenceNonce");
                requireDigest(stepUpEvidenceDigest, "stepUpEvidenceDigest");
                requireInstant(stepUpVerifiedAt, "stepUpVerifiedAt");
                requireInstant(stepUpConsumedAt, "stepUpConsumedAt");
                if (stepUpVerifiedAt.isAfter(stepUpConsumedAt)) {
                    throw new IllegalArgumentException(
                            "stepUpVerifiedAt must not follow stepUpConsumedAt");
                }
            } else if (stepUpEvidenceId != null || stepUpEvidenceAudience != null
                    || stepUpEvidencePurpose != null || stepUpEvidenceNonce != null
                    || stepUpEvidenceDigest != null || stepUpVerifiedAt != null
                    || stepUpConsumedAt != null) {
                throw new IllegalArgumentException(
                        "REJECTED decision forbids step-up evidence");
            }
            requireInstant(occurredAt, "occurredAt");
            if (stepUpConsumedAt != null && stepUpConsumedAt.isAfter(occurredAt)) {
                throw new IllegalArgumentException(
                        "stepUpConsumedAt must not follow occurredAt");
            }
            requireDigest(payloadSha256, "payloadSha256");
            requireUuid(correlationId, "correlationId");
        }

        @Override public String schemaName() { return "SeparationWaiverDecisionV2"; }
        @Override public int schemaVersion() { return 2; }

        @Override public Map<String, Object> wireValues(boolean includeDigest) {
            Map<String, Object> values = values(
                    "callbackEventId", callbackEventId,
                    "approvalRequestId", approvalRequestId,
                    "casePublicId", casePublicId,
                    "taskPublicId", taskPublicId,
                    "decision", decision,
                    "decisionActorPublicId", decisionActorPublicId,
                    "ownerRevision", ownerRevision,
                    "decisionRevision", decisionRevision,
                    "reasonCode", reasonCode,
                    "originatingPayloadSha256", originatingPayloadSha256,
                    "occurredAt", occurredAt,
                    "correlationId", correlationId);
            if (stepUpEvidenceId != null) {
                values.put("stepUpEvidenceId", stepUpEvidenceId);
                values.put("stepUpEvidenceAudience", stepUpEvidenceAudience);
                values.put("stepUpEvidencePurpose", stepUpEvidencePurpose);
                values.put("stepUpEvidenceNonce", stepUpEvidenceNonce);
                values.put("stepUpEvidenceDigest", stepUpEvidenceDigest);
                values.put("stepUpVerifiedAt", stepUpVerifiedAt);
                values.put("stepUpConsumedAt", stepUpConsumedAt);
            }
            if (includeDigest) values.put("payloadSha256", payloadSha256);
            return values;
        }

        @Override public SeparationWaiverDecisionV2 withPayloadSha256(String digest) {
            return new SeparationWaiverDecisionV2(
                    callbackEventId, approvalRequestId, casePublicId, taskPublicId,
                    decision, decisionActorPublicId, ownerRevision, decisionRevision,
                    reasonCode, originatingPayloadSha256, stepUpEvidenceId,
                    stepUpEvidenceAudience, stepUpEvidencePurpose, stepUpEvidenceNonce,
                    stepUpEvidenceDigest, stepUpVerifiedAt, stepUpConsumedAt,
                    occurredAt, digest, correlationId);
        }
    }

    public record SeparationWaiverApprovalSubmissionReceiptV1(
            UUID approvalRequestId,
            long requestRevision,
            String state,
            String requestDigest,
            UUID correlationId,
            Instant acceptedAt) {
        public SeparationWaiverApprovalSubmissionReceiptV1 {
            requireUuid(approvalRequestId, "approvalRequestId");
            requirePositive(requestRevision, "requestRevision");
            requireOneOf(state, "state", "PENDING");
            requireDigest(requestDigest, "requestDigest");
            requireUuid(correlationId, "correlationId");
            requireInstant(acceptedAt, "acceptedAt");
        }

        Map<String, Object> wireValues() {
            return values(
                    "approvalRequestId", approvalRequestId,
                    "requestRevision", requestRevision,
                    "state", state,
                    "requestDigest", requestDigest,
                    "correlationId", correlationId,
                    "acceptedAt", acceptedAt);
        }
    }

    public record SeparationCallbackReceiptV1(
            UUID receiptId,
            UUID callbackEventId,
            String callbackType,
            UUID casePublicId,
            UUID taskPublicId,
            long ownerRevision,
            String outcome,
            UUID correlationId,
            String commandDigest,
            Instant recordedAt) {
        public SeparationCallbackReceiptV1 {
            requireUuid(receiptId, "receiptId");
            requireUuid(callbackEventId, "callbackEventId");
            requireOneOf(callbackType, "callbackType",
                    "TASK_RECEIPT", "APPROVAL_DECISION", "RECONCILIATION_RESULT");
            requireUuid(casePublicId, "casePublicId");
            if ("RECONCILIATION_RESULT".equals(callbackType)) {
                if (taskPublicId != null) {
                    throw new IllegalArgumentException(
                            "reconciliation receipt forbids taskPublicId");
                }
            } else {
                requireUuid(taskPublicId, "taskPublicId");
            }
            requirePositive(ownerRevision, "ownerRevision");
            requireOneOf(outcome, "outcome", "ACCEPTED", "REJECTED", "QUARANTINED");
            requireUuid(correlationId, "correlationId");
            requireDigest(commandDigest, "commandDigest");
            requireInstant(recordedAt, "recordedAt");
        }

        Map<String, Object> wireValues() {
            Map<String, Object> values = values(
                    "receiptId", receiptId,
                    "callbackEventId", callbackEventId,
                    "callbackType", callbackType,
                    "casePublicId", casePublicId,
                    "ownerRevision", ownerRevision,
                    "outcome", outcome,
                    "correlationId", correlationId,
                    "commandDigest", commandDigest,
                    "recordedAt", recordedAt);
            if (taskPublicId != null) values.put("taskPublicId", taskPublicId);
            return values;
        }
    }

    public static BindingProfile requireProfile(
            String bindingId, String callerWorkloadId, String purpose, String taskType) {
        List<BindingProfile> matches = PROFILES.stream()
                .filter(profile -> profile.bindingId().equals(bindingId))
                .filter(profile -> profile.callerWorkloadId().equals(callerWorkloadId))
                .filter(profile -> profile.purpose().equals(purpose))
                .filter(profile -> profile.allowedTaskTypes().isEmpty()
                        || profile.allowedTaskTypes().contains(taskType))
                .toList();
        if (matches.size() != 1) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return matches.get(0);
    }

    /** Returns an immutable copy carrying its RFC 8785 business digest. */
    public static <T extends PayloadBody> T seal(T body) {
        Objects.requireNonNull(body, "body");
        @SuppressWarnings("unchecked")
        T sealed = (T) body.withPayloadSha256(payloadSha256(body));
        return sealed;
    }

    public static String payloadSha256(PayloadBody body) {
        Objects.requireNonNull(body, "body");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaName", body.schemaName());
        payload.put("schemaVersion", body.schemaVersion());
        payload.putAll(body.wireValues(false));
        return sha256(canonicalObjectBytes(payload));
    }

    public static void requirePayloadSha256(PayloadBody body) {
        if (!constantTimeEquals(body.payloadSha256(), payloadSha256(body))) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
    }

    public static String stepUpEvidenceSha256(
            long tenantId, SeparationWaiverDecisionV2 body) {
        requirePositive(tenantId, "tenantId");
        Objects.requireNonNull(body, "body");
        if (!"APPROVED".equals(body.decision())) {
            throw new IllegalArgumentException("step-up digest exists only for APPROVED");
        }
        String preimage = tenantId + "\n"
                + body.approvalRequestId() + "\n"
                + body.decisionActorPublicId() + "\n"
                + body.stepUpEvidenceId() + "\n"
                + body.stepUpEvidenceAudience() + "\n"
                + body.stepUpEvidencePurpose() + "\n"
                + body.stepUpEvidenceNonce() + "\n"
                + body.stepUpVerifiedAt();
        return sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }

    public static void requireStepUpEvidenceSha256(
            long tenantId, SeparationWaiverDecisionV2 body) {
        if ("APPROVED".equals(body.decision())
                && !constantTimeEquals(body.stepUpEvidenceDigest(),
                stepUpEvidenceSha256(tenantId, body))) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
    }

    public static byte[] encode(PayloadBody body) {
        requirePayloadSha256(body);
        byte[] bytes = canonicalObjectBytes(body.wireValues(true));
        requireMaximum(bytes, MAXIMUM_REQUEST_BYTES);
        return bytes;
    }

    public static byte[] encode(SeparationWaiverApprovalSubmissionReceiptV1 receipt) {
        byte[] bytes = canonicalObjectBytes(
                Objects.requireNonNull(receipt, "receipt").wireValues());
        requireMaximum(bytes, MAXIMUM_RESPONSE_BYTES);
        return bytes;
    }

    public static byte[] encode(SeparationCallbackReceiptV1 receipt) {
        byte[] bytes = canonicalObjectBytes(
                Objects.requireNonNull(receipt, "receipt").wireValues());
        requireMaximum(bytes, MAXIMUM_RESPONSE_BYTES);
        return bytes;
    }

    public static SeparationWaiverApprovalRequestV1 decodeWaiverRequest(byte[] bytes) {
        JsonNode node = strictObject(bytes, MAXIMUM_REQUEST_BYTES, Set.of(
                "approvalRequestId", "casePublicId", "taskPublicId", "taskType",
                "caseMakerPublicId", "requestedByActorPublicId", "reasonCode",
                "taskOwnerRevision", "taskVersion", "requestedAt", "payloadSha256",
                "correlationId"));
        try {
            SeparationWaiverApprovalRequestV1 body =
                    new SeparationWaiverApprovalRequestV1(
                            uuid(node, "approvalRequestId"), uuid(node, "casePublicId"),
                            uuid(node, "taskPublicId"), text(node, "taskType"),
                            uuid(node, "caseMakerPublicId"),
                            uuid(node, "requestedByActorPublicId"),
                            text(node, "reasonCode"), number(node, "taskOwnerRevision"),
                            number(node, "taskVersion"), instant(node, "requestedAt"),
                            text(node, "payloadSha256"), uuid(node, "correlationId"));
            requireCanonical(bytes, encode(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static SeparationTaskReceiptV2 decodeTaskReceipt(byte[] bytes) {
        JsonNode node = strictObject(bytes, MAXIMUM_REQUEST_BYTES, Set.of(
                "callbackEventId", "casePublicId", "taskPublicId", "taskType", "status",
                "ownerRevision", "externalReceiptPublicId", "originatingPayloadSha256",
                "occurredAt", "payloadSha256", "correlationId"));
        try {
            SeparationTaskReceiptV2 body = new SeparationTaskReceiptV2(
                    uuid(node, "callbackEventId"), uuid(node, "casePublicId"),
                    uuid(node, "taskPublicId"), text(node, "taskType"),
                    text(node, "status"), number(node, "ownerRevision"),
                    uuid(node, "externalReceiptPublicId"),
                    text(node, "originatingPayloadSha256"), instant(node, "occurredAt"),
                    text(node, "payloadSha256"), uuid(node, "correlationId"));
            requireCanonical(bytes, encode(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static SeparationReconciliationResultV1 decodeReconciliationResult(
            byte[] bytes) {
        JsonNode node = strictObject(bytes, MAXIMUM_REQUEST_BYTES, Set.of(
                "callbackEventId", "casePublicId", "result", "ownerRevision",
                "originatingPayloadSha256", "occurredAt", "payloadSha256",
                "correlationId"));
        try {
            SeparationReconciliationResultV1 body =
                    new SeparationReconciliationResultV1(
                            uuid(node, "callbackEventId"), uuid(node, "casePublicId"),
                            text(node, "result"), number(node, "ownerRevision"),
                            text(node, "originatingPayloadSha256"),
                            instant(node, "occurredAt"), text(node, "payloadSha256"),
                            uuid(node, "correlationId"));
            requireCanonical(bytes, encode(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static SeparationWaiverDecisionV2 decodeWaiverDecision(byte[] bytes) {
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
            SeparationWaiverDecisionV2 body = new SeparationWaiverDecisionV2(
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
            requireCanonical(bytes, encode(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static SeparationWaiverApprovalSubmissionReceiptV1 decodeWaiverReceipt(
            byte[] bytes) {
        JsonNode node = strictObject(bytes, MAXIMUM_RESPONSE_BYTES, Set.of(
                "approvalRequestId", "requestRevision", "state", "requestDigest",
                "correlationId", "acceptedAt"));
        try {
            SeparationWaiverApprovalSubmissionReceiptV1 receipt =
                    new SeparationWaiverApprovalSubmissionReceiptV1(
                            uuid(node, "approvalRequestId"),
                            number(node, "requestRevision"), text(node, "state"),
                            text(node, "requestDigest"), uuid(node, "correlationId"),
                            instant(node, "acceptedAt"));
            requireCanonical(bytes, encode(receipt));
            return receipt;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static SeparationCallbackReceiptV1 decodeCallbackReceipt(byte[] bytes) {
        JsonNode node = strictObjectVariable(bytes, MAXIMUM_RESPONSE_BYTES,
                Set.of("receiptId", "callbackEventId", "callbackType", "casePublicId",
                        "ownerRevision", "outcome", "correlationId", "commandDigest",
                        "recordedAt"), Set.of("taskPublicId"));
        try {
            boolean hasTask = node.has("taskPublicId");
            SeparationCallbackReceiptV1 receipt = new SeparationCallbackReceiptV1(
                    uuid(node, "receiptId"), uuid(node, "callbackEventId"),
                    text(node, "callbackType"), uuid(node, "casePublicId"),
                    hasTask ? uuid(node, "taskPublicId") : null,
                    number(node, "ownerRevision"), text(node, "outcome"),
                    uuid(node, "correlationId"), text(node, "commandDigest"),
                    instant(node, "recordedAt"));
            requireCanonical(bytes, encode(receipt));
            return receipt;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static String requestBodySha256(byte[] exactBodyBytes) {
        Objects.requireNonNull(exactBodyBytes, "exactBodyBytes");
        requireMaximum(exactBodyBytes, MAXIMUM_REQUEST_BYTES);
        return sha256(exactBodyBytes);
    }

    /** Nine-value LF-delimited digest, with no prefix and no trailing LF. */
    public static String commandDigest(
            BindingProfile profile,
            String exactConcretePath,
            long tenantId,
            String requestBodySha256) {
        Objects.requireNonNull(profile, "profile");
        requireKnownProfile(profile);
        if (!profile.matchesPath(exactConcretePath)) {
            throw new IllegalArgumentException("exactConcretePath does not match profile");
        }
        requirePositive(tenantId, "tenantId");
        requireDigest(requestBodySha256, "requestBodySha256");
        String preimage = String.join("\n",
                profile.bindingId(), METHOD, exactConcretePath,
                profile.callerWorkloadId(), profile.audience(),
                Long.toString(tenantId), profile.purpose(), CONTENT_TYPE,
                requestBodySha256);
        return sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }

    public static String commandDigest(
            BindingProfile profile,
            String exactConcretePath,
            long tenantId,
            byte[] exactBodyBytes) {
        return commandDigest(profile, exactConcretePath, tenantId,
                requestBodySha256(exactBodyBytes));
    }

    public static void requireBodyBinding(BindingProfile profile, PayloadBody body) {
        Objects.requireNonNull(profile, "profile");
        requireKnownProfile(profile);
        Objects.requireNonNull(body, "body");
        boolean typeMatches = switch (profile.operation()) {
            case WAIVER_REQUEST -> body instanceof SeparationWaiverApprovalRequestV1;
            case TASK_RECEIPT -> body instanceof SeparationTaskReceiptV2;
            case RECONCILIATION_RESULT ->
                    body instanceof SeparationReconciliationResultV1;
            case WAIVER_DECISION -> body instanceof SeparationWaiverDecisionV2;
        };
        if (!typeMatches) throw Hrm013SafeErrorV1.authorityDenied();
        if (body instanceof SeparationTaskReceiptV2 taskReceipt) {
            profile.requireTaskType(taskReceipt.taskType());
        }
    }

    public static String sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static byte[] canonicalObjectBytes(Map<String, ?> source) {
        Objects.requireNonNull(source, "source");
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : new TreeMap<>(source).entrySet()) {
            if (!first) json.append(',');
            first = false;
            appendJsonString(json, entry.getKey());
            json.append(':');
            appendCanonicalValue(json, entry.getValue());
        }
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode strictObject(
            byte[] bytes, int maximumBytes, Set<String> exactFields) {
        JsonNode node = parseObject(bytes, maximumBytes);
        if (!fieldNames(node).equals(exactFields)) {
            throw Hrm013SafeErrorV1.malformed();
        }
        return node;
    }

    static JsonNode strictObjectVariable(
            byte[] bytes,
            int maximumBytes,
            Set<String> requiredFields,
            Set<String> optionalFields) {
        JsonNode node = parseObject(bytes, maximumBytes);
        Set<String> actual = fieldNames(node);
        if (!actual.containsAll(requiredFields)) {
            throw Hrm013SafeErrorV1.malformed();
        }
        Set<String> allowed = new HashSet<>(requiredFields);
        allowed.addAll(optionalFields);
        if (!allowed.containsAll(actual)) {
            throw Hrm013SafeErrorV1.malformed();
        }
        return node;
    }

    static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw Hrm013SafeErrorV1.malformed();
        }
        return value.textValue();
    }

    static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw Hrm013SafeErrorV1.malformed();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw Hrm013SafeErrorV1.malformed();
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw Hrm013SafeErrorV1.malformed();
        }
        return value.longValue();
    }

    static void requireCanonical(byte[] actual, byte[] canonical) {
        if (!Arrays.equals(actual, canonical)) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    static void requireMaximum(byte[] bytes, int maximumBytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    static Map<String, Object> values(Object... keysAndValues) {
        if ((keysAndValues.length & 1) != 0) {
            throw new IllegalArgumentException("key/value pairs required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            String key = (String) keysAndValues[index];
            Object value = Objects.requireNonNull(keysAndValues[index + 1], key);
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate key");
            }
        }
        return result;
    }

    static void requireDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    static void requireCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a canonical code");
        }
    }

    static void requirePositive(long value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
    }

    static void requireNonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be nonnegative");
    }

    static void requireUuid(UUID value, String field) {
        Objects.requireNonNull(value, field);
    }

    static void requireInstant(Instant value, String field) {
        Objects.requireNonNull(value, field);
        if (!Instant.parse(value.toString()).equals(value)) {
            throw new IllegalArgumentException(field + " is not canonical");
        }
    }

    static void requireOneOf(String value, String field, String... allowed) {
        if (value == null || Arrays.stream(allowed).noneMatch(value::equals)) {
            throw new IllegalArgumentException(field + " is not allowed");
        }
    }

    private static JsonNode parseObject(byte[] bytes, int maximumBytes) {
        requireMaximum(bytes, maximumBytes);
        requireUtf8(bytes);
        try (JsonParser parser = JSON.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = JSON.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) {
                throw Hrm013SafeErrorV1.malformed();
            }
            for (JsonNode value : iterable(node.elements())) {
                if (value == null || value.isNull() || value.isContainerNode()) {
                    throw Hrm013SafeErrorV1.malformed();
                }
            }
            return node;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (IOException | RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    private static Iterable<JsonNode> iterable(java.util.Iterator<JsonNode> iterator) {
        List<JsonNode> values = new ArrayList<>();
        iterator.forEachRemaining(values::add);
        return values;
    }

    private static void requireUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    private static void appendCanonicalValue(StringBuilder target, Object value) {
        Objects.requireNonNull(value, "canonical value");
        if (value instanceof String string) {
            appendJsonString(target, string);
        } else if (value instanceof UUID || value instanceof Instant) {
            appendJsonString(target, value.toString());
        } else if (value instanceof Integer integer) {
            target.append(integer);
        } else if (value instanceof Long number) {
            target.append(number);
        } else {
            throw new IllegalArgumentException(
                    "unsupported canonical JSON value type: " + value.getClass().getName());
        }
    }

    private static void appendJsonString(StringBuilder target, String value) {
        Objects.requireNonNull(value, "JSON string");
        target.append('"');
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int width = Character.charCount(codePoint);
            if (width == 1 && Character.isSurrogate(value.charAt(offset))) {
                throw new IllegalArgumentException("unpaired surrogate is forbidden");
            }
            offset += width;
            switch (codePoint) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\t' -> target.append("\\t");
                case '\n' -> target.append("\\n");
                case '\f' -> target.append("\\f");
                case '\r' -> target.append("\\r");
                default -> {
                    if (codePoint < 0x20) {
                        target.append("\\u")
                                .append(String.format("%04x", codePoint));
                    } else {
                        target.appendCodePoint(codePoint);
                    }
                }
            }
        }
        target.append('"');
    }

    private static void requireExactAscii(String value, String field) {
        if (value == null || !ASCII_120.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not bounded ASCII");
        }
    }

    private static void requireWorkload(String value, String field) {
        if (value == null || !value.matches("^dwp-[a-z]+-server$")) {
            throw new IllegalArgumentException(field + " is not canonical");
        }
    }

    private static String canonicalUuidRegex() {
        return "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    }

    private static String pathRegex(String template) {
        StringBuilder regex = new StringBuilder("^");
        int offset = 0;
        while (offset < template.length()) {
            int opening = template.indexOf('{', offset);
            if (opening < 0) {
                regex.append(Pattern.quote(template.substring(offset)));
                break;
            }
            regex.append(Pattern.quote(template.substring(offset, opening)));
            int closing = template.indexOf('}', opening);
            if (closing < 0) {
                throw new IllegalArgumentException("invalid path template");
            }
            String token = template.substring(opening, closing + 1);
            if (!Set.of("{casePublicId}", "{taskPublicId}").contains(token)) {
                throw new IllegalArgumentException("unknown path template token");
            }
            regex.append(canonicalUuidRegex());
            offset = closing + 1;
        }
        return regex.append('$').toString();
    }

    private static void requireKnownProfile(BindingProfile profile) {
        if (!PROFILES.contains(profile)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }
}
