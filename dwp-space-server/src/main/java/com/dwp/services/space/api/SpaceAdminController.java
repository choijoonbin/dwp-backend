package com.dwp.services.space.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.space.domain.SpaceDtos;
import com.dwp.services.space.domain.SpaceService;
import com.dwp.services.space.operations.SpaceOperationsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
public class SpaceAdminController {

    private final SpaceService service;
    private final SpaceOperationsService operations;

    public SpaceAdminController(
            SpaceService service,
            SpaceOperationsService operations) {
        this.service = service;
        this.operations = operations;
    }

    @GetMapping("/overview")
    public ApiResponse<SpaceDtos.AdminOverview> overview() {
        return ApiResponse.success(service.adminOverview());
    }

    @GetMapping("/spaces")
    public ApiResponse<List<SpaceDtos.SpaceSummary>> spaces(
            @RequestParam(defaultValue = "") String q) {
        return ApiResponse.success(service.adminSpaces(q));
    }

    @GetMapping("/requests")
    public ApiResponse<List<SpaceDtos.RequestSummary>> requests(
            @RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.success(service.adminRequests(status));
    }

    @PostMapping("/requests/{requestId}/decision")
    public ApiResponse<SpaceDtos.RequestSummary> decideRequest(
            @PathVariable UUID requestId,
            @Valid @RequestBody SpaceDtos.RequestDecision request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.decideRequest(requestId, request, correlationId));
    }

    @GetMapping("/templates")
    public ApiResponse<List<SpaceDtos.TemplateSummary>> templates() {
        return ApiResponse.success(service.templates(true));
    }

    @PostMapping("/templates")
    public ApiResponse<SpaceDtos.TemplateSummary> createTemplate(
            @Valid @RequestBody SpaceDtos.SaveTemplateRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createTemplate(request, correlationId));
    }

    @PutMapping("/templates/{templateId}")
    public ApiResponse<SpaceDtos.TemplateSummary> updateTemplate(
            @PathVariable UUID templateId,
            @Valid @RequestBody SpaceDtos.SaveTemplateRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.updateTemplate(templateId, request, correlationId));
    }

    @GetMapping("/content-reviews")
    public ApiResponse<List<SpaceDtos.PublicationReviewSummary>> publicationReviews(
            @RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.success(service.publicationReviews(status));
    }

    @PostMapping("/content-reviews/{reviewId}/decision")
    public ApiResponse<Void> decidePublication(
            @PathVariable UUID reviewId,
            @Valid @RequestBody SpaceDtos.ReviewDecision request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        service.decidePublication(reviewId, request, correlationId);
        return ApiResponse.success();
    }

    @GetMapping("/lifecycle")
    public ApiResponse<List<SpaceDtos.LifecycleReviewSummary>> lifecycle(
            @RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.success(service.lifecycleReviews(status));
    }

    @PostMapping("/lifecycle/{lifecycleReviewId}/decision")
    public ApiResponse<Void> decideLifecycle(
            @PathVariable UUID lifecycleReviewId,
            @Valid @RequestBody SpaceDtos.LifecycleDecision request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        service.decideLifecycle(lifecycleReviewId, request, correlationId);
        return ApiResponse.success();
    }

    @GetMapping("/operations")
    public ApiResponse<SpaceDtos.OperationsDashboard> operations() {
        return ApiResponse.success(operations.dashboard());
    }

    @PostMapping("/operations/reconcile")
    public ApiResponse<SpaceDtos.ReconciliationRunSummary> reconcile(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(operations.reconcileManual(correlationId));
    }

    @PostMapping("/operations/entitlements/{syncItemId}/retry")
    public ApiResponse<Void> retryEntitlement(
            @PathVariable UUID syncItemId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        operations.retry(syncItemId, correlationId);
        return ApiResponse.success();
    }

    @PostMapping("/spaces/{spaceKey}/owner-recovery")
    public ApiResponse<List<SpaceDtos.MemberSummary>> recoverOwner(
            @PathVariable String spaceKey,
            @Valid @RequestBody SpaceDtos.RecoverOwnerRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        List<SpaceDtos.MemberSummary> members = service.recoverOwner(
                spaceKey, request, correlationId);
        operations.reconcileRecovery(correlationId);
        return ApiResponse.success(members);
    }
}
