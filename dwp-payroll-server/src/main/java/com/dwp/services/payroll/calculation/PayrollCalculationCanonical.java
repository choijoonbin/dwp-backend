package com.dwp.services.payroll.calculation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
final class PayrollCalculationCanonical {

    private final ObjectMapper mapper;

    PayrollCalculationCanonical(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String digest(Object value) {
        return sha256(bytes(value));
    }

    String requestDigest(
            PayrollCalculationModels.CommandType commandType,
            UUID runId,
            long expectedVersion,
            ActionCommand command) {
        return digest(Map.of(
                "schema", "PayrollActionCommand.v1",
                "commandType", commandType.name(),
                "runId", runId.toString(),
                "expectedVersion", expectedVersion,
                "body", command));
    }

    byte[] bytes(Object value) {
        try {
            JsonNode tree = canonical(mapper.valueToTree(value));
            return mapper.writeValueAsBytes(tree);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Canonical payroll JSON could not be written.", exception);
        }
    }

    String json(Object value) {
        return new String(bytes(value), StandardCharsets.UTF_8);
    }

    <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Persisted payroll canonical JSON is invalid.", exception);
        }
    }

    String auditPayload(long runRevision, String evidenceJson) {
        if (runRevision < 1) throw new IllegalArgumentException(
                "audit runRevision must be positive");
        try {
            JsonNode evidence = mapper.readTree(evidenceJson);
            if (evidence == null || !evidence.isObject()) {
                throw new IllegalArgumentException("audit evidence must be a JSON object");
            }
            return json(Map.of("runRevision", runRevision, "evidence", evidence));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audit evidence is not valid JSON", exception);
        }
    }

    String checkpointDigest(
            UUID runId,
            int attemptNo,
            int checkpointNo,
            int nextInputOrdinal,
            List<String> lineDigests) {
        return digest(Map.of(
                "schema", "PayrollWorkerCheckpoint.v1",
                "runId", runId,
                "attemptNo", attemptNo,
                "checkpointNo", checkpointNo,
                "nextInputOrdinal", nextInputOrdinal,
                "lineDigests", List.copyOf(lineDigests)));
    }

    UUID deterministicUuid(String namespace, Object material) {
        byte[] digest = sha256Bytes((namespace + "\n" + json(material))
                .getBytes(StandardCharsets.UTF_8));
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    String sha256(byte[] value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private byte[] sha256Bytes(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }
}
