package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;

@RestController
@RequestMapping("/v1/workplace/experience/collaboration")
public class WorkplaceExperienceCollaborationController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String GROUPS = "X-DWP-Group-Refs";
    private static final String CORRELATION = "X-Correlation-ID";
    private final WorkplaceExperienceCollaborationService service;
    private final WorkplaceExperienceCollaborationMediaService media;

    public WorkplaceExperienceCollaborationController(WorkplaceExperienceCollaborationService service,
                                                       WorkplaceExperienceCollaborationMediaService media) {
        this.service = service;
        this.media = media;
    }

    @GetMapping("/overview")
    public ApiResponse<CollaborationOverview> overview(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId, @RequestHeader(value = GROUPS, required = false) String groups,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID groupRef, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        return ApiResponse.success(service.overview(tenantId, userId, groups, from, to, groupRef));
    }

    @PostMapping("/work-plans")
    public ApiResponse<WorkPlan> savePlan(@RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long userId,
            @RequestHeader(value = GROUPS, required = false) String groups,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody WorkPlanRequest request) {
        return ApiResponse.success(service.savePlan(tenantId, userId, groups, correlationId, request));
    }

    @DeleteMapping("/work-plans/{planId}")
    public ApiResponse<MutationResult> deletePlan(@RequestHeader(TENANT) long tenantId, @RequestHeader(USER) long userId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID planId, @RequestParam long version) {
        return ApiResponse.success(service.deletePlan(tenantId, userId, planId, version, correlationId));
    }

    @PutMapping("/sharing-preference")
    public ApiResponse<SharingPreference> savePreference(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId, @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody SharingPreferenceRequest request) {
        return ApiResponse.success(service.savePreference(tenantId, userId, request, correlationId));
    }

    @DeleteMapping("/sharing-preference")
    public ApiResponse<SharingPreference> revokePreference(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId, @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @RequestParam long version) {
        return ApiResponse.success(service.revokePreference(tenantId, userId, version, correlationId));
    }

    @GetMapping("/resources/{resourceId}/photo/metadata")
    public ApiResponse<ResourcePhoto> photoMetadata(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId, @RequestHeader(value = GROUPS, required = false) String groups,
            @PathVariable UUID resourceId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        return ApiResponse.success(media.metadata(tenantId, userId, groups, resourceId));
    }

    @GetMapping("/resources/{resourceId}/photo")
    public ResponseEntity<org.springframework.core.io.Resource> photo(@RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId, @RequestHeader(value = GROUPS, required = false) String groups,
            @PathVariable UUID resourceId) {
        var content = media.content(tenantId, userId, groups, resourceId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.metadata().contentType()))
                .contentLength(content.metadata().sizeBytes()).eTag(content.metadata().sha256()).body(content.resource());
    }
}
