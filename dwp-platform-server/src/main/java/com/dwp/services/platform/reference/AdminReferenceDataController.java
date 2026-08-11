package com.dwp.services.platform.reference;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.audit.PlatformAuditService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/reference-sets")
public class AdminReferenceDataController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final ReferenceDataService service;
    private final PlatformAuditService auditService;

    public AdminReferenceDataController(
            ReferenceDataService service,
            PlatformAuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<ReferenceDataDtos.PageResult<ReferenceDataDtos.ReferenceSetSummary>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) ReferenceLifecycle lifecycle,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.listSets(tenantId, query, lifecycle, page, size));
    }

    @GetMapping("/{setKey}")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> detail(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @PathVariable String setKey) {
        return ApiResponse.success(service.getSet(tenantId, setKey));
    }

    @GetMapping("/{setKey}/audit-events")
    public ApiResponse<PlatformAuditService.AuditPage> activity(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @PathVariable String setKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(auditService.listReferenceSetActivity(
                tenantId, setKey, page, size));
    }

    @PostMapping
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> create(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ReferenceDataDtos.CreateSetRequest request) {
        return ApiResponse.success(service.createSet(
                tenantId, userId, correlationId, request));
    }

    @PatchMapping("/{setKey}")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> update(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String setKey,
            @Valid @RequestBody ReferenceDataDtos.UpdateSetRequest request) {
        return ApiResponse.success(service.updateSet(
                tenantId, userId, correlationId, setKey, request));
    }

    @PostMapping("/{setKey}/activate")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> activate(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String setKey,
            @Valid @RequestBody ReferenceDataDtos.VersionRequest request) {
        return ApiResponse.success(service.activateSet(
                tenantId, userId, correlationId, setKey, request.version()));
    }

    @PostMapping("/{setKey}/retire")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> retire(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String setKey,
            @Valid @RequestBody ReferenceDataDtos.VersionRequest request) {
        return ApiResponse.success(service.retireSet(
                tenantId, userId, correlationId, setKey, request.version()));
    }

    @PostMapping("/{setKey}/items")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> createItem(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String setKey,
            @Valid @RequestBody ReferenceDataDtos.CreateItemRequest request) {
        return ApiResponse.success(service.createItem(
                tenantId, userId, correlationId, setKey, request));
    }

    @PatchMapping("/{setKey}/items/{code}")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> updateItem(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String setKey,
            @PathVariable String code,
            @Valid @RequestBody ReferenceDataDtos.UpdateItemRequest request) {
        return ApiResponse.success(service.updateItem(
                tenantId, userId, correlationId, setKey, code, request));
    }

    @PostMapping("/{setKey}/items/{code}/activate")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> activateItem(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String setKey,
            @PathVariable String code,
            @Valid @RequestBody ReferenceDataDtos.VersionRequest request) {
        return ApiResponse.success(service.activateItem(
                tenantId, userId, correlationId, setKey, code, request.version()));
    }

    @PostMapping("/{setKey}/items/{code}/retire")
    public ApiResponse<ReferenceDataDtos.ReferenceSetDetail> retireItem(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String setKey,
            @PathVariable String code,
            @Valid @RequestBody ReferenceDataDtos.VersionRequest request) {
        return ApiResponse.success(service.retireItem(
                tenantId, userId, correlationId, setKey, code, request.version()));
    }
}
