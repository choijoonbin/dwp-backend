package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.config.ApprovalWorkflowRoleSecurityConfig;
import com.dwp.services.auth.service.ApprovalWorkflowRoleAttestationIssuer;
import com.dwp.services.auth.service.ApprovalWorkflowRoleBindingService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public final class ApprovalWorkflowRoleController {
    private final ApprovalWorkflowRoleBindingService service;
    public ApprovalWorkflowRoleController(ApprovalWorkflowRoleBindingService service) { this.service = service; }

    @PostMapping(value = ApprovalWorkflowRoleSecurityConfig.PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ApprovalWorkflowRoleAttestationIssuer.Attestation> bindings(
            @RequestHeader(ApprovalWorkflowRoleSecurityConfig.TOKEN_HEADER) String token, @RequestBody String rawBody) {
        return ApiResponse.success(service.bind(token, rawBody));
    }
}
