package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/calendar/booking")
public class CalendarBookingController {

    private final CalendarBookingService service;

    public CalendarBookingController(CalendarBookingService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    @Operation(operationId = "getCalendarBookingCapabilities")
    public ApiResponse<CalendarBookingDtos.BookingSecurityCapabilities> capabilities(
            HttpServletRequest request,
            HttpServletResponse response) {
        CalendarBookingAccess.require(request);
        noStore(response);
        return ApiResponse.success(service.capabilities());
    }

    @GetMapping("/pages")
    @Operation(operationId = "listCalendarBookingPages")
    public ApiResponse<List<CalendarBookingDtos.BookingPage>> pages(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.pages(CalendarBookingAccess.require(request)));
    }

    @GetMapping("/pages/{pageId}")
    @Operation(operationId = "getCalendarBookingPage")
    public ApiResponse<CalendarBookingDtos.BookingPage> page(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId) {
        noStore(response);
        return ApiResponse.success(service.page(CalendarBookingAccess.require(request), pageId));
    }

    @GetMapping("/pages/{pageId}/bookable")
    @Operation(operationId = "getAuthenticatedCalendarBookablePage")
    public ApiResponse<CalendarBookingDtos.AuthenticatedBookablePage> bookablePage(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId) {
        noStore(response);
        return ApiResponse.success(service.bookablePage(
                CalendarBookingAccess.require(request), pageId));
    }

    @GetMapping("/pages/{pageId}/availability")
    @Operation(operationId = "getAuthenticatedCalendarBookingAvailability")
    public ApiResponse<CalendarBookingDtos.BookingAvailability> availability(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        noStore(response);
        return ApiResponse.success(service.availability(
                CalendarBookingAccess.require(request), pageId, from, to));
    }

    @PostMapping("/pages")
    @Operation(operationId = "createCalendarBookingPage")
    public ApiResponse<CalendarBookingDtos.BookingPage> createPage(
            HttpServletRequest request,
            HttpServletResponse response,
            @Valid @RequestBody CalendarBookingDtos.SaveBookingPageRequest body) {
        noStore(response);
        return ApiResponse.success(service.createPage(
                CalendarBookingAccess.require(request),
                CalendarBookingAccess.correlationId(request), body));
    }

