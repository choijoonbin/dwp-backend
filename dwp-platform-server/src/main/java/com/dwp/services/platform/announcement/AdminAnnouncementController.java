package com.dwp.services.platform.announcement;

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

@RestController
@RequestMapping("/v1/admin/announcements")
public class AdminAnnouncementController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final AnnouncementService service;

    public AdminAnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AnnouncementDtos.AnnouncementResponse>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.listAdmin(tenantId));
    }

    @PostMapping
    public ApiResponse<AnnouncementDtos.AnnouncementResponse> create(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AnnouncementDtos.CreateAnnouncementRequest request) {
        return ApiResponse.success(service.create(tenantId, actorId, correlationId, request));
    }

    @PutMapping("/{announcementId}")
    public ApiResponse<AnnouncementDtos.AnnouncementResponse> update(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long announcementId,
            @Valid @RequestBody AnnouncementDtos.UpdateAnnouncementRequest request) {
        return ApiResponse.success(
                service.update(tenantId, actorId, correlationId, announcementId, request));
    }

    @PostMapping("/{announcementId}/publish")
    public ApiResponse<AnnouncementDtos.AnnouncementResponse> publish(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long announcementId,
            @Valid @RequestBody AnnouncementDtos.VersionRequest request) {
        return ApiResponse.success(service.publish(
                tenantId, actorId, correlationId, announcementId, request.version()));
    }

    @PostMapping("/{announcementId}/archive")
    public ApiResponse<AnnouncementDtos.AnnouncementResponse> archive(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long announcementId,
            @Valid @RequestBody AnnouncementDtos.VersionRequest request) {
        return ApiResponse.success(service.archive(
                tenantId, actorId, correlationId, announcementId, request.version()));
    }
}
