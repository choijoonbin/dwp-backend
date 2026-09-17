package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class WidgetRegistryCommandReceiptService {
    private final WidgetCommandReceiptRepository receipts;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public WidgetRegistryCommandReceiptService(
            WidgetCommandReceiptRepository receipts, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.receipts = receipts;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    public String fingerprint(Object request) {
        try {
            return sha256(objectMapper.writeValueAsBytes(request));
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The command is not valid JSON.");
        }
    }

    public <T> T replay(
            Long actorId,
            UUID commandId,
            String operation,
            String targetKey,
            String fingerprint,
            Class<T> responseType) {
        // Every mutation enters here before locking its aggregate. Serializing the command key
        // makes same-key concurrent delivery replay one durable result instead of racing the
        // aggregate unique constraint. The lock is released automatically with the transaction.
        String lockKey = actorId + ":" + commandId;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { }, lockKey);
        WidgetCommandReceipt receipt = receipts.findByActorIdAndCommandId(actorId, commandId)
                .orElse(null);
        if (receipt == null) return null;
        if (!operation.equals(receipt.getOperation())
                || !targetKey.equals(receipt.getTargetKey())
                || !fingerprint.equals(receipt.getRequestFingerprint())
                || !responseType.getName().equals(receipt.getResponseType())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Idempotency-Key was already used for a different Widget Registry command.");
        }
        try {
            return objectMapper.treeToValue(receipt.getResponsePayload(), responseType);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Stored command receipt is invalid.");
        }
    }

    public void store(
            Long actorId,
            UUID commandId,
            String operation,
            String targetKey,
            String fingerprint,
            Object response) {
        store(actorId, commandId, operation, targetKey, fingerprint, response, null);
    }

    public void store(
            Long actorId,
            UUID commandId,
            String operation,
            String targetKey,
            String fingerprint,
            Object response,
            Object internalAuditRequest) {
        JsonNode payload = objectMapper.valueToTree(response);
        try {
            receipts.saveAndFlush(WidgetCommandReceipt.builder()
                    .receiptId(UUID.randomUUID())
                    .actorId(actorId)
                    .commandId(commandId)
                    .operation(operation)
                    .targetKey(targetKey)
                    .requestFingerprint(fingerprint)
                    .responseType(response.getClass().getName())
                    .responsePayload(payload)
                    .requestAuditPayload(internalAuditRequest == null
                            ? null : objectMapper.valueToTree(internalAuditRequest))
                    .build());
        } catch (DataIntegrityViolationException exception) {
            Object replayed = replay(
                    actorId, commandId, operation, targetKey, fingerprint, response.getClass());
            if (replayed == null) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "Concurrent command conflict.");
            }
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    static String fingerprintText(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
