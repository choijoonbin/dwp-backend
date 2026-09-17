package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceFulfillmentController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace/service-catalog")
public class WorkplaceServiceCatalogAdminController {
    private final WorkplaceServicesService service;

    public WorkplaceServiceCatalogAdminController(WorkplaceServicesService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ServiceCatalogAdminItems> items(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.adminCatalog(tenantId));
    }

    @GetMapping("/{itemId}")
    public ApiResponse<ServiceCatalogAdminItem> item(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID itemId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.adminCatalogItem(tenantId, itemId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CatalogCommandResult>> create(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody CatalogCreateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        return accepted(service.createCatalogItem(
                tenantId, actorUserId, idempotencyKey, request, correlationId));
    }

    @PutMapping("/{itemId}")
    public ResponseEntity<ApiResponse<CatalogCommandResult>> update(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID itemId,
            @Valid @RequestBody CatalogUpdateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        return accepted(service.updateCatalogItem(
                tenantId, actorUserId, itemId, idempotencyKey, request, correlationId));
    }

    @PostMapping("/{itemId}:state")
    public ResponseEntity<ApiResponse<CatalogCommandResult>> state(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID itemId,
            @Valid @RequestBody CatalogStateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        return accepted(service.changeCatalogState(
                tenantId, actorUserId, itemId, idempotencyKey, request, correlationId));
    }

    private static ResponseEntity<ApiResponse<CatalogCommandResult>> accepted(
            CatalogCommandResult result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }
}
