package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Independent bounded closed canonical metadata format; never parses owned PRIMARY evidence. */
public final class RuntimeMetadataCatalogReadEvidenceJsonV1 {
    public static final int MAX_DOCUMENT_BYTES = 65_536;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private RuntimeMetadataCatalogReadEvidenceJsonV1() { }

    public static RuntimeMetadataCatalogReadEvidenceV1 parse(String document) {
        require(document != null && !document.isEmpty() && document.length() <= MAX_DOCUMENT_BYTES
                && document.getBytes(StandardCharsets.UTF_8).length <= MAX_DOCUMENT_BYTES, "metadata document missing or oversized");
        try {
            var parsed = JSON.readValue(document, RuntimeMetadataCatalogReadEvidenceV1.class);
            require(parsed != null && canonical(parsed).equals(document), "metadata document not a byte canonical object");
            return parsed;
        } catch (Exception exception) { throw failure("strict metadata evidence parse rejected"); }
    }

    public static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw failure("metadata serialization rejected"); }
    }

    public static String evidenceSha256(RuntimeMetadataCatalogReadEvidenceV1 value) {
        require(value != null, "metadata evidence required");
        return sha256(RuntimeMetadataCatalogReadEvidenceV1.DOCUMENT_DOMAIN + canonical(value));
    }

    public static String historyPolicySha256(PrivilegeSurfaceCompilerV1.Policy externallyApprovedHistoryPolicy) {
        require(externallyApprovedHistoryPolicy != null, "metadata history policy required");
        return sha256("dwp-runtime-metadata-history-policy-v1\n" + canonical(externallyApprovedHistoryPolicy));
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw failure("metadata digest unavailable"); }
    }
}
