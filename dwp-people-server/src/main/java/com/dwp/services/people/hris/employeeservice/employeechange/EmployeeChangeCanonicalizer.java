package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Canonicalizes caller content before hashing, encrypting, or binding idempotency. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeCanonicalizer {

    private static final Set<String> TARGET_TYPES = Set.of(
            "PERSON", "WORKER", "EMPLOYMENT_RELATIONSHIP", "ASSIGNMENT");
    private static final Map<String, Set<String>> PATCH_PATHS = Map.of(
            "PERSON", Set.of("/displayName", "/preferredLocale", "/timeZone"),
            "WORKER", Set.of("/workerType", "/workerStatus", "/originalHireDate"),
            "EMPLOYMENT_RELATIONSHIP", Set.of(
                    "/relationshipType", "/primaryRelationship", "/startDate",
                    "/endDate", "/projectedEndDate"),
            "ASSIGNMENT", Set.of(
                    "/assignmentStatus", "/businessTitle", "/costCenterKey",
                    "/workerHours", "/fullTimeEquivalent", "/changeReasonCode"));
    private static final Map<String, String> FIELD_GROUPS = Map.ofEntries(
            Map.entry("/displayName", "PERSON_NAME"),
            Map.entry("/preferredLocale", "PERSON_PROFILE"),
            Map.entry("/timeZone", "PERSON_PROFILE"),
            Map.entry("/workerType", "EMPLOYMENT"),
            Map.entry("/workerStatus", "EMPLOYMENT"),
            Map.entry("/originalHireDate", "EMPLOYMENT"),
            Map.entry("/relationshipType", "EMPLOYMENT"),
            Map.entry("/primaryRelationship", "EMPLOYMENT"),
            Map.entry("/startDate", "EMPLOYMENT"),
            Map.entry("/endDate", "EMPLOYMENT"),
            Map.entry("/projectedEndDate", "EMPLOYMENT"),
            Map.entry("/assignmentStatus", "ASSIGNMENT"),
            Map.entry("/businessTitle", "ASSIGNMENT"),
            Map.entry("/costCenterKey", "ASSIGNMENT"),
            Map.entry("/workerHours", "ASSIGNMENT"),
            Map.entry("/fullTimeEquivalent", "ASSIGNMENT"),
            Map.entry("/changeReasonCode", "ASSIGNMENT"));
    private static final Set<String> NULLABLE_PATHS = Set.of(
            "/preferredLocale", "/timeZone", "/originalHireDate", "/endDate",
            "/projectedEndDate", "/businessTitle", "/costCenterKey",
            "/workerHours", "/fullTimeEquivalent", "/changeReasonCode");

    private final ObjectMapper mapper;

    EmployeeChangeCanonicalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    CanonicalPatch canonicalPatch(EmployeeChangeDtos.CreateRequest request) {
        String targetType = code(request.targetAggregateType(), "targetAggregateType", 80);
        if (!TARGET_TYPES.contains(targetType)) {
            throw invalid("The target aggregate type is not supported.");
        }
        String changeType = code(request.changeType(), "changeType", 64);
        String schemaKey = schemaKey(request.patchSchemaKey());
        if (request.patchSchemaVersion() == null || request.patchSchemaVersion() != 1) {
            throw invalid("Only employee-change patch schema version 1 is published.");
        }
        Set<String> allowed = PATCH_PATHS.get(targetType);
        Map<String, EmployeeChangeDtos.PatchOperation> paths = new TreeMap<>();
        if (request.patch() == null || request.patch().isEmpty()
                || request.patch().size() > 100) {
            throw invalid("The employee-change patch must contain 1 to 100 operations.");
        }
        for (EmployeeChangeDtos.PatchOperation operation : request.patch()) {
            if (operation == null || !allowed.contains(operation.path())) {
                throw invalid("The patch contains a field outside the target schema.");
            }
            String op = operation.op() == null
                    ? "" : operation.op().trim().toLowerCase(Locale.ROOT);
            if (!Set.of("add", "replace", "remove").contains(op)) {
                throw invalid("The patch operation is not supported.");
            }
            if ("remove".equals(op) && operation.value() != null) {
                throw invalid("A remove operation cannot include a value.");
            }
            if ("remove".equals(op) && !NULLABLE_PATHS.contains(operation.path())) {
                throw invalid("The patch cannot remove a required target field.");
            }
            if (!"remove".equals(op) && operation.value() == null) {
                throw invalid("An add or replace operation requires a value.");
            }
            if (!"remove".equals(op)) validateValue(operation.path(), operation.value());
            if (paths.put(operation.path(),
                    new EmployeeChangeDtos.PatchOperation(op, operation.path(), operation.value()))
                    != null) {
                throw invalid("Each employee-change field may appear only once.");
            }
        }
        List<EmployeeChangeDtos.PatchOperation> normalized = List.copyOf(paths.values());
        List<String> fieldGroups = normalized.stream()
                .map(operation -> FIELD_GROUPS.get(operation.path()))
                .distinct().sorted().toList();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaKey", schemaKey);
        envelope.put("schemaVersion", 1);
        envelope.put("targetAggregateType", targetType);
        envelope.put("changeType", changeType);
        envelope.put("patch", normalized);
        byte[] bytes = canonicalJson(envelope).getBytes(StandardCharsets.UTF_8);
        return new CanonicalPatch(
                targetType, changeType, schemaKey, normalized, fieldGroups, bytes, sha256(bytes));
    }

    String requestDigest(Object value) {
        return sha256(canonicalJson(value).getBytes(StandardCharsets.UTF_8));
    }

    String digestText(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    long positiveRevision(String value) {
        byte[] digest = digest(value.getBytes(StandardCharsets.UTF_8));
        long result = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            result = (result << 8) | (digest[index] & 0xffL);
        }
        return result == Long.MIN_VALUE ? 1 : Math.max(1, Math.abs(result));
    }

    String canonicalJson(Object value) {
        try {
            return mapper.writeValueAsString(canonical(mapper.valueToTree(value)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Employee-change canonical serialization failed.", exception);
        }
    }

    <T> T decode(byte[] bytes, Class<T> type) {
        try {
            return mapper.readValue(bytes, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Frozen employee-change patch is invalid.", exception);
        }
    }

    CanonicalPatch thaw(
            byte[] bytes,
            String expectedDigest,
            String expectedTargetType,
            String expectedChangeType,
            String expectedSchemaKey) {
        FrozenPatch frozen = decode(bytes, FrozenPatch.class);
        EmployeeChangeDtos.CreateRequest request = new EmployeeChangeDtos.CreateRequest(
                new UUID(0, 1), frozen.targetAggregateType(), new UUID(0, 2),
                frozen.changeType(), LocalDate.of(2000, 1, 1), frozen.schemaKey(),
                frozen.schemaVersion(), frozen.patch(), List.of());
        CanonicalPatch canonical = canonicalPatch(request);
        if (!MessageDigest.isEqual(bytes, canonical.bytes())
                || !canonical.digest().equals(expectedDigest)
                || !canonical.targetAggregateType().equals(expectedTargetType)
                || !canonical.changeType().equals(expectedChangeType)
                || !canonical.schemaKey().equals(expectedSchemaKey)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The frozen employee-change patch does not match its immutable evidence.");
        }
        return canonical;
    }

    private JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) return mapper.nullNode();
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private String schemaKey(String value) {
        if (value == null || value.isBlank() || value.length() > 120
                || !value.equals(value.trim())
                || !value.matches("[a-z][a-z0-9._-]{2,127}")) {
            throw invalid("The employee-change patch schema key is invalid.");
        }
        return value;
    }

    private void validateValue(String path, Object raw) {
        try {
            switch (path) {
                case "/displayName" -> text(raw, 1, 200, null);
                case "/preferredLocale" -> {
                    String value = text(raw, 2, 35, "[A-Za-z0-9_-]{2,35}");
                    if (Locale.forLanguageTag(value.replace('_', '-')).getLanguage().isBlank()) {
                        throw new IllegalArgumentException();
                    }
                }
                case "/timeZone" -> ZoneId.of(text(raw, 1, 80, null));
                case "/workerType", "/relationshipType" -> enumValue(raw,
                        Set.of("EMPLOYEE", "CONTINGENT", "NONWORKER", "PENDING"));
                case "/workerStatus" -> enumValue(raw,
                        Set.of("ACTIVE", "LEAVE", "TERMINATED", "PENDING"));
                case "/assignmentStatus" -> enumValue(raw,
                        Set.of("ACTIVE", "SUSPENDED", "ENDED", "PENDING"));
                case "/originalHireDate", "/startDate", "/endDate",
                        "/projectedEndDate" -> LocalDate.parse(
                        text(raw, 10, 10, "\\d{4}-\\d{2}-\\d{2}"));
                case "/primaryRelationship" -> {
                    if (!(raw instanceof Boolean)) throw new IllegalArgumentException();
                }
                case "/businessTitle" -> text(raw, 1, 240, null);
                case "/costCenterKey" -> text(
                        raw, 1, 100, "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
                case "/changeReasonCode" -> text(
                        raw, 1, 80, "[A-Za-z][A-Za-z0-9._-]{0,79}");
                case "/workerHours" -> decimal(
                        raw, new BigDecimal("0.01"), new BigDecimal("168"));
                case "/fullTimeEquivalent" -> decimal(raw, BigDecimal.ZERO, BigDecimal.ONE);
                default -> throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw invalid("The value for " + path + " is invalid.");
        }
    }

    private String text(Object raw, int minimum, int maximum, String pattern) {
        if (!(raw instanceof String value) || !value.equals(value.trim())
                || value.length() < minimum || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)
                || (pattern != null && !value.matches(pattern))) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private void enumValue(Object raw, Set<String> allowed) {
        String value = text(raw, 1, 40, "[A-Za-z][A-Za-z0-9_-]{0,39}")
                .toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) throw new IllegalArgumentException();
    }

    private void decimal(Object raw, BigDecimal minimum, BigDecimal maximum) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            throw new IllegalArgumentException();
        }
        BigDecimal value = new BigDecimal(raw.toString());
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException();
        }
    }

    private String code(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.trim())) {
            throw invalid(field + " is invalid.");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9._-]{0," + (maximum - 1) + "}")) {
            throw invalid(field + " is invalid.");
        }
        return normalized;
    }

    private String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest(bytes));
    }

    private byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    record CanonicalPatch(
            String targetAggregateType,
            String changeType,
            String schemaKey,
            List<EmployeeChangeDtos.PatchOperation> operations,
            List<String> fieldGroups,
            byte[] bytes,
            String digest) {
        CanonicalPatch {
            operations = List.copyOf(operations);
            fieldGroups = List.copyOf(fieldGroups);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    private record FrozenPatch(
            String schemaKey,
            int schemaVersion,
            String targetAggregateType,
            String changeType,
            List<EmployeeChangeDtos.PatchOperation> patch) {
    }
}
