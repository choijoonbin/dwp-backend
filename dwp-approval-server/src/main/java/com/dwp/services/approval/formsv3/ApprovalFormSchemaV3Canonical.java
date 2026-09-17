package com.dwp.services.approval.formsv3;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ApprovalFormSchemaV3Canonical {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private ApprovalFormSchemaV3Canonical() { }

    static Frozen freeze(Map<String, ?> source) {
        Budget budget = new Budget();
        Map<String, Object> value = object(source, 0, budget);
        String json = json(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > 262_144) {
            throw invalid("Form Schema V3 exceeds the 256 KiB material limit.");
        }
        return new Frozen(value, json, sha256(json));
    }

    static Map<String, Object> copy(Map<String, ?> source) {
        return object(source, 0, new Budget());
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw invalid("Form Schema V3 cannot be represented as canonical JSON.");
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    static BigDecimal decimal(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw invalid("Expected a decimal string or a safe integer JSON number.");
        }
        String text = value.toString();
        if (text.length() > 40
                || value instanceof String && !text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
            throw invalid("Invalid decimal representation.");
        }
        try {
            BigDecimal result = new BigDecimal(text).stripTrailingZeros();
            if (result.precision() + Math.max(0, -result.scale()) > 28
                    || Math.max(0, result.scale()) > 8) {
                throw invalid("Decimal precision or scale exceeds the Form V3 limit.");
            }
            if (!(value instanceof String)
                    && (value instanceof Double || value instanceof Float || result.scale() > 0
                    || result.abs().compareTo(new BigDecimal("9007199254740991")) > 0)) {
                throw invalid("Non-integer decimals must use plain JSON strings.");
            }
            return result.scale() < 0 ? result.setScale(0) : result;
        } catch (NumberFormatException exception) {
            throw invalid("Invalid decimal representation.");
        }
    }

    private static Map<String, Object> object(Map<?, ?> source, int depth, Budget budget) {
        if (source == null) throw invalid("Form Schema V3 is missing.");
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid("Form Schema V3 object keys must be strings.");
            }
            if (result.put(key, value(entry.getValue(), depth + 1, budget)) != null) {
                throw invalid("Duplicate Form Schema V3 object key.");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object value(Object source, int depth, Budget budget) {
        if (depth > 32 || ++budget.nodes > 60_000) {
            throw invalid("Form Schema V3 nesting or node count exceeds the limit.");
        }
        if (source == null || source instanceof Boolean) return source;
        if (source instanceof String text) {
            budget.text += text.length();
            if (budget.text > 220_000) throw invalid("Form Schema V3 text exceeds the limit.");
            return text;
        }
        if (source instanceof Number) return decimal(source);
        if (source instanceof Map<?, ?> map) return object(map, depth, budget);
        if (source instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(value(item, depth + 1, budget));
            return Collections.unmodifiableList(result);
        }
        throw invalid("Unsupported Form Schema V3 JSON value.");
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    record Frozen(Map<String, Object> value, String json, String sha256) { }
    private static final class Budget { private int nodes; private long text; }
}
