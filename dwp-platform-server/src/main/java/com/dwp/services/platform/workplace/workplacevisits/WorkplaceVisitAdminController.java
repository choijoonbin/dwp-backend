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
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitController.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace/visits")
public class WorkplaceVisitAdminController {
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String ADMIN_VIEW = "ADMIN.WORKPLACE:VIEW";
    static final String ADMIN_MANAGE = "ADMIN.WORKPLACE:MANAGE";

    private final WorkplaceVisitService service;

    public WorkplaceVisitAdminController(WorkplaceVisitService service) {
        this.service = service;
    }

    @GetMapping("/exceptions")
    public ApiResponse<VisitPage<VisitException>> exceptions(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) VisitState state,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.exceptions(tenantId, state));
    }

    @GetMapping("/{visitId}")
    public ApiResponse<AdminVisit> visit(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.adminVisit(tenantId, actorId, visitId, correlationId));
    }

    @PostMapping("/{visitId}:approve")
    public ResponseEntity<ApiResponse<AdminVisitCommandResult>> approve(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody ApprovalCommand request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.approve(tenantId, actorId, visitId, key, request, correlationId));
    }

    @PostMapping("/{visitId}:retry-access")
    public ResponseEntity<ApiResponse<AdminVisitCommandResult>> retryAccess(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.retryAccess(
                tenantId, actorId, visitId, key, request, correlationId));
    }

    @PostMapping("/{visitId}:notify-host")
    public ResponseEntity<ApiResponse<AdminVisitCommandResult>> notifyHost(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.notifyHost(
                tenantId, actorId, visitId, key, request, correlationId));
    }

    @PostMapping("/{visitId}:confirm-checkout")
    public ResponseEntity<ApiResponse<AdminVisitCommandResult>> confirmCheckout(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.confirmCheckout(
                tenantId, actorId, visitId, key, request, correlationId));
    }

    static void authorizeMutation(String permissions, String accessMode) {
        requirePermission(permissions, ADMIN_MANAGE);
        if (!"ELEVATED".equalsIgnoreCase(accessMode)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Active elevated access is required for this Workplace Visit command.");
        }
    }

    private static ResponseEntity<ApiResponse<AdminVisitCommandResult>> accepted(
            AdminVisitCommandResult result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }
}
