package com.dwp.platform.contracts.hris.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * One cross-owner digest algorithm for the exact Home materialization wire.
 * Only the two circular reference fields are excluded; every other field,
 * including state, authority revisions, projections and failures, is sealed.
 */
public final class HrisHomeMaterializationDigestV1 {

    public static final String PLACEHOLDER_REFERENCE = "href_0000000000000000";
    public static final String PLACEHOLDER_DIGEST = "0".repeat(64);

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private HrisHomeMaterializationDigestV1() {
    }

    public static String digest(HrisHomeContributionMaterializationV1 materialization) {
        if (materialization == null) {
            throw new IllegalArgumentException("Home materialization is required.");
        }
        try {
            ObjectNode wire = CANONICAL.valueToTree(materialization);
            JsonNode reference = wire.remove("payloadRef");
            JsonNode digest = wire.remove("payloadDigest");
            if (reference == null || digest == null || wire.size() != 20) {
                throw new IllegalArgumentException(
                        "Home materialization digest requires the exact 22-field wire.");
            }
            byte[] bytes = CANONICAL.writeValueAsBytes(canonical(wire));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Home materialization canonical digest failed.", exception);
        }
    }

    public static boolean matches(
            HrisHomeContributionMaterializationV1 materialization,
            String expectedDigest) {
        if (expectedDigest == null || !expectedDigest.matches("^[0-9a-f]{64}$")) {
            return false;
        }
        return MessageDigest.isEqual(
                digest(materialization).getBytes(StandardCharsets.US_ASCII),
                expectedDigest.getBytes(StandardCharsets.US_ASCII));
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(
                    entry.getKey(), canonical(entry.getValue())));
            fields.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }
}
