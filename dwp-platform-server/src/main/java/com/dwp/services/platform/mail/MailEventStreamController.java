package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/mail/events")
class MailEventStreamController {

    private final MailEventStreamService events;

    MailEventStreamController(MailEventStreamService events) {
        this.events = events;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "streamMailEvents")
    SseEmitter stream(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long userId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(defaultValue = "0") long after,
            HttpServletRequest request) {
        return events.connect(
                tenantId, userId, reconnectCursor(after, lastEventId),
                MailProductSurfacePepFilter.trustedRevalidateAt(request));
    }

    @GetMapping(path = "/catch-up", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "catchUpMailEvents")
    ApiResponse<MailEventStreamService.CatchUp> catchUp(
            @RequestHeader("X-DWP-Tenant-ID") long tenantId,
            @RequestHeader("X-DWP-User-ID") long userId,
            @RequestParam(defaultValue = "0") long after) {
        return ApiResponse.success(events.catchUp(tenantId, userId, after));
    }

    private long reconnectCursor(long after, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return after;
        try {
            return Math.max(after, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "MAIL_EVENT_CURSOR_INVALID");
        }
    }
}
