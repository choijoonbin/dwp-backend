package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed owner payloads. Canonical bytes, never the caller's JSON spelling, are digested and encrypted. */
@Component
final class Per004PayloadCodec {
    private final ObjectMapper mapper;

    Per004PayloadCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    CanonicalPayload canonicalize(String schemaKey, JsonNode payload) {
        if (!HrisReferenceConfigurationV1.SCHEMAS.contains(schemaKey) || payload == null || !payload.isObject())
            throw rejected();
        try {
            if (mapper.writeValueAsBytes(payload).length > HrisReferenceConfigurationV1.MAX_OWNER_PAYLOAD_UTF8)
                throw rejected();
        } catch (JsonProcessingException exception) {
            throw rejected();
        }
        ObjectNode canonical = switch (schemaKey) {
            case "dwp.hris.performance.review-form.v1" -> form(payload);
            case "dwp.hris.performance.rating-scale.v1" -> scale(payload);
            case "dwp.hris.performance.policy.v1" -> policy(payload);
            default -> throw rejected();
        };
        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical);
            if (bytes.length > HrisReferenceConfigurationV1.MAX_OWNER_PAYLOAD_UTF8) throw rejected();
            return new CanonicalPayload(bytes, HrisReferenceConfigurationWorkloadV1.sha256(bytes));
        } catch (JsonProcessingException exception) {
            throw rejected();
        }
    }

    private ObjectNode form(JsonNode source) {
        fields(source, "schemaVersion", "templateVersionId", "templateDigest", "title", "sections");
        version(source);
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("templateVersionId", uuid(source, "templateVersionId"));
        result.put("templateDigest", digest(source, "templateDigest"));
        result.put("title", label(source, "title", 200));
        JsonNode sections = array(source, "sections", 1, 50);
        ArrayNode canonicalSections = result.putArray("sections");
        Set<String> sectionKeys = new HashSet<>();
        for (JsonNode section : sections) {
            fields(section, "key", "title", "items");
            String key = key(section, "key");
            if (!sectionKeys.add(key)) throw rejected();
            ObjectNode out = canonicalSections.addObject();
            out.put("key", key);
            out.put("title", label(section, "title", 200));
            ArrayNode items = out.putArray("items");
            Set<String> itemKeys = new HashSet<>();
            for (JsonNode item : array(section, "items", 1, 100)) {
                fields(item, "key", "label", "answerKind", "required");
                String itemKey = key(item, "key");
                if (!itemKeys.add(itemKey)) throw rejected();
                String kind = label(item, "answerKind", 20);
                if (!Set.of("TEXT", "DECIMAL", "RATING", "CHOICE").contains(kind)
                        || !item.path("required").isBoolean()) throw rejected();
                ObjectNode target = items.addObject();
                target.put("key", itemKey);
                target.put("label", label(item, "label", 500));
                target.put("answerKind", kind);
                target.put("required", item.get("required").booleanValue());
            }
        }
        return result;
    }

    private ObjectNode scale(JsonNode source) {
        fields(source, "schemaVersion", "templateVersionId", "templateDigest", "title", "levels");
        version(source);
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("templateVersionId", uuid(source, "templateVersionId"));
        result.put("templateDigest", digest(source, "templateDigest"));
        result.put("title", label(source, "title", 200));
        ArrayNode levels = result.putArray("levels");
        Set<String> keys = new HashSet<>();
        Set<Integer> scores = new HashSet<>();
        for (JsonNode level : array(source, "levels", 2, 20)) {
            fields(level, "key", "label", "score");
            String key = key(level, "key");
            JsonNode score = level.get("score");
            if (!keys.add(key) || score == null || !score.isIntegralNumber()
                    || !score.canConvertToInt() || score.intValue() < 0 || score.intValue() > 100
                    || !scores.add(score.intValue())) throw rejected();
            ObjectNode out = levels.addObject();
            out.put("key", key);
            out.put("label", label(level, "label", 200));
            out.put("score", score.intValue());
        }
        return result;
    }

    private ObjectNode policy(JsonNode source) {
        fields(source, "schemaVersion", "form", "ratingScale", "minimumReviewers", "allowSelfReview");
        version(source);
        JsonNode reviewers = source.get("minimumReviewers");
        if (reviewers == null || !reviewers.isIntegralNumber() || !reviewers.canConvertToInt()
                || reviewers.intValue() < 1 || reviewers.intValue() > 20
                || !source.path("allowSelfReview").isBoolean()) throw rejected();
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", 1);
        result.set("form", reference(source.get("form")));
        result.set("ratingScale", reference(source.get("ratingScale")));
        result.put("minimumReviewers", reviewers.intValue());
        result.put("allowSelfReview", source.get("allowSelfReview").booleanValue());
        return result;
    }

    private ObjectNode reference(JsonNode source) {
        fields(source, "artifactPublicId", "payloadDigest");
        ObjectNode result = mapper.createObjectNode();
        result.put("artifactPublicId", uuid(source, "artifactPublicId"));
        result.put("payloadDigest", digest(source, "payloadDigest"));
        return result;
    }

    private static void fields(JsonNode source, String... expected) {
        if (source == null || !source.isObject() || source.size() != expected.length
                || !source.properties().stream().map(java.util.Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet()).equals(Set.of(expected))) throw rejected();
    }
    private static void version(JsonNode source) {
        JsonNode version = source.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() != 1) throw rejected();
    }
    private static JsonNode array(JsonNode source, String field, int minimum, int maximum) {
        JsonNode value = source.get(field);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum)
            throw rejected();
        return value;
    }
    private static String label(JsonNode source, String field, int maximum) {
        JsonNode value = source.get(field);
        if (value == null || !value.isTextual()) throw rejected();
        String normalized = Normalizer.normalize(value.textValue().trim(), Normalizer.Form.NFC);
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > maximum
                || normalized.chars().anyMatch(c -> Character.isISOControl(c))) throw rejected();
        return normalized;
    }
    private static String key(JsonNode source, String field) {
        String value = label(source, field, 80);
        if (!value.matches("^[a-z][a-z0-9_-]*$")) throw rejected();
        return value;
    }
    private static String uuid(JsonNode source, String field) {
        String value = label(source, field, 36);
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw rejected();
            return value;
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }
    private static String digest(JsonNode source, String field) {
        String value = label(source, field, 64);
        if (!value.matches("^[0-9a-f]{64}$")) throw rejected();
        return value;
    }
    static IllegalArgumentException rejected() {
        return new IllegalArgumentException("PER004_TYPED_PAYLOAD_INVALID");
    }
    record CanonicalPayload(byte[] bytes, String digest) {
        CanonicalPayload { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        JsonNode json(ObjectMapper mapper) {
            try { return mapper.readTree(bytes); }
            catch (java.io.IOException exception) { throw rejected(); }
        }
    }
}
