package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
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
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassDtos.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationController.*;

@RestController
@RequestMapping("/v1/workplace/navigation")
public class WorkplaceAccessPassController {
    static final String USER = "X-DWP-User-ID";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String UPDATE = "APP.WORKPLACE:UPDATE";

    private final WorkplaceAccessPassService service;

    public WorkplaceAccessPassController(WorkplaceAccessPassService service) {
        this.service = service;
    }

    @GetMapping("/access-pass")
    public ApiResponse<AccessPassContext> context(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam UUID siteId,
            @RequestParam UUID resourceId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.context(tenantId, actorId, siteId, resourceId));
    }

    @PostMapping("/access-pass:preview")
    public ApiResponse<AccessPassPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @Valid @RequestBody AccessPassPreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, UPDATE);
        noStore(response);
        return ApiResponse.success(service.preview(tenantId, actorId, idempotencyKey, request));
    }

    @PostMapping("/access-pass:execute")
    public ResponseEntity<ApiResponse<AccessPassCommandResult>> execute(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(ACCESS_MODE) String activeAccessMode,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ConfirmAccessPassCommandRequest request) {
        requirePermission(permissions, UPDATE);
        requireElevated(activeAccessMode);
        AccessPassCommandResult result = service.execute(
                tenantId, actorId, idempotencyKey, request, correlationId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/access-pass/commands/{commandId}")
    public ApiResponse<AccessPassCommandResult> command(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID commandId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.command(tenantId, actorId, commandId));
    }

    @GetMapping("/access-pass/audit-events")
    public ApiResponse<List<AccessPassAuditEvent>> auditEvents(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) UUID passId,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.auditEvents(tenantId, actorId, passId, limit));
    }

    private static void requireElevated(String activeAccessMode) {
        if (!"ELEVATED".equalsIgnoreCase(activeAccessMode == null ? "" : activeAccessMode.trim())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Fresh step-up access is required for mobile access-pass commands.");
        }
    }
}
