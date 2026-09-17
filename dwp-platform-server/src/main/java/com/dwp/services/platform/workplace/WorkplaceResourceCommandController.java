package com.dwp.services.platform.workplace;

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
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceResourceCommandDtos.*;

@RestController
@RequestMapping("/v1/workplace/bookings/{bookingId}")
public class WorkplaceResourceCommandController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String VIEW = "APP.WORKPLACE:VIEW";
    static final String UPDATE = "APP.WORKPLACE:UPDATE";

    private final WorkplaceResourceCommandService service;

    public WorkplaceResourceCommandController(WorkplaceResourceCommandService service) {
        this.service = service;
    }

    @GetMapping("/resource-command-context")
    public ApiResponse<CommandContext> context(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID bookingId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.context(tenantId, actorId, bookingId));
    }

    @PostMapping("/resource-commands:preview")
    public ApiResponse<CommandPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID bookingId,
            @Valid @RequestBody PreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, UPDATE);
        noStore(response);
        return ApiResponse.success(service.preview(tenantId, actorId, bookingId, request));
    }

    @GetMapping("/resource-command-previews/{previewId}")
    public ApiResponse<CommandPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID bookingId,
            @PathVariable UUID previewId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.preview(tenantId, actorId, bookingId, previewId));
    }

    @PostMapping("/resource-commands")
    public ResponseEntity<ApiResponse<CommandReceipt>> execute(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody ExecuteRequest request) {
        requirePermission(permissions, UPDATE);
        requireElevated(accessMode);
        CommandReceipt receipt = service.execute(tenantId, actorId, bookingId,
                idempotencyKey, correlationId, request);
        return response(receipt);
    }

    @GetMapping("/resource-commands/{commandId}")
    public ApiResponse<CommandReceipt> receipt(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID bookingId,
            @PathVariable UUID commandId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.receipt(tenantId, actorId, bookingId, commandId));
    }

    @PostMapping("/resource-commands/{commandId}:reconcile")
    public ResponseEntity<ApiResponse<CommandReceipt>> reconcile(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID bookingId,
            @PathVariable UUID commandId,
            @Valid @RequestBody ReconcileRequest request) {
        requirePermission(permissions, UPDATE);
        requireElevated(accessMode);
        return response(service.reconcile(tenantId, actorId, bookingId,
                commandId, idempotencyKey, correlationId, request));
    }

    private static ResponseEntity<ApiResponse<CommandReceipt>> response(CommandReceipt receipt) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(receipt.statusHref()))
                .body(ApiResponse.success(receipt));
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

    static void requireElevated(String value) {
        if (!"ELEVATED".equalsIgnoreCase(value == null ? "" : value.trim())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Fresh step-up access is required for physical resource commands.");
        }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
