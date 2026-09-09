package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.MeetingFollowupAuthorityDtos;
import com.dwp.services.auth.service.MeetingFollowupAuthorityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/meeting-followup-authority")
public class MeetingFollowupAuthorityController {

    private final MeetingFollowupAuthorityService service;

    public MeetingFollowupAuthorityController(MeetingFollowupAuthorityService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    public MeetingFollowupAuthorityDtos.AuthorityResult evaluate(
            @Valid @RequestBody MeetingFollowupAuthorityDtos.EvaluateRequest request) {
        return service.evaluate(request);
    }
}
