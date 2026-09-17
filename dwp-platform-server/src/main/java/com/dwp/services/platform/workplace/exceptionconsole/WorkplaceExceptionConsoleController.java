package com.dwp.services.platform.workplace.exceptionconsole;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.workplace.exceptionconsole.WorkplaceExceptionConsoleDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace/exceptions")
public class WorkplaceExceptionConsoleController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String CORRELATION = "X-Correlation-ID";
    static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String VIEW = "ADMIN.WORKPLACE:VIEW";
    private static final String MANAGE = "ADMIN.WORKPLACE:MANAGE";
    private final WorkplaceExceptionConsoleService service;

    public WorkplaceExceptionConsoleController(WorkplaceExceptionConsoleService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ExceptionConsole> console(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.console(tenantId));
    }

    @GetMapping("/{exceptionId}")
    public ApiResponse<ExceptionItem> detail(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable String exceptionId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.detail(tenantId, exceptionId));
    }

    @PostMapping("/exports:preview")
    public ApiResponse<ExportPreview> previewExport(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ExportPreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        noStore(response);
        return ApiResponse.success(service.previewExport(
                tenantId, actorId, idempotencyKey, request, correlationId));
    }

    @PostMapping("/exports")
    public ApiResponse<ExportReceipt> startExport(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ExportStartRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        noStore(response);
        return ApiResponse.success(service.startExport(
                tenantId, actorId, idempotencyKey, request, correlationId));
    }

    @GetMapping(value = "/exports/{commandId}/content", produces = "text/csv")
    public ResponseEntity<byte[]> downloadExport(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @PathVariable UUID commandId) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        byte[] content = service.downloadExport(tenantId, actorId, commandId)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("workplace-exceptions.csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(content);
    }

    @PostMapping("/{exceptionId}/recovery:preview")
    public ApiResponse<RecoveryPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable String exceptionId,
            @Valid @RequestBody RecoveryPreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        noStore(response);
        return ApiResponse.success(service.preview(tenantId, actorId, exceptionId,
                idempotencyKey, request, correlationId));
    }

    @PostMapping("/{exceptionId}/recovery")
    public ApiResponse<RecoveryReceipt> recover(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable String exceptionId,
            @Valid @RequestBody RecoveryStartRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        noStore(response);
        return ApiResponse.success(service.recover(tenantId, actorId, exceptionId,
                idempotencyKey, request, correlationId));
    }

    static void requirePermission(String values, String expected) {
        boolean present = values != null && Arrays.stream(values.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
        if (!present) throw new BaseException(ErrorCode.FORBIDDEN,
                "The required Workplace exception-console permission is missing.");
    }

    static void requireElevated(String value) {
        if (!"ELEVATED".equals(value)) throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                "Workplace exception recovery requires current elevated access.");
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
