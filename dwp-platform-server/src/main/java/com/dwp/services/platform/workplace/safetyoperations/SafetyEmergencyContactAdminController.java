package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyAdminController.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyEmergencyContactDtos.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyUserController.*;

@RestController
@RequestMapping("/v1/admin/workplace/safety")
public class SafetyEmergencyContactAdminController {
    private final SafetyEmergencyContactService service;

    public SafetyEmergencyContactAdminController(SafetyEmergencyContactService service) {
        this.service = service;
    }

    @GetMapping("/emergency-contacts")
    public ApiResponse<List<EmergencyContactView>> contacts(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.adminContacts(tenantId));
    }

    @GetMapping("/emergency-contacts/{contactId}")
    public ApiResponse<EmergencyContactView> contact(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID contactId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.adminContact(tenantId, contactId));
    }

    @PutMapping("/emergency-contacts/{contactId}")
    public ResponseEntity<ApiResponse<EmergencyContactConfigurationResult>> configure(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID contactId,
            @Valid @RequestBody EmergencyContactConfigurationRequest request) {
        authorizeMutation(permissions, accessMode);
        EmergencyContactConfigurationResult result = service.configure(
                tenantId, actorId, contactId, idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @PostMapping("/incidents/{incidentId}/emergency-handoffs:preview")
    @Operation(operationId = "previewWorkplaceEmergencyHandoff")
    public ResponseEntity<ApiResponse<EmergencyHandoffPreviewResult>> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody EmergencyHandoffPreviewRequest request) {
        authorizeMutation(permissions, accessMode);
        EmergencyHandoffPreviewResult result = service.preview(
                tenantId, actorId, incidentId, idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/incidents/{incidentId}/emergency-handoff-previews/{previewId}")
    public ApiResponse<EmergencyHandoffPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            @PathVariable UUID previewId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.preview(tenantId, incidentId, previewId));
    }

    @PostMapping("/incidents/{incidentId}/emergency-handoffs")
    public ResponseEntity<ApiResponse<EmergencyHandoffReceipt>> execute(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody ConfirmEmergencyHandoffRequest request) {
        authorizeMutation(permissions, accessMode);
        EmergencyHandoffReceipt result = service.execute(
                tenantId, actorId, incidentId, idempotencyKey, request, correlationId);
        return accepted(result, result.statusHref());
    }

    @GetMapping("/incidents/{incidentId}/emergency-handoffs/{commandId}")
    public ApiResponse<EmergencyHandoffReceipt> handoff(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            @PathVariable UUID commandId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.handoff(tenantId, incidentId, commandId));
    }

    @PostMapping("/incidents/{incidentId}/emergency-handoffs/{commandId}:reconcile")
    @Operation(operationId = "reconcileWorkplaceEmergencyHandoff")
    public ResponseEntity<ApiResponse<EmergencyHandoffReceipt>> reconcile(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @PathVariable UUID commandId,
            @Valid @RequestBody ReconcileEmergencyHandoffRequest request) {
        authorizeMutation(permissions, accessMode);
        EmergencyHandoffReceipt result = service.reconcile(
                tenantId, actorId, incidentId, commandId, idempotencyKey,
                request, correlationId);
        return accepted(result, result.statusHref());
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(T body, String href) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(href)).body(ApiResponse.success(body));
    }
}
