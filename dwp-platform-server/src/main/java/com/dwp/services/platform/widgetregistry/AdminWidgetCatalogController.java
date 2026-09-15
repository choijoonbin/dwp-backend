package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/admin")
public class AdminWidgetCatalogController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROLES = "X-DWP-Roles";
    private static final String GROUPS = "X-DWP-Group-Refs";
    private final WidgetCatalogService catalog;
    private final WidgetRegistryAuditService audit;

    public AdminWidgetCatalogController(
            WidgetCatalogService catalog, WidgetRegistryAuditService audit) {
        this.catalog = catalog;
        this.audit = audit;
    }

    @GetMapping("/widget-registry/readiness")
    public ApiResponse<WidgetRegistryDtos.ReadinessResponse> readiness() {
        return ApiResponse.success(catalog.readiness());
    }

    @GetMapping("/widget-catalog")
    public ApiResponse<WidgetRegistryDtos.EffectiveCatalogResponse> catalog(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUPS, required = false) String groups,
            @RequestParam(defaultValue = "workspace-home")
            @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String surfaceKey) {
        return ApiResponse.success(catalog.effective(
                tenantId, surfaceKey, permissions, roles, groups));
    }

    @GetMapping("/widget-catalog/{definitionId}/explain")
    public ApiResponse<WidgetRegistryDtos.EffectiveCatalogResponse> explain(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUPS, required = false) String groups,
            @PathVariable UUID definitionId,
            @RequestParam(defaultValue = "workspace-home")
            @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String surfaceKey) {
        WidgetRegistryDtos.EffectiveCatalogResponse response = catalog.effective(
                tenantId, surfaceKey, permissions, roles, groups);
        boolean present = response.contexts().stream().flatMap(value -> value.items().stream())
                .anyMatch(value -> definitionId.equals(value.definitionId()));
        if (!present) throw new com.dwp.core.exception.BaseException(com.dwp.core.common.ErrorCode.NOT_FOUND);
        return ApiResponse.success(response);
    }

    @GetMapping("/widget-registry/events")
    public ApiResponse<WidgetRegistryDtos.RegistryEventPage> events(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(audit.list(page, size));
    }
}
