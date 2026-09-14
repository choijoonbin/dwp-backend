package com.dwp.services.approval.documentretention.management;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.*;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.Record;

@RestController
@RequestMapping("/v1/admin/retention")
@Tag(name="Approval retention management")
public class ApprovalRetentionManagementController {
    private final ApprovalRetentionManagementService management;
    public ApprovalRetentionManagementController(ApprovalRetentionManagementService management) {this.management=management;}
    @GetMapping("/policy") public ApiResponse<Policy> policy() {return ApiResponse.success(management.policy());}
    @PostMapping("/policies") public ApiResponse<Policy> initialize(@Valid @RequestBody InitializePolicy input) {return ApiResponse.success(management.initialize(input));}
    @PutMapping("/policies/{policyId}/draft") public ApiResponse<Policy> draft(@PathVariable UUID policyId,@Valid @RequestBody SavePolicy input) {return ApiResponse.success(management.save(policyId,input));}
    @PostMapping("/policies/{policyId}/publish")
    public ApiResponse<Policy> publish(@PathVariable UUID policyId,@Valid @RequestBody PublishPolicy input,
            @RequestHeader(value="X-DWP-Step-Up-Challenge",required=false) String challenge,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="X-DWP-Expected-Decision-Revision",required=false) String revision,
            @RequestHeader(value="X-DWP-Expected-Object-Version",required=false) Long version) {
        return ApiResponse.success(management.publish(policyId,input,ApprovalStepUpHeaders.of(challenge,key,revision,version)));
    }
    @GetMapping("/records/{requestId}") public ApiResponse<Record> record(@PathVariable UUID requestId) {return ApiResponse.success(management.record(requestId));}
    @PostMapping("/records/{requestId}/claims")
    public ApiResponse<Claim> claim(@PathVariable UUID requestId,@Valid @RequestBody CreateClaim input,
            @RequestHeader(value="X-DWP-Step-Up-Challenge",required=false) String challenge,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="X-DWP-Expected-Decision-Revision",required=false) String revision,
            @RequestHeader(value="X-DWP-Expected-Object-Version",required=false) Long version) {
        return ApiResponse.success(management.claim(requestId,input,ApprovalStepUpHeaders.of(challenge,key,revision,version)));
    }
    @GetMapping("/claims/{claimId}") public ApiResponse<Claim> claim(@PathVariable UUID claimId) {return ApiResponse.success(management.claim(claimId));}
}
