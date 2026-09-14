package com.dwp.services.approval.signatures;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ApprovalSignatureCommandReceiptDtos {
    private ApprovalSignatureCommandReceiptDtos() { }
    public static final String ROUTE="route.approvals.work.signature-command-receipt.data";
    public static final String PROFILE="approval.signature.command-receipt.v1";
    public enum OriginalOperation { CREATE,CONSENT,SIGN,CANCEL }
    public record Query(String idempotencyKey,OriginalOperation originalOperation,UUID targetId,String bodySha256) {
        public Query {
            if(idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,120}") || idempotencyKey.equals(".") || idempotencyKey.equals("..")
                    || originalOperation==null || targetId==null || bodySha256==null || !bodySha256.matches("[a-f0-9]{64}")) throw ApprovalSignatureCanonical.denied();
        }
        public String path() { return "/v1/signature-command-receipts/"+idempotencyKey; }
        public Map<String,Object> body() { return Map.of("originalOperation",originalOperation.name(),"targetId",targetId.toString(),"bodySha256",bodySha256); }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="ApprovalSignatureCommandReceiptMetadata",additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record CommandReceipt(@io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UUID receiptId,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) OriginalOperation originalOperation,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UUID requestId,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UUID signatureRequestId,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) Instant committedAt,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) long eventSequence,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) ApprovalSignatureDtos.State resultState,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) long resultVersion,
            @io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) boolean sourceCurrent) { }
}
