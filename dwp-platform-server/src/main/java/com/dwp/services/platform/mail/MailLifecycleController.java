package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
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
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailOrganizationDtos.LifecycleRequest request) {
        return ApiResponse.success(
                service.apply(tenantId, userId, threadId, correlationId, request));
    }
}
