package com.dwp.services.auth.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.config.ProductSurfaceInternalSecurityConfig;
import com.dwp.services.auth.dto.Hrm012ReceiptFirstStepUpDtos;
import com.dwp.services.auth.service.Hrm012ReceiptFirstStepUpVerificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/hris/hrm012/receipt-first-step-up")
public final class Hrm012ReceiptFirstStepUpVerificationController {

    private final Hrm012ReceiptFirstStepUpVerificationService service;

    public Hrm012ReceiptFirstStepUpVerificationController(
            Hrm012ReceiptFirstStepUpVerificationService service) {
        this.service = service;
    }

    @PostMapping("/verify")
    public Hrm012ReceiptFirstStepUpDtos.VerifyResponse verify(
            @RequestAttribute(
                    name = ProductSurfaceInternalSecurityConfig
                            .AUTHENTICATED_SERVICE_IDENTITY_ATTRIBUTE,
                    required = false) String serviceIdentity,
            @Valid @RequestBody Hrm012ReceiptFirstStepUpDtos.VerifyRequest request) {
        if (!ProductSurfaceInternalSecurityConfig.PEOPLE_SERVICE_IDENTITY.equals(serviceIdentity)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Only the People owner may verify HRM-012 receipt-first authority.");
        }
        return service.verify(request);
    }
}
