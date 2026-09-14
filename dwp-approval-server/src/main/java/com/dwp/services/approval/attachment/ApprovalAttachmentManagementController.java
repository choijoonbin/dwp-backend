package com.dwp.services.approval.attachment;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.dwp.services.approval.attachment.ApprovalAttachmentDtos.*;

@RestController
@RequestMapping("/v1/admin/attachments")
public class ApprovalAttachmentManagementController {
    private final ApprovalAttachmentManagementService management;
    public ApprovalAttachmentManagementController(ApprovalAttachmentManagementService management){this.management=management;}
    @GetMapping("/policy") public ApiResponse<Policy> policy(){return ApiResponse.success(management.policy());}
    @PostMapping("/policies") public ApiResponse<Policy> initialize(@Valid @RequestBody InitializePolicy input){return ApiResponse.success(management.initialize(input));}
    @PutMapping("/policies/{policyId}/draft") public ApiResponse<Policy> draft(@PathVariable UUID policyId,@Valid @RequestBody SavePolicy input){return ApiResponse.success(management.save(policyId,input));}
    @PostMapping("/policies/{policyId}/publish") public ApiResponse<Policy> publish(@PathVariable UUID policyId,@Valid @RequestBody PublishPolicy input,
            @RequestHeader(value="X-DWP-Step-Up-Challenge",required=false) String challenge,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestHeader(value="X-DWP-Expected-Decision-Revision",required=false) String revision,
            @RequestHeader(value="X-DWP-Expected-Object-Version",required=false) Long version){
        return ApiResponse.success(management.publish(policyId,input,ApprovalStepUpHeaders.of(challenge,key,revision,version)));
    }
}
