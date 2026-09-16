package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-side durable idempotency boundary, separate from the broker receipt. */
@Service
public class HomeOwnerActionReceiptService {

    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final HomeOwnerActionReceiptRepository receipts;
    private final HomeCanonicalJson canonicalJson;
    private final ObjectMapper objectMapper;

    public HomeOwnerActionReceiptService(
            HomeOwnerActionReceiptRepository receipts,
            HomeCanonicalJson canonicalJson,
            ObjectMapper objectMapper) {
        this.receipts = receipts;
        this.canonicalJson = canonicalJson;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public HomeWidgetProviderContract.CommandResponse execute(
            HomeRuntimeContext context,
            String contractId,
            HomeWidgetProviderContract.CommandRequest request,
            Supplier<HomeWidgetProviderContract.CommandResponse> ownerMutation) {
        String fingerprint = canonicalJson.fingerprint(Map.of(
                "authority", context.fingerprint(),
                "contractId", contractId,
                "request", request));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeOwnerActionReceipt existing = receipts.findByTenantIdAndUserIdAndCommandId(
                context.tenantId(), context.userId(), request.commandId()).orElse(null);
        if (existing != null) {
            if (existing.getExpiresAt() == null || !existing.getExpiresAt().isAfter(now)
                    || !contractId.equals(existing.getContractId())
                    || !fingerprint.equals(existing.getRequestFingerprint())
                    || !"COMPLETED".equals(existing.getReceiptState())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The owner idempotency key is expired, in flight, or bound to another command.");
            }
            try {
                return objectMapper.treeToValue(
                        existing.getResponsePayload(),
                        HomeWidgetProviderContract.CommandResponse.class);
            } catch (JsonProcessingException exception) {
                throw new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "The owner command receipt is invalid.",
                        exception);
            }
        }

        HomeOwnerActionReceipt claimed = HomeOwnerActionReceipt.builder()
                .receiptId(UUID.randomUUID())
                .tenantId(context.tenantId())
                .userId(context.userId())
                .commandId(request.commandId())
                .contractId(contractId)
                .requestFingerprint(fingerprint)
                .receiptState("PENDING")
                .responsePayload(objectMapper.createObjectNode())
                .createdAt(now)
                .expiresAt(now.plusHours(24))
                .build();
        try {
            receipts.saveAndFlush(claimed);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The owner idempotency key is already being processed.");
        }
        HomeWidgetProviderContract.CommandResponse response = ownerMutation.get();
        try {
            if (objectMapper.writeValueAsBytes(response).length > MAX_RESPONSE_BYTES) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The owner command receipt exceeds the bounded payload size.");
            }
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The owner command receipt cannot be serialized.",
                    exception);
        }
        claimed.setReceiptState("COMPLETED");
        claimed.setResponsePayload(objectMapper.valueToTree(response));
        receipts.saveAndFlush(claimed);
        return response;
    }
}
