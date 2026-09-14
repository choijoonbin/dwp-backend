package com.dwp.services.approval.document;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public final class ApprovalDocumentCanonical {
    private final ObjectMapper mapper;
    public ApprovalDocumentCanonical(ObjectMapper mapper) { this.mapper = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw unavailable("Document data could not be serialized."); }
    }
    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw unavailable("Stored document data is invalid."); }
    }
    public String fingerprint(Object value) { return sha(json(sorted(mapper.convertValue(value, Object.class)))); }
    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, item) -> result.put(key.toString(), sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
        return value;
    }
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN, "Current document authority or policy prohibits this operation."); }
    public static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "Document version, policy, or command changed."); }
    public static BaseException unavailable(String message) { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message); }
}
