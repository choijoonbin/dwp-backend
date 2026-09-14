package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionErrors;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile.Operation;

@RestController
@RequestMapping("/v1/admin/retention")
@Tag(name="Approval retention command reconciliation")
public class ApprovalRetentionReceiptController {
    private final ApprovalRetentionReceiptService service;
    public ApprovalRetentionReceiptController(ApprovalRetentionReceiptService service) {this.service=service;}
    @GetMapping("/policy-initialization-commands/{idempotencyKey}")
    public ApiResponse<ApprovalRetentionCommandReceipt> initialization(@PathVariable String idempotencyKey,HttpServletRequest request) {
        return read(Operation.INITIALIZE_POLICY,null,idempotencyKey,"/v1/admin/retention/policy-initialization-commands/",request);
    }
    @GetMapping("/policies/{policyId}/draft-commands/{idempotencyKey}")
    public ApiResponse<ApprovalRetentionCommandReceipt> draft(@PathVariable UUID policyId,@PathVariable String idempotencyKey,HttpServletRequest request) {
        return read(Operation.SAVE_POLICY,policyId,idempotencyKey,"/v1/admin/retention/policies/"+policyId+"/draft-commands/",request);
    }
    @GetMapping("/policies/{policyId}/publication-commands/{idempotencyKey}")
    public ApiResponse<ApprovalRetentionCommandReceipt> publication(@PathVariable UUID policyId,@PathVariable String idempotencyKey,HttpServletRequest request) {
        return read(Operation.PUBLISH_POLICY,policyId,idempotencyKey,"/v1/admin/retention/policies/"+policyId+"/publication-commands/",request);
    }
    @GetMapping("/records/{requestId}/claim-commands/{idempotencyKey}")
    public ApiResponse<ApprovalRetentionCommandReceipt> claim(@PathVariable UUID requestId,@PathVariable String idempotencyKey,HttpServletRequest request) {
        return read(Operation.CLAIM_RECORD,requestId,idempotencyKey,"/v1/admin/retention/records/"+requestId+"/claim-commands/",request);
    }
    private ApiResponse<ApprovalRetentionCommandReceipt> read(Operation operation,UUID target,String key,String prefix,HttpServletRequest request) {
        ApprovalRetentionReceiptService.key(key);
        if(!(prefix+key).equals(request.getRequestURI()) || request.getQueryString()!=null) throw ApprovalRetentionErrors.invalid();
        return ApiResponse.success(service.read(operation,target,key));
    }
}
