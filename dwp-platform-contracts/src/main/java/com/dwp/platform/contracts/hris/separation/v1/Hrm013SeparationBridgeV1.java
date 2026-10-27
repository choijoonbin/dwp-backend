package com.dwp.platform.contracts.hris.separation.v1;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationWireValues.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    public static final String PEOPLE = Hrm013SeparationWireValues.PEOPLE;
    public static final String APPROVAL = Hrm013SeparationWireValues.APPROVAL;
    public static final String TIME = Hrm013SeparationWireValues.TIME;
    public static final String PAYROLL = Hrm013SeparationWireValues.PAYROLL;
    public static final String AUTH = Hrm013SeparationWireValues.AUTH;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9._-]{0,79}$");
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
            Hrm013SeparationWireValues.bindingProfileFields(bindingId, operation, callerWorkloadId, ownerWorkloadId, purpose, pathTemplate);
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
            Hrm013SeparationWireValues.separationWaiverApprovalRequestV1Fields(
                    approvalRequestId, casePublicId, taskPublicId, taskType, caseMakerPublicId,
                    requestedByActorPublicId, reasonCode, taskOwnerRevision, taskVersion, requestedAt, payloadSha256,
                    correlationId);
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
            Hrm013SeparationWireValues.separationTaskReceiptV2Fields(
                    callbackEventId, casePublicId, taskPublicId, taskType, status, ownerRevision,
                    externalReceiptPublicId, originatingPayloadSha256, occurredAt, payloadSha256, correlationId);
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
            Hrm013SeparationWireValues.separationReconciliationResultV1Fields(
                    callbackEventId, casePublicId, result, ownerRevision, originatingPayloadSha256, occurredAt,
                    payloadSha256, correlationId);
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
            Hrm013SeparationWireValues.separationWaiverDecisionV2Fields(
                    callbackEventId, approvalRequestId, casePublicId, taskPublicId, decision, decisionActorPublicId,
                    ownerRevision, decisionRevision, reasonCode, originatingPayloadSha256, stepUpEvidenceId,
                    stepUpEvidenceAudience, stepUpEvidencePurpose, stepUpEvidenceNonce, stepUpEvidenceDigest,
                    stepUpVerifiedAt, stepUpConsumedAt, occurredAt, payloadSha256, correlationId);
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
            Hrm013SeparationWireValues.separationWaiverApprovalSubmissionReceiptV1Fields(
                    approvalRequestId, requestRevision, state, requestDigest, correlationId, acceptedAt);
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
            Hrm013SeparationWireValues.separationCallbackReceiptV1Fields(
                    receiptId, callbackEventId, callbackType, casePublicId, taskPublicId, ownerRevision, outcome,
                    correlationId, commandDigest, recordedAt);
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
        return Hrm013SeparationWireDecoder.decodeWaiverRequest(
                bytes, SeparationWaiverApprovalRequestV1::new, Hrm013SeparationBridgeV1::encode);
    }

    public static SeparationTaskReceiptV2 decodeTaskReceipt(byte[] bytes) {
        return Hrm013SeparationWireDecoder.decodeTaskReceipt(
                bytes, SeparationTaskReceiptV2::new, Hrm013SeparationBridgeV1::encode);
    }

    public static SeparationReconciliationResultV1 decodeReconciliationResult(
            byte[] bytes) {
        return Hrm013SeparationWireDecoder.decodeReconciliationResult(
                bytes, SeparationReconciliationResultV1::new, Hrm013SeparationBridgeV1::encode);
    }

    public static SeparationWaiverDecisionV2 decodeWaiverDecision(byte[] bytes) {
        return Hrm013SeparationWireDecoder.decodeWaiverDecision(
                bytes, SeparationWaiverDecisionV2::new, Hrm013SeparationBridgeV1::encode);
    }

    public static SeparationWaiverApprovalSubmissionReceiptV1 decodeWaiverReceipt(
            byte[] bytes) {
        return Hrm013SeparationWireDecoder.decodeWaiverReceipt(
                bytes, SeparationWaiverApprovalSubmissionReceiptV1::new, Hrm013SeparationBridgeV1::encode);
    }

    public static SeparationCallbackReceiptV1 decodeCallbackReceipt(byte[] bytes) {
        return Hrm013SeparationWireDecoder.decodeCallbackReceipt(
                bytes, SeparationCallbackReceiptV1::new, Hrm013SeparationBridgeV1::encode);
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
        return Hrm013SeparationWireValues.sha256(bytes);
    }

    static byte[] canonicalObjectBytes(Map<String, ?> source) {
        return Hrm013SeparationWireValues.canonicalObjectBytes(source);
    }

    static JsonNode strictObject(
            byte[] bytes, int maximumBytes, Set<String> exactFields) {
        return Hrm013SeparationWireValues.strictObject(bytes, maximumBytes, exactFields);
    }

    static JsonNode strictObjectVariable(
            byte[] bytes,
            int maximumBytes,
            Set<String> requiredFields,
            Set<String> optionalFields) {
        return Hrm013SeparationWireValues.strictObjectVariable(bytes, maximumBytes, requiredFields, optionalFields);
    }

    static Set<String> fieldNames(JsonNode node) {
        return Hrm013SeparationWireValues.fieldNames(node);
    }

    static String text(JsonNode node, String field) {
        return Hrm013SeparationWireValues.text(node, field);
    }

    static UUID uuid(JsonNode node, String field) {
        return Hrm013SeparationWireValues.uuid(node, field);
    }

    static Instant instant(JsonNode node, String field) {
        return Hrm013SeparationWireValues.instant(node, field);
    }

    static long number(JsonNode node, String field) {
        return Hrm013SeparationWireValues.number(node, field);
    }

    static void requireCanonical(byte[] actual, byte[] canonical) {
        Hrm013SeparationWireValues.requireCanonical(actual, canonical);
    }

    static void requireMaximum(byte[] bytes, int maximumBytes) {
        Hrm013SeparationWireValues.requireMaximum(bytes, maximumBytes);
    }

    static boolean constantTimeEquals(String left, String right) {
        return Hrm013SeparationWireValues.constantTimeEquals(left, right);
    }

    static Map<String, Object> values(Object... keysAndValues) {
        return Hrm013SeparationWireValues.values(keysAndValues);
    }

    static void requireDigest(String value, String field) {
        Hrm013SeparationWireValues.requireDigest(value, field);
    }

    static void requireCode(String value, String field) {
        Hrm013SeparationWireValues.requireCode(value, field);
    }

    static void requirePositive(long value, String field) {
        Hrm013SeparationWireValues.requirePositive(value, field);
    }

    static void requireNonNegative(long value, String field) {
        Hrm013SeparationWireValues.requireNonNegative(value, field);
    }

    static void requireUuid(UUID value, String field) {
        Hrm013SeparationWireValues.requireUuid(value, field);
    }

    static void requireInstant(Instant value, String field) {
        Hrm013SeparationWireValues.requireInstant(value, field);
    }

    static void requireOneOf(String value, String field, String... allowed) {
        Hrm013SeparationWireValues.requireOneOf(value, field, allowed);
    }

    private static void requireKnownProfile(BindingProfile profile) {
        if (!PROFILES.contains(profile)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }
}
