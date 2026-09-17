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
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;

@RestController
@RequestMapping("/v1/workplace")
public class WorkplaceServicesController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String VIEW = "APP.WORKPLACE:VIEW";
    static final String UPDATE = "APP.WORKPLACE:UPDATE";

    private final WorkplaceServicesService service;
    private final WorkplaceServiceOrderQueryService queries;
    private final WorkplaceServiceLineAdjustmentService lineAdjustments;

    public WorkplaceServicesController(
            WorkplaceServicesService service,
            WorkplaceServiceOrderQueryService queries,
            WorkplaceServiceLineAdjustmentService lineAdjustments) {
        this.service = service;
        this.queries = queries;
        this.lineAdjustments = lineAdjustments;
    }

    @GetMapping("/service-catalog")
    public ApiResponse<ServiceCatalog> catalog(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam ReservationAuthority reservationAuthority,
            @RequestParam UUID reservationId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.catalog(
                tenantId, actorUserId, reservationAuthority, reservationId));
    }

    @PostMapping("/reservations/{reservationId}/service-orders:preview")
    public ApiResponse<ServiceOrderPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID reservationId,
            @Valid @RequestBody PreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, UPDATE);
        noStore(response);
        return ApiResponse.success(service.preview(tenantId, actorUserId, reservationId, request));
    }

    @PostMapping("/reservations/{reservationId}/service-orders")
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> submit(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID reservationId,
            @Valid @RequestBody SubmitRequest request) {
        requirePermission(permissions, UPDATE);
        ServiceOrderCommandResult result = service.submit(
                tenantId, actorUserId, reservationId, idempotencyKey, request, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/service-orders")
    public ApiResponse<ServiceOrders> orders(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(queries.ownOrders(
                tenantId, actorUserId, cursor, limit));
    }

    @GetMapping("/service-orders/{orderId}")
    public ApiResponse<ServiceOrder> order(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.ownOrder(tenantId, actorUserId, orderId));
    }

    @PostMapping("/service-orders/{orderId}:cancel")
    public ResponseEntity<ApiResponse<ServiceOrderCommandResult>> cancel(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody CancelRequest request) {
        requirePermission(permissions, UPDATE);
        ServiceOrderCommandResult result = service.cancel(
                tenantId, actorUserId, orderId, idempotencyKey, request, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @PostMapping("/service-orders/{orderId}/lines/{lineId}/cancellation-impact:preview")
    public ApiResponse<LineCancellationImpact> previewLineCancellation(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody LineCancellationImpactRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, UPDATE);
        noStore(response);
        return ApiResponse.success(lineAdjustments.preview(
                tenantId, actorUserId, orderId, lineId, request));
    }

    @PostMapping("/service-orders/{orderId}/lines/{lineId}:cancel")
    public ResponseEntity<ApiResponse<LineAdjustmentCommandResult>> cancelLine(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody LineCancellationRequest request) {
        requirePermission(permissions, UPDATE);
        LineAdjustmentCommandResult result = lineAdjustments.cancel(
                tenantId, actorUserId, orderId, lineId, idempotencyKey, request, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/service-orders/{orderId}/line-adjustments/{adjustmentId}")
    public ApiResponse<LineAdjustment> lineAdjustment(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @PathVariable UUID adjustmentId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(lineAdjustments.status(
                tenantId, actorUserId, orderId, adjustmentId));
    }

    static void requirePermission(String values, String expected) {
        boolean present = values != null && Arrays.stream(values.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
        if (!present) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The required Workplace Services permission is missing.");
        }
    }

    static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
