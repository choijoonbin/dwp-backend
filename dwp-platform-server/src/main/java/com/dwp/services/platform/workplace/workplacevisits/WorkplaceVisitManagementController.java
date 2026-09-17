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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitAdminController.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitController.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;

@RestController
@RequestMapping("/v1/admin/workplace")
public class WorkplaceVisitManagementController {
    private final WorkplaceVisitManagementService service;

    public WorkplaceVisitManagementController(WorkplaceVisitManagementService service) {
        this.service = service;
    }

    @GetMapping("/visit-policies")
    public ApiResponse<List<VisitPolicy>> policies(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        authorizeRead(permissions, response);
        return ApiResponse.success(service.policies(tenantId));
    }

    @PostMapping("/visit-policies")
    public ResponseEntity<ApiResponse<ManagementResult<VisitPolicy>>> createPolicy(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody VisitPolicyRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.createPolicy(tenantId, actorId, key, request, correlationId),
                "/v1/admin/workplace/visit-policies/");
    }

    @PutMapping("/visit-policies/{policyId}")
    public ResponseEntity<ApiResponse<ManagementResult<VisitPolicy>>> updatePolicy(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID policyId,
            @Valid @RequestBody VisitPolicyRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.updatePolicy(
                tenantId, actorId, policyId, key, request, correlationId),
                "/v1/admin/workplace/visit-policies/");
    }

    @PostMapping("/visit-policies/{policyId}:impact-preview")
    public ApiResponse<PolicyImpactPreview> policyImpact(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @PathVariable UUID policyId,
            @Valid @RequestBody VersionCommand request,
            HttpServletResponse response) {
        authorizeMutation(permissions, accessMode);
        noStore(response);
        PolicyImpactPreview preview = service.policyImpact(tenantId, policyId);
        if (!request.explicitConfirmation()) throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE, "Explicit confirmation is required.");
        if (preview.currentVersion() != request.expectedVersion()) throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT, "The policy version changed.");
        return ApiResponse.success(preview);
    }

    @GetMapping("/access-zones")
    public ApiResponse<List<AccessZone>> zones(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        authorizeRead(permissions, response);
        return ApiResponse.success(service.zones(tenantId));
    }

    @PostMapping("/access-zones")
    public ResponseEntity<ApiResponse<ManagementResult<AccessZone>>> createZone(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody AccessZoneRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.createZone(tenantId, actorId, key, request, correlationId),
                "/v1/admin/workplace/access-zones/");
    }

    @PutMapping("/access-zones/{zoneId}")
    public ResponseEntity<ApiResponse<ManagementResult<AccessZone>>> updateZone(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID zoneId,
            @Valid @RequestBody AccessZoneRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.updateZone(
                tenantId, actorId, zoneId, key, request, correlationId),
                "/v1/admin/workplace/access-zones/");
    }

    @GetMapping("/provider-bindings")
    public ApiResponse<List<ProviderBinding>> providers(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        authorizeRead(permissions, response);
        return ApiResponse.success(service.providers(tenantId));
    }

    @PostMapping("/provider-bindings")
    public ResponseEntity<ApiResponse<ManagementResult<ProviderBinding>>> createProvider(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ProviderBindingRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.createProvider(tenantId, actorId, key, request, correlationId),
                "/v1/admin/workplace/provider-bindings/");
    }

    @PutMapping("/provider-bindings/{bindingId}")
    public ResponseEntity<ApiResponse<ManagementResult<ProviderBinding>>> updateProvider(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID bindingId,
            @Valid @RequestBody ProviderBindingRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.updateProvider(
                tenantId, actorId, bindingId, key, request, correlationId),
                "/v1/admin/workplace/provider-bindings/");
    }

    @PostMapping("/provider-bindings/{bindingId}:test")
    public ResponseEntity<ApiResponse<ManagementResult<ProviderBinding>>> testProvider(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID bindingId,
            @Valid @RequestBody ProviderEvidenceRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.recordProviderEvidence(
                tenantId, actorId, bindingId, key, request, correlationId),
                "/v1/admin/workplace/provider-bindings/");
    }

    @GetMapping("/kiosk-devices")
    public ApiResponse<List<KioskDevice>> devices(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        authorizeRead(permissions, response);
        return ApiResponse.success(service.devices(tenantId));
    }

    @PostMapping("/kiosk-devices")
    public ResponseEntity<ApiResponse<ManagementResult<KioskDevice>>> createDevice(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody KioskDeviceRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.createDevice(tenantId, actorId, key, request, correlationId),
                "/v1/admin/workplace/kiosk-devices/");
    }

    @PutMapping("/kiosk-devices/{deviceId}")
    public ResponseEntity<ApiResponse<ManagementResult<KioskDevice>>> updateDevice(
            @RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody KioskDeviceRequest request) {
        authorizeMutation(permissions, accessMode);
        return accepted(service.updateDevice(
                tenantId, actorId, deviceId, key, request, correlationId),
                "/v1/admin/workplace/kiosk-devices/");
    }

    private static void authorizeRead(String permissions, HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
    }

    private static <T> ResponseEntity<ApiResponse<ManagementResult<T>>> accepted(
            ManagementResult<T> result, String basePath) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(basePath + result.receipt().resourceId()))
                .body(ApiResponse.success(result));
    }
}
