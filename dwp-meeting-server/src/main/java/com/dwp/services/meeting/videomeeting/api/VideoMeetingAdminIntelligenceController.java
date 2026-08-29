package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingAdminIntelligenceReadinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/intelligence")
public class VideoMeetingAdminIntelligenceController {

    private final VideoMeetingAdminIntelligenceReadinessService service;

    public VideoMeetingAdminIntelligenceController(
            VideoMeetingAdminIntelligenceReadinessService service) {
        this.service = service;
    }

    @GetMapping("/readiness")
    public ApiResponse<VideoMeetingAdminIntelligenceDtos.ReadinessResponse> readiness() {
        return ApiResponse.success(service.readiness());
    }
}
