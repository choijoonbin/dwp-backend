package com.dwp.services.approval.signatureproviders;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Private scoped codec: no legacy mapper coercion or global unknown-field behavior is changed. */
public final class SignatureProviderJson {
    public static final int MAX_BODY_BYTES = 524_288;
    static final Set<Class<?>> INPUTS = Set.of(
            ApprovalSignatureProviderDtos.ProbeInput.class, ApprovalSignatureProviderDtos.KmsProbeInput.class,
            ApprovalSignatureProviderDtos.WormInspectionInput.class,
            ApprovalSignatureProviderPolicyDtos.InitializeInput.class, ApprovalSignatureProviderPolicyDtos.DraftInput.class,
            ApprovalSignatureProviderPolicyDtos.PublishInput.class);
    private final ObjectMapper mapper;

    public SignatureProviderJson(ObjectMapper configured) {
        mapper = configured.copy();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(16_384).maxNumberLength(20).build());
        mapper.getFactory().configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature(), true);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        for (var type : Set.of(LogicalType.Boolean, LogicalType.Integer)) {
            mapper.coercionConfigFor(type).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        }
        mapper.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        var ids = new SimpleModule("approvalSignatureProviderCanonicalIds");
        ids.addDeserializer(UUID.class, new JsonDeserializer<UUID>() {
            @Override public UUID deserialize(JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) throw context.weirdStringException("", UUID.class, "Canonical UUID required");
                String raw = parser.getText(); UUID id;
                try { id = UUID.fromString(raw); } catch (IllegalArgumentException bad) { throw context.weirdStringException(raw, UUID.class, "Canonical UUID required"); }
                if (!id.toString().equals(raw)) throw context.weirdStringException(raw, UUID.class, "Canonical UUID required");
                return id;
            }
        });
        mapper.registerModule(ids);
    }

    public <T> T input(byte[] raw, Class<T> type) throws IOException {
        if (!INPUTS.contains(type)) throw SignatureProviderModel.invalid("Unregistered input type");
        requireUtf8(raw);
        return mapper.readValue(raw, type);
    }

    <T> T input(JsonParser parser, Class<T> type) throws IOException {
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode node = parser.getCodec().readTree(parser);
        if (node == null || !node.isObject()) throw new IOException("Closed input object required");
        if (parser.nextToken() != null) throw new IOException("Trailing input documents are forbidden");
        return input(mapper.writeValueAsBytes(node), type);
    }

    static void requireUtf8(byte[] raw) throws IOException {
        if (raw == null || raw.length == 0 || raw.length > MAX_BODY_BYTES) throw new IOException("Input size exceeds bounds");
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            if (decoded.indexOf('\0') >= 0 || decoded.startsWith("\ufeff")) throw new IOException("Strict UTF-8 without BOM required");
        } catch (java.nio.charset.CharacterCodingException malformed) { throw new IOException("Strict UTF-8 required", malformed); }
    }
}
