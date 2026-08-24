package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public final class ProductSurfaceStepUpRequestParser {

    private static final int MAXIMUM_BYTES = 16_384;
    private static final int MAXIMUM_DEPTH = 16;
    private static final int MAXIMUM_KEYS = 128;
    private static final Set<String> FIELDS = Set.of(
            "commandMethod", "commandPath", "targetType", "targetId",
            "expectedObjectVersion", "idempotencyKey", "payload",
            "contextKey", "contextScopeKey", "providerKey", "returnTo");
    private final ObjectMapper objectMapper;

    public ProductSurfaceStepUpRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedRequest parse(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
            throw invalid();
        }
        try {
            JsonNode root = objectMapper.readerFor(JsonNode.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (!(root instanceof ObjectNode object)
                    || !FIELDS.containsAll(fields(object))
                    || countKeys(root, 1) > MAXIMUM_KEYS) {
                throw invalid();
            }
            ProductSurfaceStepUpDtos.IssueRequest request =
                    objectMapper.treeToValue(root, ProductSurfaceStepUpDtos.IssueRequest.class);
            JsonNode payload = request.payload();
            if (!(payload instanceof ObjectNode)
                    || depth(payload, 1) > MAXIMUM_DEPTH
                    || containsNonFinite(payload)) {
                throw invalid();
            }
            byte[] canonicalPayload = objectMapper.writeValueAsBytes(canonical(payload));
            return new ParsedRequest(request, canonicalPayload);
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private Set<String> fields(ObjectNode value) {
        return value.propertyStream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private int depth(JsonNode value, int current) {
        if (!value.isContainerNode()) return current;
        int maximum = current;
        for (JsonNode child : value) maximum = Math.max(maximum, depth(child, current + 1));
        return maximum;
    }

    private int countKeys(JsonNode value, int count) {
        if (value.isObject()) {
            int total = count + value.size();
            for (JsonNode child : value) total = countKeys(child, total);
            return total;
        }
        if (value.isArray()) {
            int total = count;
            for (JsonNode child : value) total = countKeys(child, total);
            return total;
        }
        return count;
    }

    private boolean containsNonFinite(JsonNode value) {
        if (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) return true;
        for (JsonNode child : value) if (containsNonFinite(child)) return true;
        return false;
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_FORMAT, "Invalid step-up command payload.");
    }

    public record ParsedRequest(
            ProductSurfaceStepUpDtos.IssueRequest request,
            byte[] canonicalPayload) {
    }
}
