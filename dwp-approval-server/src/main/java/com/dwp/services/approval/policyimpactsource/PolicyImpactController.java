package com.dwp.services.approval.policyimpactsource;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PolicyImpactController {
    private final PolicyImpactSourceService service;
    public PolicyImpactController(PolicyImpactSourceService service) { this.service = service; }
    @Operation(summary = "Preview the exact current policy proposal impact in the verified management scope")
    @GetMapping("/v1/admin/policies/{policyId}/impact")
    public ApiResponse<ApprovalPolicyImpactDtos.Result> impact(@PathVariable UUID policyId,
            @RequestParam long expectedVersion, HttpServletRequest request) {
        return ApiResponse.success(service.preview(request, policyId, expectedVersion));
    }
}
