package com.dwp.services.approval.signatureproviders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

final class SignatureProviderCanonical {
    private final ObjectMapper mapper;

    SignatureProviderCanonical(ObjectMapper configured) {
        mapper = configured.copy().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    String json(Object value) {
        try { return canonical(mapper.valueToTree(value)); }
        catch (RuntimeException failure) { throw SignatureProviderErrors.unavailable(); }
    }

    String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception malformed) { throw SignatureProviderErrors.unavailable(); }
    }

    private String canonical(JsonNode node) {
        if (node.isObject()) {
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            var parts = new ArrayList<String>();
            for (String name : names) parts.add(quote(name) + ":" + canonical(node.get(name)));
            return "{" + String.join(",", parts) + "}";
        }
        if (node.isArray()) {
            var parts = new ArrayList<String>();
            node.forEach(value -> parts.add(canonical(value)));
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return Long.toString(SignatureProviderModel.version(node.longValue()));
        }
        if (node.isTextual()) return quote(node.textValue());
        if (node.isBoolean() || node.isNull()) return node.toString();
        throw SignatureProviderModel.invalid("Canonical signature JSON permits exact integers only");
    }

    private String quote(String value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw SignatureProviderErrors.unavailable(); }
    }
}
