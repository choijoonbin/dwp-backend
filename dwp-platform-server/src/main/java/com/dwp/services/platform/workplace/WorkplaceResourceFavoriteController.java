package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

import static com.dwp.services.platform.workplace.WorkplaceResourceFavoriteDtos.*;

@RestController
@RequestMapping("/v1/workplace")
public class WorkplaceResourceFavoriteController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String VIEW = "APP.WORKPLACE:VIEW";
    static final String UPDATE = "APP.WORKPLACE:UPDATE";

    private final WorkplaceResourceFavoriteService service;

    public WorkplaceResourceFavoriteController(WorkplaceResourceFavoriteService service) {
        this.service = service;
    }

    @GetMapping("/resource-favorites")
    public ApiResponse<List<FavoriteView>> favorites(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) List<UUID> resourceIds,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        return ApiResponse.success(service.favorites(tenantId, userId, resourceIds));
    }

    @PutMapping("/resources/{resourceId}/favorite")
    public ApiResponse<FavoriteReceipt> set(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody SetFavoriteRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, UPDATE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        return ApiResponse.success(service.set(tenantId, userId, resourceId,
                idempotencyKey, correlationId, request));
    }

    static void requirePermission(String values, String expected) {
        Set<String> granted = values == null ? Set.of() : Arrays.stream(values.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!granted.contains(expected)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The required Workplace permission is missing.");
        }
    }
}
