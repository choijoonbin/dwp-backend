package com.dwp.services.platform.navigation;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/navigation")
public class RuntimeNavigationController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";

    private final NavigationService service;

    public RuntimeNavigationController(NavigationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<NavigationDtos.RuntimeNode>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(defaultValue = "en") String locale) {
        return ApiResponse.success(service.runtimeTree(tenantId, locale));
    }
}
