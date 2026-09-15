package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.policy.ApprovalPolicyDraftDtos;
import com.dwp.services.approval.policy.ApprovalPolicyDraftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/admin/policies")
public class ApprovalPolicyDraftController {
    private final ApprovalPolicyDraftService service;

    public ApprovalPolicyDraftController(ApprovalPolicyDraftService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<ApprovalDtos.PolicySummary> create(
            @Valid @RequestBody ApprovalPolicyDraftDtos.Create request,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.create(request, idempotencyKey, correlationId));
    }
}
