package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyUserController.*;

@RestController
@RequestMapping("/v1/admin/workplace/safety")
public class SafetyAdminController {
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    static final String ADMIN_VIEW = "ADMIN.WORKPLACE:VIEW";
    static final String ADMIN_MANAGE = "ADMIN.WORKPLACE:MANAGE";
    static final String ADMIN_EXPORT = "ADMIN.WORKPLACE:EXPORT";

    private final SafetyOperationsService service;
    private final SafetyConnectorService connectors;
    private final SafetyPreviewService previews;

    public SafetyAdminController(SafetyOperationsService service, SafetyConnectorService connectors,
                                 SafetyPreviewService previews) {
        this.service = service;
        this.connectors = connectors;
        this.previews = previews;
    }

    @PostMapping("/incidents:preview")
    @Operation(operationId = "previewWorkplaceSafetyIncidentActivation")
    public ResponseEntity<ApiResponse<ActivationPreviewResult>> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ActivationPreviewRequest request) {
        authorizeMutation(permissions, accessMode);
        ActivationPreviewResult result = previews.activation(tenantId, actorId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/activation-previews/{previewId}")
    public ApiResponse<ActivationPreview> activationPreview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID previewId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(previews.activation(tenantId, previewId));
    }

    @PostMapping("/incidents")
    public ResponseEntity<ApiResponse<IncidentCommandResult>> activate(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ActivateIncidentRequest request) {
        authorizeMutation(permissions, accessMode);
        IncidentCommandResult result = service.activate(tenantId, actorId, idempotencyKey,
                request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/incidents")
    public ApiResponse<List<Incident>> incidents(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) IncidentState state,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.incidents(tenantId, state));
    }

    @GetMapping("/incidents/{incidentId}")
    public ApiResponse<Incident> incident(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.incident(tenantId, incidentId));
    }

    @GetMapping("/incidents/{incidentId}/commands/{commandId}")
    public ApiResponse<CommandReceipt> command(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            @PathVariable UUID commandId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.command(tenantId, incidentId, commandId));
    }

    @PostMapping("/incidents/{incidentId}/scope-revisions:preview")
    public ResponseEntity<ApiResponse<ScopePreviewCommandResult>> previewScope(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody ScopeRevisionPreviewRequest request) {
        authorizeMutation(permissions, accessMode);
        ScopePreviewCommandResult result = previews.scope(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/incidents/{incidentId}/scope-revisions/{revisionId}")
    public ApiResponse<ScopeRevisionPreview> scopePreview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            @PathVariable UUID revisionId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(previews.scope(tenantId, incidentId, revisionId));
    }

    @PostMapping("/incidents/{incidentId}/scope-revisions")
    public ResponseEntity<ApiResponse<IncidentCommandResult>> reviseScope(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody ApplyScopeRevisionRequest request) {
        authorizeMutation(permissions, accessMode);
        IncidentCommandResult result = service.applyScope(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @PostMapping("/incidents/{incidentId}/dispatches:resend")
    public ResponseEntity<ApiResponse<IncidentCommandResult>> resend(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody ResendRequest request) {
        authorizeMutation(permissions, accessMode);
        IncidentCommandResult result = service.resend(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/incidents/{incidentId}/messages")
    public ApiResponse<List<IncidentMessage>> messages(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.messages(tenantId, actorId, incidentId, true));
    }

    @PostMapping("/incidents/{incidentId}/assembly-confirmations")
    public ResponseEntity<ApiResponse<AssemblyCommandResult>> confirmAssembly(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody AssemblyConfirmationRequest request) {
        authorizeMutation(permissions, accessMode);
        AssemblyCommandResult result = service.confirmAssembly(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @PostMapping("/incidents/{incidentId}/messages")
    public ResponseEntity<ApiResponse<MessageCommandResult>> message(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody MessageRequest request) {
        authorizeMutation(permissions, accessMode);
        MessageCommandResult result = service.adminMessage(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @PostMapping("/incidents/{incidentId}/closures:preview")
    public ResponseEntity<ApiResponse<ClosurePreviewCommandResult>> previewClosure(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody ClosurePreviewRequest request) {
        authorizeMutation(permissions, accessMode);
        ClosurePreviewCommandResult result = previews.closure(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/incidents/{incidentId}/closure-previews/{previewId}")
    public ApiResponse<ClosurePreview> closurePreview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            @PathVariable UUID previewId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(previews.closure(tenantId, incidentId, previewId));
    }

    @PostMapping("/incidents/{incidentId}/closure-requests")
    public ResponseEntity<ApiResponse<ClosureCommandResult>> requestClosure(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody ClosureRequestInput request) {
        authorizeMutation(permissions, accessMode);
        ClosureCommandResult result = service.requestClosure(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @PostMapping("/incidents/{incidentId}/closure-requests/{closureId}:approve")
    public ResponseEntity<ApiResponse<IncidentCommandResult>> approveClosure(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @PathVariable UUID closureId,
            @Valid @RequestBody ClosureApprovalInput request) {
        authorizeMutation(permissions, accessMode);
        IncidentCommandResult result = service.approveClosure(tenantId, actorId, incidentId,
                closureId, idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/incidents/{incidentId}/report")
    public ApiResponse<PostIncidentReport> report(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.report(tenantId, incidentId));
    }

    @PostMapping("/incidents/{incidentId}/exports")
    public ResponseEntity<ApiResponse<ExportCommandResult>> createExport(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody GuardedExportRequest request) {
        authorizeExport(permissions, accessMode);
        String stepUpEvidence = requireDecisionRevision(decisionRevision);
        ExportCommandResult result = service.createExport(tenantId, actorId, incidentId,
                idempotencyKey, request, correlationId, stepUpEvidence);
        return accepted(result, result.export().downloadHref());
    }

    @GetMapping("/exports/{exportId}/content")
    public ResponseEntity<byte[]> export(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID exportId) {
        authorizeExport(permissions, accessMode);
        ExportContent content = service.export(tenantId, actorId, exportId, correlationId);
        String suffix = content.metadata().format() == ExportFormat.PDF ? ".pdf" : ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("safety-incident" + suffix, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(content.metadata().contentType()))
                .body(content.payload());
    }

    @GetMapping("/connectors")
    public ApiResponse<List<ConnectorTruth>> connectors(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(connectors.truth(tenantId));
    }

    @PutMapping("/connectors/{kind}")
    public ResponseEntity<ApiResponse<ConnectorCommandResult>> configureConnector(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable ConnectorKind kind,
            @Valid @RequestBody ConnectorConfigurationRequest request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        if (kind != request.kind()) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Connector path and request kind must match.");
        noStore(response);
        ConnectorCommandResult result = connectors.configure(
                tenantId, actorId, idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/connectors/commands/{commandId}")
    public ApiResponse<CommandReceipt> connectorCommand(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID commandId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(connectors.command(tenantId, commandId));
    }

    static void authorizeMutation(String permissions, String accessMode) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
    }

    static void authorizeExport(String permissions, String accessMode) {
        requirePermission(permissions, ADMIN_EXPORT);
        requireElevated(accessMode);
    }

    static String requireDecisionRevision(String value) {
        if (value == null || !value.matches("psr-[a-f0-9]{64}")) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "A verified product-surface decision revision is required.");
        }
        return value;
    }

    static void requireElevated(String value) {
        if (!"ELEVATED".equals(value)) throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                "Workplace safety administration requires current elevated access.");
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(T body, String href) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(href)).body(ApiResponse.success(body));
    }
}
