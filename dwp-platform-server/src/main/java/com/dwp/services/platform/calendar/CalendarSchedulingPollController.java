package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.*;

/** Internal authenticated API; no public token or external-voter route is exposed. */
@RestController
@RequestMapping("/v1/calendar/scheduling-polls")
public class CalendarSchedulingPollController {

    private final CalendarSchedulingPollService service;

    public CalendarSchedulingPollController(CalendarSchedulingPollService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listCalendarSchedulingPolls")
    public ApiResponse<PollPage> list(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(required = false) OffsetDateTime afterUpdatedAt,
            @RequestParam(required = false) UUID afterPollId,
            @RequestParam(required = false) Integer limit) {
        noStore(response);
        return ApiResponse.success(service.list(
                CalendarSchedulingPollAccess.require(request),
                afterUpdatedAt, afterPollId, limit));
    }

    @PostMapping
    @Operation(operationId = "createCalendarSchedulingPoll")
    public ApiResponse<PollDetails> create(
            HttpServletRequest request,
            HttpServletResponse response,
            @Valid @RequestBody CreatePollRequest body) {
        noStore(response);
        return ApiResponse.success(service.create(
                CalendarSchedulingPollAccess.require(request), body));
    }

    @GetMapping("/{pollId}")
    @Operation(operationId = "getCalendarSchedulingPoll")
    public ApiResponse<PollDetails> detail(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pollId) {
        noStore(response);
        return ApiResponse.success(service.detail(
                CalendarSchedulingPollAccess.require(request), pollId));
    }

    @PostMapping("/{pollId}/open")
    @Operation(operationId = "openCalendarSchedulingPoll")
    public ApiResponse<PollDetails> open(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pollId,
            @Valid @RequestBody ChangePollStateRequest body) {
        noStore(response);
        return ApiResponse.success(service.open(
                CalendarSchedulingPollAccess.require(request), pollId, body));
    }

    @PostMapping("/{pollId}/votes")
    @Operation(operationId = "castCalendarSchedulingPollVotes")
    public ApiResponse<PollDetails> vote(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pollId,
            @Valid @RequestBody CastVotesRequest body) {
        noStore(response);
        return ApiResponse.success(service.vote(
                CalendarSchedulingPollAccess.require(request), pollId, body));
    }

    @PostMapping("/{pollId}/cancel")
    @Operation(operationId = "cancelCalendarSchedulingPoll")
    public ApiResponse<PollDetails> cancel(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pollId,
            @Valid @RequestBody ChangePollStateRequest body) {
        noStore(response);
        return ApiResponse.success(service.cancel(
                CalendarSchedulingPollAccess.require(request), pollId, body));
    }

    @PostMapping("/{pollId}/confirm")
    @Operation(operationId = "confirmCalendarSchedulingPoll")
    public ApiResponse<PollDetails> confirm(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable UUID pollId,
            @Valid @RequestBody ConfirmPollRequest body) {
        noStore(response);
        return ApiResponse.success(service.confirm(
                CalendarSchedulingPollAccess.require(request), pollId, body));
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
