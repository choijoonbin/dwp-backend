package com.dwp.services.provider.support;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/v1/internal/support-access")
public class ProviderSupportAccessController {

    public static final String VALIDATION_TOKEN_HEADER = "X-DWP-Support-Validation-Token";
    public static final String RESOURCE_METHOD_HEADER = "X-DWP-Support-Resource-Method";
    public static final String RESOURCE_PATH_HEADER = "X-DWP-Support-Resource-Path";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final ProviderSupportAccessService service;
    private final String validationToken;

    public ProviderSupportAccessController(
            ProviderSupportAccessService service,
            @Value("${dwp.provider.support-validation-token:}") String validationToken) {
        this.service = service;
        this.validationToken = validationToken == null ? "" : validationToken.trim();
    }

    @PostMapping("/resolve")
    public ApiResponse<ProviderDtos.SupportSessionContext> resolve(
            @RequestHeader(value = VALIDATION_TOKEN_HEADER, required = false) String providedToken,
            @RequestHeader(RESOURCE_METHOD_HEADER) String resourceMethod,
            @RequestHeader(RESOURCE_PATH_HEADER) String resourcePath,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @CookieValue(ProviderSupportCookie.NAME) String supportSessionToken) {
        if (validationToken.isBlank()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Support access validation is not configured.");
        }
        if (!constantTimeEquals(validationToken, providedToken)) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "Trusted support validation is required.");
        }
        return ApiResponse.success(service.resolve(
                supportSessionToken, resourceMethod, resourcePath, correlationId));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
