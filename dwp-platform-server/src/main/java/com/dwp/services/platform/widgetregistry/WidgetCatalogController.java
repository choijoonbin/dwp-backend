package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/widget-catalog")
public class WidgetCatalogController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROLES = "X-DWP-Roles";
    private static final String GROUPS = "X-DWP-Group-Refs";
    private final WidgetCatalogService catalog;

    public WidgetCatalogController(WidgetCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/readiness")
    public ApiResponse<WidgetRegistryDtos.ReadinessResponse> readiness() {
        return ApiResponse.success(catalog.readiness());
    }

    @GetMapping("/effective")
    public ResponseEntity<ApiResponse<WidgetRegistryDtos.EffectiveCatalogResponse>> effective(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUPS, required = false) String groups,
            @RequestParam(defaultValue = "workspace-home")
            @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String surfaceKey,
            @RequestParam(required = false)
            @Pattern(regexp = "CLASSIC|FLOW_V1|MZ_V1") String mode) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.success(catalog.effective(
                        tenantId, surfaceKey, permissions, roles, groups, mode)));
    }
}
