package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/mail/threads")
public class MailLifecycleController {

    private final MailLifecycleService service;

    public MailLifecycleController(MailLifecycleService service) {
        this.service = service;
    }

    @PostMapping("/{threadId}/lifecycle")
    public ApiResponse<MailOrganizationDtos.LifecycleResult> lifecycle(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailOrganizationDtos.LifecycleRequest request) {
        if (request.action() == MailOrganizationTypes.LifecycleAction.DELETE_FOREVER
                && !hasPermission(permissions, "APP.MAIL:DELETE")) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "APP.MAIL:DELETE is required for permanent Mail deletion.");
        }
        return ApiResponse.success(
                service.apply(tenantId, userId, threadId, correlationId, request));
    }

    @PostMapping("/{threadId}/lifecycle/preview")
    @Operation(operationId = "previewMailThreadLifecycle")
    public ApiResponse<MailOrganizationDtos.LifecyclePreview> preview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailOrganizationDtos.LifecycleRequest request) {
        return ApiResponse.success(service.preview(tenantId, userId, threadId, request));
    }

    private static boolean hasPermission(String values, String expected) {
        if (values == null || values.isBlank()) return false;
        return java.util.Arrays.stream(values.split("[,\\s]+"))
                .map(String::trim).anyMatch(expected::equalsIgnoreCase);
    }
}
