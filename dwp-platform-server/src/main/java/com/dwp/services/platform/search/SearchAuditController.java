package com.dwp.services.platform.search;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/search/audit")
public class SearchAuditController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String ROLES = "X-DWP-Roles";
    private static final String CORRELATION = "X-Correlation-ID";

    private final SearchAuditService service;

    public SearchAuditController(SearchAuditService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<SearchAuditDtos.AuditReceipt> record(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody SearchAuditDtos.AuditRequest request) {
        return ApiResponse.success(service.record(
                tenantId, actorId, roles, correlationId, request));
    }
}