    @PutMapping("/pages/{pageId}")
    @Operation(operationId = "updateCalendarBookingPage")
    public ApiResponse<CalendarBookingDtos.BookingPage> updatePage(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @Valid @RequestBody CalendarBookingDtos.SaveBookingPageRequest body) {
        noStore(response);
        return ApiResponse.success(service.updatePage(
                CalendarBookingAccess.require(request), pageId,
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/pages/{pageId}/state")
    @Operation(operationId = "changeCalendarBookingPageState")
    public ApiResponse<CalendarBookingDtos.BookingPagePublishReceipt> changeState(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @Valid @RequestBody CalendarBookingDtos.ChangeBookingPageStateRequest body) {
        noStore(response);
        return ApiResponse.success(service.changeState(
                CalendarBookingAccess.require(request), pageId,
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/pages/{pageId}/public-token:rotate")
    @Operation(operationId = "rotateCalendarBookingPagePublicToken")
    public ApiResponse<CalendarBookingDtos.BookingPagePublishReceipt> rotatePageToken(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @Valid @RequestBody CalendarBookingDtos.RotateBookingTokenRequest body) {
        noStore(response);
        return ApiResponse.success(service.rotatePageToken(
                CalendarBookingAccess.require(request), pageId,
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/pages/{pageId}/public-token:revoke")
    @Operation(operationId = "revokeCalendarBookingPagePublicToken")
    public ApiResponse<CalendarBookingDtos.BookingPage> revokePageToken(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @Valid @RequestBody CalendarBookingDtos.RotateBookingTokenRequest body) {
        noStore(response);
        return ApiResponse.success(service.revokePageToken(
                CalendarBookingAccess.require(request), pageId,
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/pages/{pageId}/holds")
    @Operation(operationId = "createAuthenticatedCalendarBookingHold")
    public ApiResponse<CalendarBookingDtos.BookingHold> createHold(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @Valid @RequestBody CalendarBookingDtos.CreateBookingHoldRequest body) {
        noStore(response);
        return ApiResponse.success(service.createHold(
                CalendarBookingAccess.require(request), pageId,
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/pages/{pageId}/holds/{holdId}/cancel")
    @Operation(operationId = "cancelAuthenticatedCalendarBookingHold")
    public ApiResponse<CalendarBookingDtos.BookingHold> cancelHold(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @PathVariable UUID holdId,
            @Valid @RequestBody CalendarBookingDtos.CancelBookingHoldRequest body) {
        noStore(response);
        return ApiResponse.success(service.cancelHold(
                CalendarBookingAccess.require(request), pageId, holdId,
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/pages/{pageId}/appointments")
    @Operation(operationId = "confirmAuthenticatedCalendarBooking")
    public ApiResponse<CalendarBookingDtos.AppointmentReceipt> confirm(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pageId,
            @Valid @RequestBody CalendarBookingDtos.ConfirmBookingRequest body) {
        noStore(response);
        return ApiResponse.success(service.confirm(
                CalendarBookingAccess.require(request), pageId,
                CalendarBookingAccess.locale(request),
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/appointments/{appointmentId}/reschedule")
    @Operation(operationId = "rescheduleAuthenticatedCalendarBooking")
    public ApiResponse<CalendarBookingDtos.Appointment> reschedule(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody CalendarBookingDtos.RescheduleAppointmentRequest body) {
        noStore(response);
        return ApiResponse.success(service.reschedule(
                CalendarBookingAccess.require(request), appointmentId,
                CalendarBookingAccess.locale(request),
                CalendarBookingAccess.correlationId(request), body));
    }

    @GetMapping("/appointments")
    @Operation(operationId = "listMyCalendarBookingAppointments")
    public ApiResponse<List<CalendarBookingDtos.Appointment>> appointments(
            HttpServletRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.appointments(CalendarBookingAccess.require(request)));
    }

    @GetMapping("/appointments/{appointmentId}")
    @Operation(operationId = "getMyCalendarBookingAppointment")
    public ApiResponse<CalendarBookingDtos.Appointment> appointment(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID appointmentId) {
        noStore(response);
        return ApiResponse.success(service.appointment(
                CalendarBookingAccess.require(request), appointmentId));
    }

    @PostMapping("/appointments/{appointmentId}/cancel")
    @Operation(operationId = "cancelAuthenticatedCalendarBooking")
    public ApiResponse<CalendarBookingDtos.Appointment> cancel(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody CalendarBookingDtos.CancelAppointmentRequest body) {
        noStore(response);
        return ApiResponse.success(service.cancel(
                CalendarBookingAccess.require(request), appointmentId,
                CalendarBookingAccess.locale(request),
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/appointments/{appointmentId}/management-token:rotate")
    @Operation(operationId = "rotateCalendarBookingManagementToken")
    public ApiResponse<CalendarBookingDtos.AppointmentReceipt> rotateAppointmentToken(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody CalendarBookingDtos.RotateBookingTokenRequest body) {
        noStore(response);
        return ApiResponse.success(service.rotateAppointmentToken(
                CalendarBookingAccess.require(request), appointmentId,
                CalendarBookingAccess.correlationId(request), body));
    }

    @PostMapping("/appointments/{appointmentId}/management-token:revoke")
    @Operation(operationId = "revokeCalendarBookingManagementToken")
    public ApiResponse<CalendarBookingDtos.Appointment> revokeAppointmentToken(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody CalendarBookingDtos.RotateBookingTokenRequest body) {
        noStore(response);
        return ApiResponse.success(service.revokeAppointmentToken(
                CalendarBookingAccess.require(request), appointmentId,
                CalendarBookingAccess.correlationId(request), body));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
