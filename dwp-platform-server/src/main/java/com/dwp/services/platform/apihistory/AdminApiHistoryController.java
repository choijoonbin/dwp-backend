package com.dwp.services.platform.apihistory;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/api-history")
public class AdminApiHistoryController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";

    private final ApiHistoryService service;

    public AdminApiHistoryController(ApiHistoryService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<ApiHistoryDtos.Overview> overview(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(defaultValue = "H24") ApiHistoryWindow window,
            @RequestParam(defaultValue = "GATEWAY") String observationPoint,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String httpMethod,
            @RequestParam(defaultValue = "ALL") String outcome,
            @RequestParam(required = false) String query) {
        return ApiResponse.success(service.overview(criteria(
                tenantId, window, observationPoint, serviceName, httpMethod, outcome, query)));
    }

    @GetMapping("/events")
    public ApiResponse<ApiHistoryDtos.EventPage> events(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(defaultValue = "H24") ApiHistoryWindow window,
            @RequestParam(defaultValue = "GATEWAY") String observationPoint,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String httpMethod,
            @RequestParam(defaultValue = "ALL") String outcome,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(
                criteria(tenantId, window, observationPoint, serviceName, httpMethod, outcome, query),
                cursor,
                size));
    }

    @GetMapping("/events/{historyId}")
    public ApiResponse<ApiHistoryDtos.TraceDetail> detail(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @PathVariable UUID historyId) {
        return ApiResponse.success(service.detail(tenantId, historyId));
    }

    private ApiHistoryCriteria criteria(
            Long tenantId,
            ApiHistoryWindow window,
            String observationPoint,
            String serviceName,
            String httpMethod,
            String outcome,
            String query) {
        return ApiHistoryCriteria.of(
                tenantId,
                window,
                observationPoint,
                serviceName,
                httpMethod,
                outcome,
                query,
                Instant.now());
    }
}
