package com.dwp.services.platform.workplace.connectorops;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseStatus;
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
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace/connectors")
public class WorkplaceConnectorOpsController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String CORRELATION = "X-Correlation-ID";
    static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String VIEW = "ADMIN.WORKPLACE:VIEW";
    private static final String MANAGE = "ADMIN.WORKPLACE:MANAGE";

    private final WorkplaceConnectorOpsService service;

    public WorkplaceConnectorOpsController(WorkplaceConnectorOpsService service) {
        this.service = service;
    }

    @GetMapping("/operations")
    public ApiResponse<ConnectorOperations> operations(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.operations(tenantId));
    }

    @GetMapping("/{kind}/operations")
    public ApiResponse<ConnectorRuntimeTruth> detail(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable ConnectorKind kind,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.detail(tenantId, kind));
    }

    @PostMapping("/{kind}/replays:preview")
    public ApiResponse<ReplayPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable ConnectorKind kind,
            @Valid @RequestBody ReplayPreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        noStore(response);
        return ApiResponse.success(service.preview(
                tenantId, actorId, kind, idempotencyKey, request, correlationId));
    }

    @PostMapping("/{kind}/replays")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<ApiResponse<ReplayStartResponse>> start(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable ConnectorKind kind,
            @Valid @RequestBody ReplayStartRequest request) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        ReplayStartResponse result = service.startReplay(
                tenantId, actorId, kind, idempotencyKey, request, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/{kind}/replays/{jobId}")
    public ApiResponse<ReplayJob> replay(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ACCESS_MODE, required = false) String accessMode,
            @PathVariable ConnectorKind kind,
            @PathVariable UUID jobId,
            HttpServletResponse response) {
        requirePermission(permissions, MANAGE);
        requireElevated(accessMode);
        noStore(response);
        return ApiResponse.success(service.replay(tenantId, kind, jobId));
    }

    static void requirePermission(String values, String expected) {
        boolean present = values != null && Arrays.stream(values.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
        if (!present) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The required Workplace connector permission is missing.");
        }
    }

    static void requireElevated(String value) {
        if (!"ELEVATED".equals(value)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Connector replay requires current elevated access.");
        }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
