package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/calendar/trash")
public class CalendarTrashController {

    private final CalendarTrashService service;

    public CalendarTrashController(CalendarTrashService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CalendarDtos.TrashedEventSummary>> trashedEvents(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.trashedEvents(
                tenantId, userId, personPublicId, groupRefs, locale));
    }
}
