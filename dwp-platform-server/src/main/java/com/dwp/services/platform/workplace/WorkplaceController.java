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
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workplace")
public class WorkplaceController {

    private final WorkplaceService service;

    public WorkplaceController(WorkplaceService service) {
        this.service = service;
    }

    @GetMapping("/explore")
    public ApiResponse<WorkplaceDtos.ExploreResponse> exploreWorkplace(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
                    UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam(required = false) UUID floorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        return ApiResponse.success(service.explore(
                tenantId, userId, personPublicId, floorId, from, to, locale, groupRefs));
    }

    @GetMapping("/bookings")
    public ApiResponse<List<WorkplaceDtos.Booking>> getWorkplaceBookings(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        return ApiResponse.success(service.myBookings(
                tenantId, userId, from, to, locale, groupRefs));
    }

    @GetMapping("/floors/{floorId}/background")
    public ResponseEntity<org.springframework.core.io.Resource> workplaceFloorBackground(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID floorId) {
        WorkplaceService.FloorBackground content = service.floorBackground(
                tenantId, userId, groupRefs, floorId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .eTag(content.sha256())
                .cacheControl(CacheControl.noCache().cachePrivate().mustRevalidate())
                .body(content.resource());
    }

    @PostMapping("/bookings/{bookingId}/check-in")
    public ApiResponse<WorkplaceDtos.Booking> checkInWorkplaceBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody WorkplaceDtos.VersionRequest request) {
        return ApiResponse.success(service.checkIn(
                tenantId, userId, bookingId, locale, correlationId, groupRefs, request));
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public ApiResponse<WorkplaceDtos.Booking> cancelWorkplaceBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody WorkplaceDtos.VersionRequest request) {
        return ApiResponse.success(service.cancelBooking(
                tenantId, userId, bookingId, locale, correlationId, groupRefs, request));
    }

    @PostMapping("/bookings/{bookingId}/release")
    public ApiResponse<WorkplaceDtos.Booking> releaseWorkplaceBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody WorkplaceDtos.VersionRequest request) {
        return ApiResponse.success(service.releaseBooking(
                tenantId, userId, bookingId, locale, correlationId, groupRefs, request));
    }
}
