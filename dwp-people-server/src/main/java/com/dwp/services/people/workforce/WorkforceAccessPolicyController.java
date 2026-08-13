package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/workforce/access-policies")
public class WorkforceAccessPolicyController {

    private final WorkforceAccessPolicyService service;

    public WorkforceAccessPolicyController(WorkforceAccessPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<WorkforceAccessDtos.Policy>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/organizations")
    public ApiResponse<List<WorkforceAccessDtos.OrganizationOption>> organizations() {
        return ApiResponse.success(service.organizations());
    }

    @PostMapping
    public ApiResponse<WorkforceAccessDtos.Policy> create(
            @Valid @RequestBody WorkforceAccessDtos.CreatePolicyRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.create(request, correlationId));
    }

    @PatchMapping("/{policyId}/revoke")
    public ApiResponse<WorkforceAccessDtos.Policy> revoke(
            @PathVariable UUID policyId,
            @Valid @RequestBody WorkforceAccessDtos.RevokePolicyRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.revoke(policyId, request, correlationId));
    }
}
