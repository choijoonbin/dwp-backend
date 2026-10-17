package com.dwp.services.auth.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.config.ProductSurfaceInternalSecurityConfig;
import com.dwp.services.auth.dto.HrisNavigationPolicyDecisionDtos;
import com.dwp.services.auth.service.HrisNavigationPolicyDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/hris-navigation-policy-decisions")
public class HrisNavigationPolicyDecisionController {

    private final HrisNavigationPolicyDecisionService service;

    public HrisNavigationPolicyDecisionController(HrisNavigationPolicyDecisionService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    @Operation(operationId = "evaluateHrisNavigationPolicyDecisionsInternal")
    public HrisNavigationPolicyDecisionDtos.BatchResult evaluate(
            @RequestAttribute(
                    name = ProductSurfaceInternalSecurityConfig
                            .AUTHENTICATED_SERVICE_IDENTITY_ATTRIBUTE,
                    required = false)
            String authenticatedServiceIdentity,
            @Valid @RequestBody HrisNavigationPolicyDecisionDtos.BatchRequest request) {
        if (!ProductSurfaceInternalSecurityConfig.PLATFORM_SERVICE_IDENTITY.equals(
                authenticatedServiceIdentity)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The HRIS navigation POLICY carrier accepts only the trusted Platform service.");
        }
        return service.evaluate(request);
    }
}
