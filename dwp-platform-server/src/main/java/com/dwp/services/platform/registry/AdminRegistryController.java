package com.dwp.services.platform.registry;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.reference.ReferenceLifecycle;
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
@RequestMapping("/v1/admin/registry-entries")
public class AdminRegistryController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final RegistryService service;

    public AdminRegistryController(RegistryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<RegistryDtos.PageResult<RegistryDtos.RegistryEntryResponse>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(required = false) RegistryType registryType,
            @RequestParam(required = false) ReferenceLifecycle lifecycle,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(
                tenantId, registryType, lifecycle, query, page, size));
    }

    @GetMapping("/{registryType}/{entryKey}")
    public ApiResponse<RegistryDtos.RegistryEntryDetail> detail(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @PathVariable RegistryType registryType,
            @PathVariable String entryKey) {
        return ApiResponse.success(service.get(tenantId, registryType, entryKey));
    }

    @PostMapping
    public ApiResponse<RegistryDtos.RegistryEntryResponse> create(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody RegistryDtos.CreateRegistryEntryRequest request) {
        return ApiResponse.success(service.create(tenantId, userId, correlationId, request));
    }

    @PostMapping("/{registryType}/{entryKey}/revisions")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> createRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable RegistryType registryType,
            @PathVariable String entryKey,
            @Valid @RequestBody RegistryDtos.CreateRegistryRevisionRequest request) {
        return ApiResponse.success(service.createRevision(
                tenantId, userId, correlationId, registryType, entryKey, request));
    }

    @PatchMapping("/{registryType}/{entryKey}/revisions/{revision}")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> updateRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable RegistryType registryType,
            @PathVariable String entryKey,
            @PathVariable Integer revision,
            @Valid @RequestBody RegistryDtos.UpdateRegistryRevisionRequest request) {
        return ApiResponse.success(service.updateRevision(
                tenantId, userId, correlationId, registryType, entryKey, revision, request));
    }

    @PostMapping("/{registryType}/{entryKey}/revisions/{revision}/activate")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> activateRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable RegistryType registryType,
            @PathVariable String entryKey,
            @PathVariable Integer revision,
            @Valid @RequestBody RegistryDtos.VersionRequest request) {
        return ApiResponse.success(service.activateRevision(
                tenantId,
                userId,
                correlationId,
                registryType,
                entryKey,
                revision,
                request.version()));
    }

    @PostMapping("/{registryType}/{entryKey}/revisions/{revision}/retire")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> retireRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable RegistryType registryType,
            @PathVariable String entryKey,
            @PathVariable Integer revision,
            @Valid @RequestBody RegistryDtos.VersionRequest request) {
        return ApiResponse.success(service.retireRevision(
                tenantId,
                userId,
                correlationId,
                registryType,
                entryKey,
                revision,
                request.version()));
    }
}

