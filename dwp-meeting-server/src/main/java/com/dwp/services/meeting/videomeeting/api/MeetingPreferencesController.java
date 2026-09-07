package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.PreferencesInput;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.PreferencesResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingPreferencesService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/preferences")
public class MeetingPreferencesController {
    private final MeetingPreferencesService service;
    public MeetingPreferencesController(MeetingPreferencesService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PreferencesResponse> get() { return ApiResponse.success(service.get()); }

    @PutMapping
    public ApiResponse<PreferencesResponse> update(@Valid @RequestBody PreferencesInput input,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation) {
        return ApiResponse.success(service.update(input, key, correlation));
    }
}
