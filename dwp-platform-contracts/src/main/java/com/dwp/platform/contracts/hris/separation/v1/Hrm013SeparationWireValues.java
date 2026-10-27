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

/** Canonical normalization and validation for contract values. */
final class Hrm013SeparationWireValues {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9._-]{0,79}$");

    private static final Pattern ASCII_120 = Pattern.compile("^[\\x21-\\x7e]{1,120}$");

    static final String PEOPLE = "dwp-people-server";

    static final String APPROVAL = "dwp-approval-server";

    static final String TIME = "dwp-time-server";

    static final String PAYROLL = "dwp-payroll-server";

    static final String AUTH = "dwp-auth-server";

    private Hrm013SeparationWireValues() {
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

    static JsonNode parseObject(byte[] bytes, int maximumBytes) {
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

    static Iterable<JsonNode> iterable(java.util.Iterator<JsonNode> iterator) {
        List<JsonNode> values = new ArrayList<>();
        iterator.forEachRemaining(values::add);
        return values;
    }

    static void requireUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    static void appendCanonicalValue(StringBuilder target, Object value) {
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

    static void appendJsonString(StringBuilder target, String value) {
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

    static void requireExactAscii(String value, String field) {
        if (value == null || !ASCII_120.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not bounded ASCII");
        }
    }

    static void requireWorkload(String value, String field) {
        if (value == null || !value.matches("^dwp-[a-z]+-server$")) {
            throw new IllegalArgumentException(field + " is not canonical");
        }
    }

    static String canonicalUuidRegex() {
        return "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    }

    static String pathRegex(String template) {
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

    static void bindingProfileFields(
            String bindingId,
            Enum<?> operation,
            String callerWorkloadId,
            String ownerWorkloadId,
            String purpose,
            String pathTemplate) {
        requireCode(bindingId, "bindingId");
        Objects.requireNonNull(operation, "operation");
        requireWorkload(callerWorkloadId, "callerWorkloadId");
        requireWorkload(ownerWorkloadId, "ownerWorkloadId");
        requireCode(purpose, "purpose");
        if (pathTemplate == null || !pathTemplate.startsWith("/internal/")) {
            throw new IllegalArgumentException("pathTemplate is not internal");
        }
    }

    static void separationWaiverApprovalRequestV1Fields(
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
            UUID correlationId) {
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

    static void separationTaskReceiptV2Fields(
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
            UUID correlationId) {
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

    static void separationReconciliationResultV1Fields(
            UUID callbackEventId,
            UUID casePublicId,
            String result,
            long ownerRevision,
            String originatingPayloadSha256,
            Instant occurredAt,
            String payloadSha256,
            UUID correlationId) {
        requireUuid(callbackEventId, "callbackEventId");
        requireUuid(casePublicId, "casePublicId");
        requireOneOf(result, "result", "CONSISTENT", "CORRECTION_REQUIRED");
        requirePositive(ownerRevision, "ownerRevision");
        requireDigest(originatingPayloadSha256, "originatingPayloadSha256");
        requireInstant(occurredAt, "occurredAt");
        requireDigest(payloadSha256, "payloadSha256");
        requireUuid(correlationId, "correlationId");
    }

    static void separationWaiverDecisionV2Fields(
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
            UUID correlationId) {
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

    static void separationWaiverApprovalSubmissionReceiptV1Fields(
            UUID approvalRequestId,
            long requestRevision,
            String state,
            String requestDigest,
            UUID correlationId,
            Instant acceptedAt) {
        requireUuid(approvalRequestId, "approvalRequestId");
        requirePositive(requestRevision, "requestRevision");
        requireOneOf(state, "state", "PENDING");
        requireDigest(requestDigest, "requestDigest");
        requireUuid(correlationId, "correlationId");
        requireInstant(acceptedAt, "acceptedAt");
    }

    static void separationCallbackReceiptV1Fields(
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
}
