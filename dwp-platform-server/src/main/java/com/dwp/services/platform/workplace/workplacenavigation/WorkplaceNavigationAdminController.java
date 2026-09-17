package com.dwp.services.platform.workplace.workplacenavigation;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationController.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace")
public class WorkplaceNavigationAdminController {
    static final String USER = "X-DWP-User-ID";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String ADMIN_VIEW = "ADMIN.WORKPLACE:VIEW";
    static final String ADMIN_MANAGE = "ADMIN.WORKPLACE:MANAGE";

    private final WorkplaceNavigationService navigation;
    private final WorkplaceDeviceService devices;

    public WorkplaceNavigationAdminController(
            WorkplaceNavigationService navigation,
            WorkplaceDeviceService devices) {
        this.navigation = navigation;
        this.devices = devices;
    }

    @GetMapping("/navigation/graphs")
    public ApiResponse<List<GraphRevisionView>> graphs(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) UUID siteId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(navigation.graphs(tenantId, siteId));
    }

    @PostMapping("/navigation/graphs")
    public ResponseEntity<ApiResponse<GraphRevisionView>> createGraph(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody CreateGraphRequest request) {
        authorizeMutation(permissions, accessMode);
        GraphRevisionView result = navigation.createGraph(
                tenantId, actorId, idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .body(ApiResponse.success(result));
    }

    @PostMapping("/navigation/graphs/{graphId}:publish")
    public ApiResponse<GraphRevisionView> publishGraph(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID graphId,
            @Valid @RequestBody PublishGraphRequest request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        noStore(response);
        return ApiResponse.success(navigation.publishGraph(
                tenantId, actorId, graphId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/navigation/graphs/{graphId}:review")
    public ApiResponse<GraphRevisionView> submitGraphForReview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID graphId,
            @Valid @RequestBody GraphTransitionRequest request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        noStore(response);
        return ApiResponse.success(navigation.submitForReview(
                tenantId, actorId, graphId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/navigation/graphs/{graphId}:archive")
    public ApiResponse<GraphRevisionView> archiveGraph(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID graphId,
            @Valid @RequestBody GraphTransitionRequest request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        noStore(response);
        return ApiResponse.success(navigation.archiveGraph(
                tenantId, actorId, graphId, idempotencyKey, correlationId, request));
    }

    @GetMapping("/devices")
    public ApiResponse<List<DeviceView>> devices(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) UUID siteId,
            @RequestParam(required = false) RegistrationState state,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(devices.devices(tenantId, siteId, state));
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<DeviceView> device(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID deviceId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(devices.device(tenantId, deviceId));
    }

    @GetMapping("/devices/{deviceId}/commands")
    public ApiResponse<List<DeviceCommandReceipt>> deviceCommands(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID deviceId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(devices.commands(tenantId, deviceId));
    }

    @GetMapping("/devices/{deviceId}/audit-events")
    public ApiResponse<List<DeviceAuditEvent>> deviceAuditEvents(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID deviceId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(devices.auditEvents(tenantId, deviceId));
    }

    @PostMapping("/devices/{deviceId}:approve")
    public ApiResponse<DeviceView> approve(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody VersionedAdminCommand request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        noStore(response);
        return ApiResponse.success(devices.approve(
                tenantId, actorId, deviceId, idempotencyKey, request, correlationId));
    }

    @PostMapping("/devices/{deviceId}:bind")
    public ApiResponse<DeviceView> bind(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody BindDeviceRequest request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        noStore(response);
        return ApiResponse.success(devices.bind(
                tenantId, actorId, deviceId, idempotencyKey, request, correlationId));
    }

    @GetMapping("/device-providers")
    public ApiResponse<List<ProviderTruth>> providerTruth(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(devices.providerTruth(tenantId));
    }

    @PutMapping("/device-providers/{capability}")
    public ApiResponse<ProviderTruth> configureProvider(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable ProviderCapability capability,
            @Valid @RequestBody ProviderConfigurationRequest request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        noStore(response);
        return ApiResponse.success(devices.configureProvider(
                tenantId, actorId, capability, idempotencyKey, request, correlationId));
    }

    @PostMapping("/devices/{deviceId}/commands:preview")
    public ApiResponse<DeviceCommandPreview> previewCommand(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody DeviceCommandPreviewRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_MANAGE);
        noStore(response);
        return ApiResponse.success(devices.preview(
                tenantId, actorId, deviceId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/devices/{deviceId}/commands")
    public ResponseEntity<ApiResponse<DeviceCommandReceipt>> executeCommand(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody ExecuteDeviceCommandRequest request) {
        authorizeMutation(permissions, accessMode);
        DeviceCommandReceipt receipt = devices.execute(
                tenantId, actorId, deviceId, idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(receipt.statusHref()))
                .body(ApiResponse.success(receipt));
    }

    @GetMapping("/devices/commands/{commandId}")
    public ApiResponse<DeviceCommandReceipt> commandReceipt(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID commandId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(devices.receipt(tenantId, commandId));
    }

    static void authorizeMutation(String permissions, String accessMode) {
        requirePermission(permissions, ADMIN_MANAGE);
        if (!"ELEVATED".equalsIgnoreCase(accessMode)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Fresh elevated access is required for this Workplace device command.");
        }
    }
}
