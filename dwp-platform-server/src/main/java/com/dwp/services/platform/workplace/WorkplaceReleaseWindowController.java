package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workplace/release-windows")
class WorkplaceReleaseWindowController {

    private final WorkplaceReleaseWindowService service;

    WorkplaceReleaseWindowController(WorkplaceReleaseWindowService service) {
        this.service = service;
    }

    @GetMapping("/eligible-resources")
    ApiResponse<List<WorkplaceReleaseWindowDtos.AssignedResource>> assignedResources(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
                    UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.assignedResources(
                tenantId, userId, personPublicId, locale, groupRefs));
    }

    @GetMapping
    ApiResponse<List<WorkplaceReleaseWindowDtos.ReleaseWindow>> ownedWindows(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
                    UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        return ApiResponse.success(service.ownedWindows(
                tenantId, userId, personPublicId, from, to, locale));
    }

    @PostMapping
    ApiResponse<WorkplaceReleaseWindowDtos.ReleaseWindow> create(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
                    UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @Valid @RequestBody WorkplaceReleaseWindowDtos.CreateRequest request) {
        return ApiResponse.success(service.create(
                tenantId,
                userId,
                personPublicId,
                locale,
                correlationId,
                idempotencyKey,
                groupRefs,
                request));
    }

    @PostMapping("/{releaseWindowId}/cancel")
    ApiResponse<WorkplaceReleaseWindowDtos.ReleaseWindow> cancel(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
                    UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID releaseWindowId,
            @Valid @RequestBody WorkplaceDtos.VersionRequest request) {
        return ApiResponse.success(service.cancel(
                tenantId,
                userId,
                personPublicId,
                releaseWindowId,
                locale,
                correlationId,
                request));
    }
}
