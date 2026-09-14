package com.dwp.services.approval.document;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;

@RestController
@RequestMapping("/v1/admin/document-tools")
public class ApprovalDocumentManagementController {
    private final ApprovalDocumentManagementService management;
    public ApprovalDocumentManagementController(ApprovalDocumentManagementService management) { this.management = management; }
    @GetMapping("/policy") public ApiResponse<Policy> policy() { return ApiResponse.success(management.policy()); }
    @PutMapping("/policies/{policyId}/draft") public ApiResponse<Policy> draft(@PathVariable UUID policyId, @Valid @RequestBody SavePolicy input) { return ApiResponse.success(management.save(policyId, input)); }
    @PostMapping("/policies/{policyId}/publish")
    public ApiResponse<Policy> publish(@PathVariable UUID policyId, @Valid @RequestBody PublishPolicy input,
            @RequestHeader(value="X-DWP-Step-Up-Challenge", required=false) String challenge,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @RequestHeader(value="X-DWP-Expected-Decision-Revision", required=false) String revision,
            @RequestHeader(value="X-DWP-Expected-Object-Version", required=false) Long version) {
        return ApiResponse.success(management.publish(policyId, input, ApprovalStepUpHeaders.of(challenge, key, revision, version)));
    }
    @GetMapping("/holds/{requestId}") public ApiResponse<Hold> hold(@PathVariable UUID requestId) { return ApiResponse.success(management.hold(requestId)); }
    @PostMapping("/holds/{requestId}/proposals")
    public ApiResponse<Hold> propose(@PathVariable UUID requestId, @Valid @RequestBody HoldProposal input) { return ApiResponse.success(management.propose(requestId, input)); }
    @PostMapping("/holds/{requestId}/publish")
    public ApiResponse<Hold> publishHold(@PathVariable UUID requestId, @Valid @RequestBody PublishHold input,
            @RequestHeader(value="X-DWP-Step-Up-Challenge", required=false) String challenge,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @RequestHeader(value="X-DWP-Expected-Decision-Revision", required=false) String revision,
            @RequestHeader(value="X-DWP-Expected-Object-Version", required=false) Long version) {
        return ApiResponse.success(management.publishHold(requestId, input, ApprovalStepUpHeaders.of(challenge, key, revision, version)));
    }
}
