package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceFulfillmentController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesController.*;

@RestController
public class WorkplaceServiceOperationsController {
    private final WorkplaceServiceOperationsService service;

    public WorkplaceServiceOperationsController(WorkplaceServiceOperationsService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/workplace/service-providers")
    public ApiResponse<ProviderProfiles> providers(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.providers(tenantId));
    }

    @GetMapping("/v1/admin/workplace/service-providers/{providerId}")
    public ApiResponse<ProviderProfile> provider(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID providerId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.provider(tenantId, providerId));
    }

    @PostMapping("/v1/admin/workplace/service-providers")
    public ResponseEntity<ApiResponse<ProviderCommandResult>> createProvider(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ProviderCreateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        ProviderCommandResult result = service.createProvider(
                tenantId, actorUserId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @PutMapping("/v1/admin/workplace/service-providers/{providerId}")
    public ResponseEntity<ApiResponse<ProviderCommandResult>> updateProvider(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID providerId,
            @Valid @RequestBody ProviderUpdateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        ProviderCommandResult result = service.updateProvider(
                tenantId, actorUserId, providerId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @PostMapping("/v1/admin/workplace/service-providers/{providerId}:state")
    public ResponseEntity<ApiResponse<ProviderCommandResult>> changeProviderState(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID providerId,
            @Valid @RequestBody ProviderStateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        ProviderCommandResult result = service.changeProviderState(
                tenantId, actorUserId, providerId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @PostMapping("/v1/admin/workplace/service-providers/{providerId}:verify")
    public ResponseEntity<ApiResponse<ProviderCommandResult>> verifyProvider(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID providerId,
            @Valid @RequestBody ProviderVerifyRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        ProviderCommandResult result = service.verifyProvider(
                tenantId, actorUserId, providerId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @GetMapping("/v1/admin/workplace/service-assignees")
    public ApiResponse<AssigneeSearchResult> assignees(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam String purpose,
            @RequestParam UUID providerId,
            @RequestParam String siteReference,
            @RequestParam("q") String query,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.searchAssignees(
                tenantId, purpose, providerId, siteReference, query, limit));
    }

    @PostMapping("/v1/admin/workplace/service-orders/{orderId}/tasks/{taskId}:assign")
    public ResponseEntity<ApiResponse<AssigneeAssignmentResult>> assign(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID taskId,
            @Valid @RequestBody AssigneeAssignmentRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        AssigneeAssignmentResult result = service.assign(tenantId, actorUserId,
                orderId, taskId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @GetMapping("/v1/workplace/service-catalog/{catalogItemId}/capacity")
    public ApiResponse<CapacityRange> capacity(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID catalogItemId,
            @RequestParam String siteReference,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.capacity(
                tenantId, catalogItemId, siteReference, from, to));
    }

    @GetMapping("/v1/admin/workplace/service-catalog/{catalogItemId}/capacity")
    public ApiResponse<CapacityRange> adminCapacity(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID catalogItemId,
            @RequestParam String siteReference,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.capacity(
                tenantId, catalogItemId, siteReference, from, to));
    }

    @PutMapping("/v1/admin/workplace/service-catalog/{catalogItemId}/capacity")
    public ResponseEntity<ApiResponse<CapacityUpsertResult>> upsertCapacity(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID catalogItemId,
            @Valid @RequestBody CapacityUpsertRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        CapacityUpsertResult result = service.upsertCapacity(
                tenantId, actorUserId, catalogItemId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @GetMapping("/v1/workplace/service-orders/{orderId}/lines/{lineId}/inspection")
    public ApiResponse<InspectionStatus> requesterInspection(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.inspection(
                tenantId, actorUserId, orderId, lineId, false));
    }

    @PostMapping("/v1/workplace/service-orders/{orderId}/lines/{lineId}/inspection-attempts")
    public ResponseEntity<ApiResponse<InspectionCommandResult>> requesterInspect(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody InspectionAttemptRequest request) {
        requirePermission(permissions, UPDATE);
        InspectionCommandResult result = service.inspect(tenantId, actorUserId,
                orderId, lineId, false, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @GetMapping("/v1/admin/workplace/service-orders/{orderId}/lines/{lineId}/inspection")
    public ApiResponse<InspectionStatus> adminInspection(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.inspection(
                tenantId, actorUserId, orderId, lineId, true));
    }

    @PostMapping("/v1/admin/workplace/service-orders/{orderId}/lines/{lineId}/inspection-attempts")
    public ResponseEntity<ApiResponse<InspectionCommandResult>> adminInspect(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody InspectionAttemptRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        InspectionCommandResult result = service.inspect(tenantId, actorUserId,
                orderId, lineId, true, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @PostMapping("/v1/workplace/service-orders/{orderId}/lines/{lineId}/access-credentials:issue")
    public ResponseEntity<ApiResponse<AccessCredentialIssueResult>> issueCredential(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody AccessCredentialIssueRequest request) {
        requirePermission(permissions, UPDATE);
        requireElevated(accessMode);
        AccessCredentialIssueResult result = service.issueCredential(
                tenantId, actorUserId, orderId, lineId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @PostMapping("/v1/workplace/service-orders/{orderId}/lines/{lineId}/access-credentials/{grantId}:revoke")
    public ResponseEntity<ApiResponse<AccessCredentialStatus>> revokeCredential(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @PathVariable UUID grantId,
            @Valid @RequestBody AccessCredentialRevokeRequest request) {
        requirePermission(permissions, UPDATE);
        requireElevated(accessMode);
        AccessCredentialStatus result = service.revokeCredential(tenantId, actorUserId,
                orderId, lineId, grantId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    @PostMapping("/v1/workplace/service-orders/{orderId}/contacts")
    public ResponseEntity<ApiResponse<ContactResult>> contact(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorUserId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody ContactRequest request) {
        requirePermission(permissions, UPDATE);
        ContactResult result = service.contact(
                tenantId, actorUserId, orderId, idempotencyKey, request, correlationId);
        return accepted(result.receipt().statusHref(), result);
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(String href, T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(href)).body(ApiResponse.success(body));
    }
}
