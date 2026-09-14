package com.dwp.services.approval.signatureproviders;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/** Vendor JSON is private observation data, never a current tenant authority or a signature-quality claim. */
final class SignatureProviderVendorJson {
    private final ObjectMapper mapper;
    SignatureProviderVendorJson(ObjectMapper configured) {
        mapper = configured.copy();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(16_384).maxNumberLength(64).build());
        mapper.getFactory().configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature(), true);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    JsonNode object(SignatureProviderHttpTransport.Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) throw new IOException("Provider observation was not successful");
        try {
            MediaType type = MediaType.parseMediaType(response.contentType());
            if (!MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))
                throw new IOException("Provider observation requires UTF-8 JSON");
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid provider observation media type", invalid); }
        byte[] raw = response.body(); SignatureProviderJson.requireUtf8(raw);
        JsonNode node = mapper.readTree(raw);
        if (node == null || !node.isObject()) throw new IOException("Provider observation object required");
        return node;
    }

    static String text(JsonNode object, String key, int max) throws IOException {
        JsonNode node = object.get(key);
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max
                || node.textValue().chars().anyMatch(value -> value < 32 || value == 127))
            throw new IOException("Invalid provider observation field");
        return node.textValue();
    }
}
