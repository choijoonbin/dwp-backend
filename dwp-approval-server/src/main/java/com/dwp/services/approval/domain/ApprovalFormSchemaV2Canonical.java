package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ApprovalFormSchemaV2Canonical {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private ApprovalFormSchemaV2Canonical() { }

    static String json(Map<String, Object> definition) {
        try {
            return MAPPER.writeValueAsString(definition);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid form schema JSON.");
        }
    }

    static String sha256(String canonicalJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    static Map<String, Object> freeze(Map<String, ?> value) {
        return object(value, 0, new Budget());
    }

    private static Map<String, Object> object(Map<?, ?> value, int depth, Budget budget) {
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) invalid();
            result.put((String) entry.getKey(), copy(entry.getValue(), depth + 1, budget));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object copy(Object value, int depth, Budget budget) {
        if (depth > 32 || ++budget.nodes > 50000) invalid();
        if (value instanceof String text) {
            budget.text += text.length();
            if (budget.text > 200000) invalid();
            return text;
        }
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) return object(map, depth, budget);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(copy(item, depth + 1, budget));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Number number) {
            try {
                BigDecimal decimal = transportDecimal(number);
                return decimal.scale() < 0 ? decimal.setScale(0) : decimal;
            } catch (NumberFormatException exception) { invalid(); }
        }
        invalid();
        return null;
    }

    private static void invalid() {
        throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported form JSON value.");
    }
    static BigDecimal decimal(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) throw numberInvalid();
        try {
            String text = value.toString();
            if (text.length() > 40 || value instanceof String && !text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
                throw numberInvalid();
            }
            BigDecimal result = new BigDecimal(text).stripTrailingZeros();
            if (result.precision() + Math.max(0, -result.scale()) > 28 || Math.max(0, result.scale()) > 8) {
                throw numberInvalid();
            }
            return result.scale() < 0 ? result.setScale(0) : result;
        } catch (NumberFormatException exception) { throw numberInvalid(); }
    }
    private static BaseException numberInvalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid decimal value or precision/scale limit.");
    }
    static BigDecimal transportDecimal(Object value) {
        BigDecimal decimal = decimal(value);
        if (value instanceof String) return decimal;
        if (value instanceof Double || value instanceof Float || decimal.scale() > 0
                || decimal.abs().compareTo(new BigDecimal("9007199254740991")) > 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Decimal values must be plain strings; only safe integer JSON numbers are accepted.");
        }
        return decimal;
    }
    private static final class Budget { private int nodes; private long text; }
}
