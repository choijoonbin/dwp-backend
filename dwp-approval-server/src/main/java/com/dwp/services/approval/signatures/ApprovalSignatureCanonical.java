package com.dwp.services.approval.signatures;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ApprovalSignatureCanonical {
    private final ObjectMapper mapper;
    public ApprovalSignatureCanonical(ObjectMapper mapper) {
        this.mapper = mapper.copy().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(5242880).maxNumberLength(32).build());
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(sorted(mapper.convertValue(value, Object.class))); }
        catch (Exception error) { throw unavailable(); }
    }
    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception error) { throw unavailable(); }
    }
    public String digest(Object value) { return sha(json(value).getBytes(StandardCharsets.UTF_8)); }
    private Object sorted(Object value) {
        if (value instanceof Map<?,?> map) {
            var result = new TreeMap<String,Object>(); map.forEach((key,item) -> result.put(key.toString(), sorted(item))); return result;
        }
        if (value instanceof List<?> values) return values.stream().map(this::sorted).toList();
        return value;
    }
    public static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Internal self-attestation source or verification is unavailable."); }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Current self-attestation authority is required."); }
    public static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "Self-attestation source, consent, version or command changed."); }
    public static BaseException hidden() { return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, "Self-attestation is unavailable."); }
}
