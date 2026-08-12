package com.dwp.services.platform.workspace;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/app-access-requests")
public class AdminAppAccessRequestController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String LOCALE = "Accept-Language";
    private static final String CORRELATION = "X-Correlation-ID";

    private final WorkspaceService service;

    public AdminAppAccessRequestController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceDtos.AppAccessRequest>> requests(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestParam(defaultValue = "ALL") String state) {
        return ApiResponse.success(service.appAccessRequests(tenantId, locale, state));
    }

    @PostMapping("/{requestId}/decision")
    public ApiResponse<WorkspaceDtos.AppAccessRequest> decide(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody WorkspaceDtos.AppAccessDecisionRequest request) {
        return ApiResponse.success(service.decideAppAccessRequest(
                tenantId, actorId, locale, correlationId, requestId, request));
    }
}
