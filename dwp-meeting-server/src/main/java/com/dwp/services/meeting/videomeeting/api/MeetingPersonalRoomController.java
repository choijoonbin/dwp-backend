package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.*;
import com.dwp.services.meeting.videomeeting.domain.MeetingPersonalRoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
public class MeetingPersonalRoomController {
    private final MeetingPersonalRoomService service;
    public MeetingPersonalRoomController(MeetingPersonalRoomService service) { this.service = service; }

    @GetMapping("/v1/personal-room")
    public ApiResponse<PersonalRoomResponse> get() { return ApiResponse.success(service.get()); }

    @PostMapping("/v1/personal-room")
    public ApiResponse<PersonalRoomResponse> create(@Valid @RequestBody RoomCreate input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.create(input, key, correlation));
    }

    @PutMapping("/v1/personal-room")
    public ApiResponse<PersonalRoomResponse> update(@Valid @RequestBody RoomUpdate input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.update(input, key, correlation));
    }

    @PostMapping("/v1/personal-room/rotate-invitation")
    public ApiResponse<PersonalRoomResponse> rotate(@Valid @RequestBody VersionCommand input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.rotate(input, key, correlation));
    }

    @PostMapping("/v1/personal-room/sessions")
    public ApiResponse<RoomSessionResponse> createSession(@Valid @RequestBody RoomSessionCommand input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.createSession(input, key, correlation));
    }

    @GetMapping("/v1/personal-room/sessions")
    public ApiResponse<RoomSessionPage> history(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int pageSize) {
        return ApiResponse.success(service.history(page, pageSize));
    }

    @GetMapping("/v1/personal-rooms/{alias}/invitation")
    public ApiResponse<InvitationResponse> resolve(@PathVariable String alias,
            @RequestParam @Min(1) long revision) {
        return ApiResponse.success(service.resolve(alias, revision));
    }
}
