package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalFormMaterialCodec {
    private final ObjectMapper mapper;
    private final ApprovalFormLegacySchemaValidator legacy;
    @Autowired public ApprovalFormMaterialCodec(ObjectMapper mapper, ObjectProvider<ApprovalFormLegacySchemaValidator> legacy) {
        this(mapper, legacy.getIfAvailable());
    }
    public ApprovalFormMaterialCodec(ObjectMapper mapper, ApprovalFormLegacySchemaValidator legacy) {
        this.mapper=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        this.legacy=legacy;
    }
    public String json(Object value) {
        try {
            String raw=mapper.writeValueAsString(value);
            if (raw.getBytes(StandardCharsets.UTF_8).length>262144) throw invalid();
            return raw;
        } catch (BaseException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }
    public Map<String,Object> object(String raw) {
        if (raw==null || raw.getBytes(StandardCharsets.UTF_8).length>262144) throw invalid();
        try { return mapper.readValue(raw,new TypeReference<>() { }); }
        catch (Exception exception) { throw invalid(); }
    }
    public Map<String,Object> map(Object raw) { return object(json(raw)); }
    public <T> T project(Object raw,Class<T> type) {
        try { return mapper.convertValue(raw,type); } catch(Exception exception) { throw invalid(); }
    }
    public String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw invalid(); }
    }
    public String material(String schemaHash, Map<String,Object> metadata, Map<String,Object> route) {
        return sha(json(Map.of("schemaSha256",schemaHash,"metadata",metadata,"route",route)));
    }
    public Schema authoring(Map<String,Object> value) {
        if (value==null) throw invalid();
        if (value.containsKey("schemaContract")) {
            if (!ApprovalFormSchemaV2.CONTRACT.equals(value.get("schemaContract"))) throw invalid();
            var compiled=new ApprovalFormSchemaV2Compiler().compile(value);
            var fields=(List<?>)compiled.definition().get("fields");
            boolean summary=fields.stream().filter(Map.class::isInstance).map(Map.class::cast).anyMatch(field ->
                    "summary".equals(field.get("key")) && List.of("TEXT","TEXTAREA").contains(field.get("type"))
                            && !field.containsKey("visibleWhen"));
            if (!summary) throw invalid();
            return new Schema(compiled.canonicalJson(),compiled.sha256());
        }
        legacyMarker(value);
        if (legacy==null) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"The original legacy form validator is not wired.");
        String raw=json(legacy.validate(value));
        return new Schema(raw,sha(raw));
    }
    public void stored(String raw,String hash) {
        if (hash==null || !hash.matches("[a-f0-9]{64}")) throw invalid();
        var value=object(raw);
        if (value.containsKey("schemaContract")) {
            var compiled=authoring(value);
            if (!hash.equals(compiled.sha256())) throw invalid();
        } else legacyMarker(value);
    }
    private void legacyMarker(Map<String,Object> value) {
        Object revision=value.get("schemaVersion");
        if (!(Integer.valueOf(1).equals(revision)||Integer.valueOf(2).equals(revision))) throw invalid();
    }
    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE,"The form material is invalid or exceeds its bound."); }
    public record Schema(String json,String sha256) { }
}
