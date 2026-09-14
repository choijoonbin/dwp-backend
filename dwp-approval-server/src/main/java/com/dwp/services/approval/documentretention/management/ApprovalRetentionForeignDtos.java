package com.dwp.services.approval.documentretention.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalRetentionForeignDtos {
    private ApprovalRetentionForeignDtos() {}
    @Schema(name="ApprovalRetentionForeignDeletionRequest", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record DeletionRequest(UUID deletionRequestId,long tenantId,UUID intentId,UUID requestId,
            String consumerService,int chunkIndex,int chunkCount,List<UUID> producerEventIds,
            String inventorySha256,String purpose) {
        public DeletionRequest {producerEventIds=List.copyOf(producerEventIds);}
    }
    @Schema(name="ApprovalRetentionSignedForeignAcknowledgement", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record SignedAck(String payloadBase64Url,String signatureBase64Url) {}
    @Schema(name="ApprovalRetentionForeignAcknowledgementClaims", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record AckClaims(UUID deletionRequestId,long tenantId,UUID intentId,String consumerService,
            String requestSha256,String consumerInventorySha256,String outcome,String issuer,String audience,
            String purpose,String keyId,UUID nonce,Instant issuedAt,Instant expiresAt) {
        @JsonAnySetter public void unknown(String key,JsonNode value) {throw new IllegalArgumentException("Unknown ACK field: "+key);}
    }
}
