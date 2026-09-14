package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** A planning binding, not authority to enumerate candidates or cast a task vote. */
public record ApprovalWorkflowRoleBinding(long tenantId, long actorId, UUID personPublicId, UUID requestId,
        String contextKey, String contextScopeKey, String decisionRevision, String accessMode,
        String routeContractKey, String managementResourceSetKey, List<String> roleCodes, Map<String, Object> sealed) {
    private static final Set<String> FIELDS = Set.of("tenantId", "actorId", "personPublicId", "requestId", "requestVersion",
            "workflowVersionId", "workflowVersion", "workflowDefinitionSha256", "formVersionId", "formSchemaSha256",
            "payloadRevision", "payloadSha256", "policyVersion", "policySha256", "contextKey", "contextScopeKey",
            "decisionRevision", "accessMode", "routeContractKey", "managementResourceSetKey", "roleCodes",
            "publishedDefinition", "method", "path", "idempotencyKey");

    public ApprovalWorkflowRoleBinding {
        roleCodes = List.copyOf(roleCodes);
        sealed = java.util.Collections.unmodifiableMap(new TreeMap<>(sealed));
    }

    static ApprovalWorkflowRoleBinding parse(JsonNode value, ObjectMapper mapper) {
        exact(value, FIELDS);
        long tenant = integer(value, "tenantId", 1), actor = integer(value, "actorId", 1);
        UUID person = uuid(value, "personPublicId"), request = uuid(value, "requestId");
        uuid(value, "workflowVersionId"); uuid(value, "formVersionId");
        integer(value, "requestVersion", 0); integer(value, "workflowVersion", 1);
        integer(value, "payloadRevision", 1); integer(value, "policyVersion", 1);
        for (String field : List.of("workflowDefinitionSha256", "formSchemaSha256", "payloadSha256", "policySha256")) {
            if (!text(value, field).matches("[a-f0-9]{64}")) throw denied();
        }
        String mode = text(value, "accessMode"), revision = text(value, "decisionRevision");
        if (!Set.of("NORMAL", "ELEVATED").contains(mode) || !revision.matches("psr-[a-f0-9]{64}")) throw denied();
        if (!text(value, "idempotencyKey").matches("[A-Za-z0-9._:-]{1,120}")) throw denied();
        String route = text(value, "routeContractKey"), method = text(value, "method"), path = text(value, "path");
        String prefix = "/v1/requests/" + request;
        boolean canonical = switch (route) {
            case "route.approvals.work.request-draft-update.action" -> "PUT".equals(method) && (prefix + "/draft").equals(path);
            case "route.approvals.work.request-draft-recover.action" -> "POST".equals(method) && (prefix + "/draft/recover").equals(path);
            case "route.approvals.work.request-submit.action" -> "POST".equals(method) && (prefix + "/submit").equals(path);
            default -> false;
        };
        if (!canonical) throw denied();
        String definition = largeText(value, "publishedDefinition", 131072);
        JsonNode published;
        try { published = mapper.readTree(definition); }
        catch (java.io.IOException exception) { throw denied(); }
        exact(published, Set.of("schemaContract", "schemaVersion", "slaMinutes", "stages"));
        if (!"DWP_APPROVAL_WORKFLOW_QUORUM_V2".equals(text(published, "schemaContract"))
                || integer(published, "schemaVersion", 2) != 2 || integer(published, "slaMinutes", 15) > 525600) throw denied();
        String canonicalDefinition = json(mapper, canonical(published));
        if (!definition.equals(canonicalDefinition) || !sha256(definition).equals(text(value, "workflowDefinitionSha256"))) throw denied();
        var stages = published.get("stages");
        if (!stages.isArray() || stages.isEmpty() || stages.size() > 64) throw denied();
        var derived = new java.util.TreeSet<String>();
        for (JsonNode stage : stages) {
            exact(stage, stage.has("routeCondition")
                    ? Set.of("key", "name", "candidateRole", "quorum", "slaMinutes", "predecessors", "routeCondition")
                    : Set.of("key", "name", "candidateRole", "quorum", "slaMinutes", "predecessors"));
            String role = text(stage, "candidateRole");
            if (!role.matches("[A-Z][A-Z0-9_]{1,49}")) throw denied();
            derived.add(role);
        }
        JsonNode supplied = value.get("roleCodes");
        if (!supplied.isArray() || supplied.isEmpty() || supplied.size() > 64) throw denied();
        List<String> codes = new ArrayList<>();
        for (JsonNode code : supplied) {
            if (!code.isTextual()) throw denied();
            codes.add(code.textValue());
        }
        if (!codes.equals(List.copyOf(derived))) throw denied();
        @SuppressWarnings("unchecked") var sealed = (Map<String, Object>) canonical(value);
        return new ApprovalWorkflowRoleBinding(tenant, actor, person, request, text(value, "contextKey"),
                text(value, "contextScopeKey"), revision, mode, route, text(value, "managementResourceSetKey"), codes, sealed);
    }

    static Object canonical(JsonNode value) {
        if (value.isObject()) {
            var result = new TreeMap<String, Object>();
            value.properties().forEach(entry -> result.put(entry.getKey(), canonical(entry.getValue())));
            return java.util.Collections.unmodifiableMap(result);
        }
        if (value.isArray()) {
            var result = new ArrayList<Object>(); value.forEach(item -> result.add(canonical(item)));
            return java.util.Collections.unmodifiableList(result);
        }
        return value.deepCopy();
    }

    static String json(ObjectMapper mapper, Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException exception) { throw unavailable(); }
    }

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static void exact(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject()) throw denied();
        var actual = new HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw denied();
    }

    static String text(JsonNode value, String field) { return largeText(value, field, 512); }
    private static String largeText(JsonNode value, String field, int limit) {
        var item = value.get(field);
        if (item == null || !item.isTextual() || item.textValue().isBlank() || item.textValue().length() > limit
                || !item.textValue().equals(item.textValue().strip())
                || item.textValue().codePoints().anyMatch(Character::isISOControl)) throw denied();
        return item.textValue();
    }

    static long integer(JsonNode value, String field, long minimum) {
        var item = value.get(field);
        if (item == null || !item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() < minimum) throw denied();
        return item.longValue();
    }

    static UUID uuid(JsonNode value, String field) {
        try {
            String raw = text(value, field); UUID parsed = UUID.fromString(raw);
            if (!raw.equals(parsed.toString())) throw denied();
            return parsed;
        } catch (IllegalArgumentException exception) { throw denied(); }
    }

    static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "The sealed workflow role binding was rejected."); }
    static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Workflow role proof verification is unavailable."); }
}
