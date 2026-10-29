package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/calendar/events/{eventId}/reminders")
public class CalendarReminderController {

    private final CalendarReminderService service;

    public CalendarReminderController(CalendarReminderService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "getCalendarEventReminders")
    public ApiResponse<CalendarReminderDtos.ReminderSet> reminders(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID eventId) {
        noStore(response);
        return ApiResponse.success(service.reminders(
                CalendarReminderAccess.require(request, eventId), eventId));
    }

    @PutMapping
    @Operation(operationId = "replaceCalendarEventReminders")
    public ApiResponse<CalendarReminderDtos.ReminderSet> replace(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarReminderDtos.ReplaceRemindersRequest body) {
        noStore(response);
        return ApiResponse.success(service.replace(
                CalendarReminderAccess.require(request, eventId), eventId,
                CalendarReminderAccess.correlationId(request), body));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
