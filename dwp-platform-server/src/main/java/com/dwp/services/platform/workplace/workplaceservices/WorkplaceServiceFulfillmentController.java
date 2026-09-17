package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesController.*;

@RestController
@RequestMapping("/v1/admin/workplace/service-orders")
public class WorkplaceServiceFulfillmentController {
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String ADMIN_VIEW = "ADMIN.WORKPLACE:VIEW";
    static final String ADMIN_MANAGE = "ADMIN.WORKPLACE:MANAGE";

    private final WorkplaceServicesService service;
    private final WorkplaceServiceOrderQueryService queries;
    private final WorkplaceServiceLineAdjustmentService lineAdjustments;

    public WorkplaceServiceFulfillmentController(
            WorkplaceServicesService service,
            WorkplaceServiceOrderQueryService queries,
            WorkplaceServiceLineAdjustmentService lineAdjustments) {
        this.service = service;
        this.queries = queries;
        this.lineAdjustments = lineAdjustments;
    }

    @GetMapping
    public ApiResponse<ServiceOrders> orders(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) OrderState state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(queries.adminOrders(tenantId, state, cursor, limit));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<ServiceOrder> order(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.adminOrder(tenantId, orderId));
    }

    @PostMapping("/{orderId}/tasks/{taskId}:update")
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> update(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID taskId,
            @Valid @RequestBody FulfillmentUpdateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        ServiceOrderCommandResult result = service.updateFulfillment(
                tenantId, actorUserId, orderId, taskId, idempotencyKey, request, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/{orderId}/line-adjustments/{adjustmentId}")
    public ApiResponse<LineAdjustment> lineAdjustment(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @PathVariable UUID adjustmentId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(lineAdjustments.adminStatus(
                tenantId, orderId, adjustmentId));
    }

    @PostMapping("/{orderId}/line-adjustments/{adjustmentId}:reconcile")
    public ResponseEntity<ApiResponse<LineAdjustmentCommandResult>> reconcileLineAdjustment(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID adjustmentId,
            @Valid @RequestBody LineAdjustmentReconcileRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        LineAdjustmentCommandResult result = lineAdjustments.reconcile(
                tenantId, actorUserId, orderId, adjustmentId,
                idempotencyKey, request, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    static void requireElevated(String value) {
        if (!"ELEVATED".equals(value)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Service fulfillment updates require current elevated access.");
        }
    }
}
