package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Bounded strict record-order canonical JSON shared by the external issuer and runtime verifier. */
public final class RuntimeStartupSealJson {
    public static final int MAX_DOCUMENT_BYTES = 262_144;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private RuntimeStartupSealJson() { }
    public static RuntimeStreamStartupSeal seal(String document) {
        return parse(document, RuntimeStreamStartupSeal.class);
    }
    public static RuntimeStartupLease lease(String document) {
        return parse(document, RuntimeStartupLease.class);
    }
    public static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw failure("canonical serialization rejected"); }
    }
    public static String sealSha256(RuntimeStreamStartupSeal seal) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("dwp-runtime-stream-startup-seal-v1\n" + canonical(seal))
                        .getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw failure("seal digest unavailable"); }
    }
    private static <T> T parse(String document, Class<T> type) {
        require(document != null && !document.isEmpty() && document.length() <= MAX_DOCUMENT_BYTES
                && document.getBytes(StandardCharsets.UTF_8).length <= MAX_DOCUMENT_BYTES,
                "signed document missing or oversized");
        try {
            T parsed = JSON.readValue(document, type);
            require(canonical(parsed).equals(document), "signed document is not byte canonical");
            return parsed;
        } catch (Exception exception) { throw failure("strict signed document parse rejected"); }
    }
}
