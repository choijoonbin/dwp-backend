package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpacePlanningBoardReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.CATALOG_MANAGE;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.CATALOG_VIEW;

@RestController
@RequestMapping("/v1/admin/workplace/space-planning")
public class WorkplaceSpacePlanningBoardReportController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String EXPORT = "ADMIN.WORKPLACE:EXPORT";

    private final WorkplaceSpacePlanningBoardReportService service;
    private final WorkplaceDelegatedAdminScopeGuard scopeGuard;

    public WorkplaceSpacePlanningBoardReportController(
            WorkplaceSpacePlanningBoardReportService service,
            WorkplaceDelegatedAdminScopeGuard scopeGuard) {
        this.service = service;
        this.scopeGuard = scopeGuard;
    }

    @PostMapping("/reports:preview")
    @Operation(operationId = "previewWorkplaceSpacePlanningReport")
    public ApiResponse<ReportPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @RequestParam UUID siteId,
            @Valid @RequestBody PreviewRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        requirePermission(permissions);
        noStore(response);
        return ApiResponse.success(service.preview(tenantId, actorId, siteId, idempotencyKey,
                request, correlationId, scopeGuard.scope(servletRequest, siteId, CATALOG_VIEW)));
    }

    @PostMapping("/reports")
    public ApiResponse<ReportReceipt> execute(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @RequestParam UUID siteId,
            @Valid @RequestBody ExecuteRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        authorizeElevated(permissions, accessMode);
        noStore(response);
        return ApiResponse.success(service.execute(tenantId, actorId, siteId, idempotencyKey,
                request, correlationId, decisionRevision,
                scopeGuard.scope(servletRequest, siteId, CATALOG_MANAGE)));
    }

    @GetMapping("/reports/{commandId}")
    public ApiResponse<ReportReceipt> receipt(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam UUID siteId,
            @PathVariable UUID commandId,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        requirePermission(permissions);
        noStore(response);
        return ApiResponse.success(service.receipt(tenantId, actorId, siteId, commandId,
                scopeGuard.scope(servletRequest, siteId, CATALOG_VIEW)));
    }

    @Operation(summary = "Download a guarded aggregate space-planning board report")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            content = {
                    @Content(mediaType = "application/pdf",
                            schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            schema = @Schema(type = "string", format = "binary"))
            })
    @GetMapping(value = "/reports/{commandId}/content", produces = {
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    })
    public ResponseEntity<byte[]> content(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @RequestParam UUID siteId,
            @PathVariable UUID commandId,
            HttpServletRequest servletRequest) {
        authorizeElevated(permissions, accessMode);
        ReportContent content = service.content(tenantId, actorId, siteId, commandId,
                correlationId, decisionRevision,
                scopeGuard.scope(servletRequest, siteId, CATALOG_MANAGE));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.receipt().fileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.parseMediaType(content.receipt().mimeType()))
                .contentLength(content.receipt().byteSize())
                .body(content.payload());
    }

    static void authorizeElevated(String permissions, String accessMode) {
        requirePermission(permissions);
        if (!"ELEVATED".equals(accessMode)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Current elevated access is required for a Workplace board-report export.");
        }
    }

    static void requirePermission(String values) {
        boolean present = values != null && Arrays.stream(values.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(EXPORT::equals);
        if (!present) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The Workplace board-report export permission is required.");
        }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
