package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Repository
public class NotificationIdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationIdempotencyRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Request begin(
            NotificationRequestContext.Actor actor,
            String idempotencyKey,
            String operation,
            Object requestBody) {
        validateKey(idempotencyKey);
        String normalizedKey = idempotencyKey.trim();
        String requestHash = sha256(canonicalJson(requestBody));
        String lockKey = actor.tenantId() + ":" + actor.userId() + ":" + normalizedKey;
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                new MapSqlParameterSource("lockKey", lockKey),
                resultSet -> null);
        MapSqlParameterSource params = params(actor, normalizedKey);
        jdbc.update("""
                DELETE FROM ntf_idempotency_receipts
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND idempotency_key = :idempotencyKey
                   AND expires_at <= CURRENT_TIMESTAMP
                """, params);
        List<StoredReceipt> rows = jdbc.query("""
                SELECT operation, request_hash, response_payload::text AS response_payload
                  FROM ntf_idempotency_receipts
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND idempotency_key = :idempotencyKey
                """, params, (resultSet, rowNumber) -> new StoredReceipt(
                resultSet.getString("operation"),
                resultSet.getString("request_hash"),
                resultSet.getString("response_payload")));
        if (rows.isEmpty()) {
            return new Request(normalizedKey, operation, requestHash, null);
        }
        StoredReceipt receipt = rows.get(0);
        if (!operation.equals(receipt.operation()) || !requestHash.equals(receipt.requestHash())) {
            throw new NotificationException(
                    NotificationErrorCode.NOTIFICATION_IDEMPOTENCY_CONFLICT);
        }
        return new Request(normalizedKey, operation, requestHash, receipt.responsePayload());
    }

    public void complete(
            NotificationRequestContext.Actor actor,
            Request request,
            Object responseBody) {
        int updated = jdbc.update("""
                INSERT INTO ntf_idempotency_receipts (
                    tenant_id, user_id, idempotency_key, operation,
                    request_hash, response_payload, expires_at)
                VALUES (
                    :tenantId, :userId, :idempotencyKey, :operation,
                    :requestHash, CAST(:responsePayload AS jsonb),
                    CURRENT_TIMESTAMP + INTERVAL '24 hours')
                ON CONFLICT (tenant_id, user_id, idempotency_key)
                DO UPDATE SET
                    operation = EXCLUDED.operation,
                    request_hash = EXCLUDED.request_hash,
                    response_payload = EXCLUDED.response_payload,
                    created_at = CURRENT_TIMESTAMP,
                    expires_at = EXCLUDED.expires_at
                WHERE ntf_idempotency_receipts.expires_at <= CURRENT_TIMESTAMP
                """, params(actor, request.idempotencyKey())
                .addValue("operation", request.operation())
                .addValue("requestHash", request.requestHash())
                .addValue("responsePayload", json(responseBody)));
        if (updated != 1) {
            throw new NotificationException(
                    NotificationErrorCode.NOTIFICATION_IDEMPOTENCY_CONFLICT);
        }
    }

    public <T> T replay(Request request, Class<T> responseType) {
        if (!request.replayed()) return null;
        try {
            return objectMapper.readValue(request.responsePayload(), responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid notification idempotency receipt.", exception);
        }
    }

    private MapSqlParameterSource params(
            NotificationRequestContext.Actor actor,
            String idempotencyKey) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("idempotencyKey", idempotencyKey);
    }

    private void validateKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) {
            throw new IllegalArgumentException("A valid Idempotency-Key is required.");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotent request.", exception);
        }
    }

    String canonicalJson(Object value) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            return objectMapper.writeValueAsString(canonicalize(tree));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to canonicalize idempotent request.", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        ObjectNode object = objectMapper.createObjectNode();
        Map<String, JsonNode> sorted = new TreeMap<>();
        node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        sorted.forEach((key, item) -> object.set(key, canonicalize(item)));
        return object;
    }

    public record Request(
            String idempotencyKey,
            String operation,
            String requestHash,
            String responsePayload) {

        public boolean replayed() {
            return responsePayload != null;
        }
    }

    private record StoredReceipt(
            String operation,
            String requestHash,
            String responsePayload) {
    }
}
