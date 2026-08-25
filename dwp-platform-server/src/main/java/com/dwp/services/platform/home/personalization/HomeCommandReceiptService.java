package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class HomeCommandReceiptService {
    private static final int MAX_RESPONSE_BYTES = 384 * 1024;
    private final HomeCommandReceiptRepository receipts;
    private final ObjectMapper objectMapper;
    private final int retentionHours;

    public HomeCommandReceiptService(
            HomeCommandReceiptRepository receipts,
            ObjectMapper objectMapper,
            @Value("${dwp.platform.home.personalization-maintenance.receipt-retention-hours:24}")
            int retentionHours) {
        this.receipts = receipts;
        this.objectMapper = objectMapper;
        this.retentionHours = Math.max(1, Math.min(retentionHours, 24 * 30));
    }

    public <T> T replay(
            Long tenantId,
            Long actorId,
            UUID commandId,
            String operation,
            String targetKey,
            String fingerprint,
            Class<T> responseType) {
        requireCommand(commandId);
        HomeCommandReceipt receipt = receipts
                .findByTenantIdAndActorIdAndCommandId(tenantId, actorId, commandId)
                .orElse(null);
        if (receipt == null) return null;
        // The persisted expiry is the single source of truth shared with cleanup. Never
        // silently execute an expired command again while its unique receipt is still retained.
        if (receipt.getExpiresAt() == null
                || !receipt.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency receipt expired; retry with a new Idempotency-Key.");
        }
        if (!operation.equals(receipt.getOperation())
                || !targetKey.equals(receipt.getTargetKey())
                || !fingerprint.equals(receipt.getRequestFingerprint())
                || !responseType.getName().equals(receipt.getResponseType())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        try {
            return objectMapper.treeToValue(receipt.getResponsePayload(), responseType);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored idempotency response is invalid.",
                    exception);
        }
    }

    public void record(
            Long tenantId,
            Long actorId,
            UUID commandId,
            String operation,
            String targetKey,
            String fingerprint,
            Object response) {
        requireCommand(commandId);
        byte[] serialized;
        try {
            serialized = objectMapper.writeValueAsBytes(response);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The idempotency response could not be serialized.",
                    exception);
        }
        if (serialized.length > MAX_RESPONSE_BYTES) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The idempotency response exceeds the bounded receipt size.");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            receipts.saveAndFlush(HomeCommandReceipt.builder()
                    .receiptId(UUID.randomUUID()).tenantId(tenantId).actorId(actorId)
                    .commandId(commandId).operation(operation).targetKey(targetKey)
                    .requestFingerprint(fingerprint).responseType(response.getClass().getName())
                    .responsePayload(objectMapper.valueToTree(response))
                    .createdAt(now).expiresAt(now.plusHours(retentionHours)).build());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private void requireCommand(UUID commandId) {
        if (commandId == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key is required.");
        }
    }
}
