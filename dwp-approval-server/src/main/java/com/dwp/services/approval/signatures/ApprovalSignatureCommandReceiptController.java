package com.dwp.services.approval.signatures;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/signature-command-receipts")
@ConditionalOnProperty(prefix="dwp.approval.internal-signatures",name="enabled",havingValue="true")
public class ApprovalSignatureCommandReceiptController {
    private final ApprovalSignatureCommandReceiptService service;
    public ApprovalSignatureCommandReceiptController(ApprovalSignatureCommandReceiptService service){this.service=service;}
    @GetMapping("/{idempotencyKey}")
    public ApiResponse<CommandReceipt> read(@PathVariable String idempotencyKey,@RequestParam OriginalOperation originalOperation,
            @RequestParam UUID targetId,@RequestParam String bodySha256){return ApiResponse.success(service.read(new Query(idempotencyKey,originalOperation,targetId,bodySha256)));}
}
