package com.dwp.services.platform.localization;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/localization")
public class AdminLocalizationController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String CORRELATION = "X-Correlation-ID";

    private final LocalizationService service;

    public AdminLocalizationController(LocalizationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<LocalizationDtos.Workspace> workspace(
            @RequestHeader(TENANT) Long tenantId) {
        return ApiResponse.success(service.workspace(tenantId));
    }

    @PostMapping("/bundles")
    public ApiResponse<LocalizationDtos.Revision> createBundle(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody LocalizationDtos.CreateBundleRequest request) {
        return ApiResponse.success(service.createBundle(tenantId, actorId, correlationId, request));
    }

    @GetMapping("/bundles/{bundleId}/revisions")
    public ApiResponse<List<LocalizationDtos.Revision>> revisions(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID bundleId) {
        return ApiResponse.success(service.revisions(tenantId, bundleId));
    }

    @PostMapping("/bundles/{bundleId}/drafts")
    public ApiResponse<LocalizationDtos.Revision> createDraft(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID bundleId,
            @Valid @RequestBody LocalizationDtos.RestoreRequest request) {
        return ApiResponse.success(service.createDraft(
                tenantId, actorId, correlationId, bundleId, request));
    }

    @GetMapping("/revisions/{revisionId}")
    public ApiResponse<LocalizationDtos.Revision> revision(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID revisionId) {
        return ApiResponse.success(service.revision(tenantId, revisionId));
    }

    @PutMapping("/revisions/{revisionId}")
    public ApiResponse<LocalizationDtos.Revision> saveDraft(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LocalizationDtos.SaveDraftRequest request) {
        return ApiResponse.success(service.saveDraft(
                tenantId, actorId, correlationId, revisionId, request));
    }

    @GetMapping("/revisions/{revisionId}/preview")
    public ApiResponse<LocalizationDtos.Preview> preview(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID revisionId) {
        return ApiResponse.success(service.revision(tenantId, revisionId).preview());
    }

    @GetMapping("/revisions/{revisionId}/diff")
    public ApiResponse<LocalizationDtos.Diff> diff(
            @RequestHeader(TENANT) Long tenantId,
            @PathVariable UUID revisionId) {
        return ApiResponse.success(service.diff(tenantId, revisionId));
    }

    @PostMapping("/revisions/{revisionId}/submit")
    public ApiResponse<LocalizationDtos.Revision> submit(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LocalizationDtos.TransitionRequest request) {
        return ApiResponse.success(service.submit(
                tenantId, actorId, correlationId, revisionId, request));
    }

    @PostMapping("/revisions/{revisionId}/decision")
    public ApiResponse<LocalizationDtos.Revision> decide(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LocalizationDtos.DecisionRequest request) {
        return ApiResponse.success(service.decide(
                tenantId, actorId, correlationId, revisionId, request));
    }

    @PostMapping("/revisions/{revisionId}/publish")
    public ApiResponse<LocalizationDtos.Revision> publish(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LocalizationDtos.TransitionRequest request) {
        return ApiResponse.success(service.publish(
                tenantId, actorId, correlationId, revisionId, request));
    }

    @PostMapping("/revisions/{revisionId}/restore")
    public ApiResponse<LocalizationDtos.Revision> restore(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LocalizationDtos.RestoreRequest request) {
        return ApiResponse.success(service.restore(
                tenantId, actorId, correlationId, revisionId, request));
    }
}
