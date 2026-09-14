package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Purpose-local strict JSON; object order is canonical, arrays retain their actual order. */
public final class RetentionExecutionJson {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public RetentionExecutionJson() {
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                .maxNumberLength(20).maxStringLength(65536).build());
    }
    public JsonNode parse(byte[] bytes,int limit) {
        if(bytes==null || bytes.length==0 || bytes.length>limit) throw denied();
        try {var value=mapper.readTree(bytes);validate(value,new int[]{0});return value;}
        catch(java.io.IOException | IllegalArgumentException invalid) {throw denied();}
    }
    private void validate(JsonNode value,int[] nodes) {
        if(value==null || ++nodes[0]>4096 || value.isFloatingPointNumber()
                || value.isIntegralNumber() && (!value.canConvertToLong() || value.longValue()<-9007199254740991L
                || value.longValue()>9007199254740991L)) throw denied();
        value.forEach(child->validate(child,nodes));
    }
    public JsonNode tree(Object value) {return mapper.valueToTree(value);}
    public byte[] bytes(Object value) {
        try {return mapper.writeValueAsBytes(sorted(tree(value)));}
        catch(java.io.IOException invalid) {throw unavailable();}
    }
    private JsonNode sorted(JsonNode value) {
        if(value.isObject()) {var result=mapper.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);
            for(String key:keys) result.set(key,sorted(value.get(key)));return result;}
        if(value.isArray()) {var result=mapper.createArrayNode();value.forEach(child->result.add(sorted(child)));return result;}
        if(value.isFloatingPointNumber()) throw denied();return value.deepCopy();
    }
    public String digest(Object value) {return sha(bytes(value));}
    public static void exact(JsonNode value,Set<String> fields) {
        if(value==null || !value.isObject()) throw denied();var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);
        if(!fields.equals(actual)) throw denied();
    }
    public static String text(JsonNode value,String field,int limit) {
        var node=value==null?null:value.get(field);
        if(node==null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length()>limit
                || node.textValue().codePoints().anyMatch(Character::isISOControl)) throw denied();return node.textValue();
    }
    public static long integer(JsonNode value,String field,long minimum) {
        var node=value==null?null:value.get(field);
        if(node==null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<minimum
                || node.longValue()>9007199254740991L) throw denied();return node.longValue();
    }
    public static UUID uuid(JsonNode value,String field) {
        String raw=text(value,field,36);
        if(!raw.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw denied();return UUID.fromString(raw);
    }
    public static String hash(JsonNode value,String field) {
        String raw=text(value,field,64);if(!raw.matches("[a-f0-9]{64}")) throw denied();return raw;
    }
    public static byte[] part(String raw) {
        if(raw==null || !raw.matches("[A-Za-z0-9_-]+")) throw denied();
        try {byte[] bytes=Base64.getUrlDecoder().decode(raw);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw denied();return bytes;}
        catch(IllegalArgumentException invalid) {throw denied();}
    }
    public static String sha(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException invalid) {throw new IllegalStateException(invalid);}
    }
    public static String sha(String text) {return sha(text.getBytes(StandardCharsets.UTF_8));}
}
