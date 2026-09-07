package com.dwp.services.platform.workhub.calendar;

import io.swagger.v3.oas.annotations.Parameter;
import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.dwp.services.platform.workhub.calendar.WorkCalendarDtos.*;

@RestController
@RequestMapping("/v1/workspace/work-hub/calendar-links")
public class WorkCalendarController {
    private final WorkCalendarService service;
    public WorkCalendarController(WorkCalendarService service) { this.service = service; }

    @ModelAttribute("workCalendarContext")
    public AccessContext context(@RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId, @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return new AccessContext(tenantId, userId, permissions, personPublicId, groupRefs, locale);
    }

    @GetMapping
    public ApiResponse<LinkPage> list(@Parameter(hidden = true) @ModelAttribute(value = "workCalendarContext", binding = false) AccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(service.list(context, page, size));
    }

    @PutMapping("/{linkId}")
    public ApiResponse<Link> put(@Parameter(hidden = true) @ModelAttribute(value = "workCalendarContext", binding = false) AccessContext context,
            @PathVariable UUID linkId, @Valid @RequestBody LinkRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.put(context, linkId, request, correlationId));
    }

    @DeleteMapping("/{linkId}")
    public ApiResponse<Link> remove(@Parameter(hidden = true) @ModelAttribute(value = "workCalendarContext", binding = false) AccessContext context,
            @PathVariable UUID linkId, @RequestParam long version,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.remove(context, linkId, version, correlationId));
    }
}
