package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/rooms")
public class RoomsController {

    private final RoomService service;

    public RoomsController(RoomService service) {
        this.service = service;
    }

    @GetMapping("/policy")
    public ApiResponse<CalendarDtos.Policy> getRoomPolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.policy(tenantId));
    }

    @GetMapping("/availability")
    public ApiResponse<CalendarDtos.RoomAvailabilityResponse> getRoomAvailability(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        return ApiResponse.success(service.roomAvailability(
                tenantId, userId, groupRefs, from, to, locale));
    }

    @GetMapping("/bookings")
    public ApiResponse<List<CalendarDtos.EventSummary>> getRoomBookings(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        return ApiResponse.success(service.roomBookings(
                tenantId, userId, personPublicId, groupRefs, from, to, locale));
    }

    @PostMapping("/bookings")
    public ApiResponse<CalendarDtos.EventSummary> createRoomBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Display-Name-B64", required = false) String displayName,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @Valid @RequestBody CalendarDtos.CreateEventRequest request) {
        return ApiResponse.success(service.createRoomBooking(
                tenantId, userId, personPublicId, decoded(displayName),
                locale, correlationId, groupRefs, request));
    }

    @PutMapping("/bookings/{eventId}")
    public ApiResponse<CalendarDtos.EventSummary> updateRoomBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.UpdateEventRequest request) {
        return ApiResponse.success(service.updateRoomBooking(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, groupRefs, request));
    }

    @PostMapping("/bookings/{eventId}/response")
    public ApiResponse<CalendarDtos.EventSummary> respondToRoomBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.RespondRequest request) {
        return ApiResponse.success(service.respondRoomBooking(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, groupRefs, request));
    }

    @PostMapping("/bookings/{eventId}/cancel")
    public ApiResponse<Void> cancelRoomBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.VersionRequest request) {
        service.cancelRoomBooking(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, groupRefs, request);
        return ApiResponse.success(null);
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8).trim();
            return decoded.isBlank() || decoded.length() > 160 ? null : decoded;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
