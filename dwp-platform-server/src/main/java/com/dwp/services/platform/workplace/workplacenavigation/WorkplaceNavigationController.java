package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

@RestController
@RequestMapping("/v1/workplace/navigation")
public class WorkplaceNavigationController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String VIEW = "APP.WORKPLACE:VIEW";

    private final WorkplaceNavigationService service;

    public WorkplaceNavigationController(WorkplaceNavigationService service) {
        this.service = service;
    }

    @GetMapping("/routes")
    public ApiResponse<RouteProjection> route(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam UUID siteId,
            @RequestParam UUID originPoiId,
            @RequestParam UUID destinationPoiId,
            @RequestParam(defaultValue = "false") boolean accessible,
            @RequestParam(defaultValue = "false") boolean avoidStairs,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.route(tenantId, siteId, originPoiId,
                destinationPoiId, accessible, avoidStairs, permissionSet(permissions)));
    }

    @GetMapping("/pois")
    public ApiResponse<List<PoiView>> pois(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam UUID siteId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.pois(tenantId, siteId));
    }

    static void requirePermission(String values, String expected) {
        if (!permissionSet(values).contains(expected)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The required Workplace navigation permission is missing.");
        }
    }

    static Set<String> permissionSet(String values) {
        if (values == null || values.isBlank()) return Set.of();
        return Arrays.stream(values.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
