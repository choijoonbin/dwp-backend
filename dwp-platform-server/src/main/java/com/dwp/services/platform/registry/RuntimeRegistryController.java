package com.dwp.services.platform.registry;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/catalog/registry-entries")
public class RuntimeRegistryController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";

    private final RegistryService service;

    public RuntimeRegistryController(RegistryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<RegistryDtos.RuntimeRegistryEntry>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(required = false) RegistryType registryType) {
        return ApiResponse.success(service.listRuntime(tenantId, registryType));
    }

    @GetMapping("/{registryType}/{entryKey}")
    public ApiResponse<RegistryDtos.RuntimeRegistryEntry> detail(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @PathVariable RegistryType registryType,
            @PathVariable String entryKey) {
        return ApiResponse.success(service.getRuntime(tenantId, registryType, entryKey));
    }
}
