package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollPreparationCanonical {

    private final ObjectMapper mapper;
    private final byte[] cursorKey;
    private final Clock clock;

    PayrollPreparationCanonical(ObjectMapper mapper) {
        this(mapper, "", Clock.systemUTC());
    }

    @Autowired
    PayrollPreparationCanonical(
            ObjectMapper mapper,
            @Value("${dwp.features.HRIS_PAYROLL_RUN_PREPARE.cursor-secret:}")
            String cursorSecret) {
        this(mapper, cursorSecret, Clock.systemUTC());
    }

    PayrollPreparationCanonical(
            ObjectMapper mapper, String cursorSecret, Clock clock) {
        this.mapper = mapper;
        this.cursorKey = cursorSecret == null
                ? new byte[0] : cursorSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    String digest(Object value) {
        return sha256(bytes(value));
    }

    String requestDigest(PayrollPreparationModels.PrepareCommand command) {
        return digest(Map.of(
                "schema", "PayrollRunPrepareCommand.v1",
                "command", command));
    }

    String json(Object value) {
        return new String(bytes(value), StandardCharsets.UTF_8);
    }

    byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(canonical(mapper.valueToTree(value)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payroll preparation canonicalization failed",
                    exception);
        }
    }

    UUID deterministicUuid(String namespace, Object value) {
        byte[] digest;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(namespace.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            digest = sha.digest(bytes(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        long most = buffer.getLong();
        long least = buffer.getLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    String cursor(int offset, String bindingDigest, Instant asOf) {
        requireCursorAuthority();
        Instant expiresAt = clock.instant().plusSeconds(300);
        Map<String, Object> unsigned = Map.of(
                "schema", "PayrollPopulationCursor.v2",
                "offset", offset,
                "bindingDigest", bindingDigest,
                "asOf", asOf.toString(),
                "expiresAt", expiresAt.toString());
        Map<String, Object> envelope = new java.util.LinkedHashMap<>(unsigned);
        envelope.put("signature", HexFormat.of().formatHex(hmac(bytes(unsigned))));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(envelope));
    }

    CursorState cursorState(String cursor) {
        if (cursor == null) return null;
        requireCursorAuthority();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            if (decoded.length > 2048) throw new IllegalArgumentException();
            JsonNode value = mapper.readTree(decoded);
            if (!value.isObject()
                    || value.size() != 6
                    || !"PayrollPopulationCursor.v2".equals(
                    value.path("schema").asText())
                    || !value.path("bindingDigest").asText()
                    .matches("[0-9a-f]{64}")
                    || !value.path("offset").canConvertToInt()
                    || value.path("offset").asInt() < 0
                    || !value.path("signature").asText()
                    .matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            Instant asOf = Instant.parse(value.path("asOf").asText());
            Instant expiresAt = Instant.parse(value.path("expiresAt").asText());
            if (!expiresAt.isAfter(clock.instant())) throw new IllegalArgumentException();
            Map<String, Object> unsigned = Map.of(
                    "schema", "PayrollPopulationCursor.v2",
                    "offset", value.path("offset").asInt(),
                    "bindingDigest", value.path("bindingDigest").asText(),
                    "asOf", asOf.toString(),
                    "expiresAt", expiresAt.toString());
            byte[] expected = hmac(bytes(unsigned));
            byte[] actual = HexFormat.of().parseHex(
                    value.path("signature").asText());
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException();
            }
            return new CursorState(
                    value.path("offset").asInt(),
                    value.path("bindingDigest").asText(), asOf);
        } catch (RuntimeException | java.io.IOException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The Payroll population cursor is invalid or belongs to another query.");
        }
    }

    void requireCursorAuthority() {
        if (cursorKey.length < 32) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The Payroll population cursor authority is unavailable.");
        }
    }

    private byte[] hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable", exception);
        }
    }

    record CursorState(int offset, String bindingDigest, Instant asOf) {
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(name ->
                    result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
