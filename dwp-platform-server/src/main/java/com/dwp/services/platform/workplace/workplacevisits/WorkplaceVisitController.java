package com.dwp.services.platform.workplace.workplacevisits;

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

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;

@RestController
@RequestMapping("/v1/workplace")
public class WorkplaceVisitController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String VIEW = "APP.WORKPLACE:VIEW";
    static final String UPDATE = "APP.WORKPLACE:UPDATE";

    private final WorkplaceVisitService service;

    public WorkplaceVisitController(WorkplaceVisitService service) {
        this.service = service;
    }

    @PostMapping("/visits:preview")
    public ApiResponse<VisitPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody VisitPreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, UPDATE);
        noStore(response);
        return ApiResponse.success(service.preview(
                tenantId, actorId, key, request, correlationId));
    }

    @PostMapping("/visits")
    public ResponseEntity<ApiResponse<VisitCommandResult>> create(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody CreateVisitRequest request) {
        requirePermission(permissions, UPDATE);
        return accepted(service.create(tenantId, actorId, key, request, correlationId));
    }

    @GetMapping("/visits")
    public ApiResponse<VisitPage<RequesterVisit>> visits(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) ReservationAuthority reservationAuthority,
            @RequestParam(required = false) UUID reservationId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.requesterVisits(
                tenantId, actorId, reservationAuthority, reservationId));
    }

    @GetMapping("/visits/{visitId}")
    public ApiResponse<RequesterVisit> visit(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.requesterVisit(
                tenantId, actorId, visitId, correlationId));
    }

    @PostMapping("/visits/{visitId}:send-invitation")
    public ResponseEntity<ApiResponse<VisitCommandResult>> invite(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        requirePermission(permissions, UPDATE);
        return accepted(service.sendInvitation(
                tenantId, actorId, visitId, key, request, correlationId));
    }

    @PostMapping("/visits/{visitId}/access-requests")
    public ResponseEntity<ApiResponse<VisitCommandResult>> requestAccess(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        requirePermission(permissions, UPDATE);
        return accepted(service.requestAccess(
                tenantId, actorId, visitId, key, request, correlationId));
    }

    @PostMapping("/visits/{visitId}:cancel")
    public ResponseEntity<ApiResponse<VisitCommandResult>> cancel(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        requirePermission(permissions, UPDATE);
        return accepted(service.cancel(tenantId, actorId, visitId, key, request, correlationId));
    }

    static void requirePermission(String values, String expected) {
        boolean present = values != null && Arrays.stream(values.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
        if (!present) throw new BaseException(ErrorCode.FORBIDDEN,
                "The required Workplace Visit permission is missing.");
    }

    static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }

    private static ResponseEntity<ApiResponse<VisitCommandResult>> accepted(
            VisitCommandResult result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }
}
