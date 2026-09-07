package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.FacilitationCommandResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.PollResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.QuestionResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.TimerResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/v1/meetings/{meetingId}/facilitation")
public class MeetingLiveFacilitationController {

    private final MeetingLiveFacilitationService service;

    public MeetingLiveFacilitationController(MeetingLiveFacilitationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MeetingLiveFacilitationDtos.SnapshotResponse> snapshot(
            @PathVariable UUID meetingId) {
        return ApiResponse.success(service.snapshot(meetingId));
    }

    @PostMapping("/questions")
    public ApiResponse<FacilitationCommandResponse<QuestionResponse>> askQuestion(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.AskQuestionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.askQuestion(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/questions/{questionId}/upvote")
    public ApiResponse<FacilitationCommandResponse<QuestionResponse>> upvoteQuestion(
            @PathVariable UUID meetingId,
            @PathVariable UUID questionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.upvoteQuestion(
                meetingId, questionId, idempotencyKey, correlationId));
    }

    @PostMapping("/questions/{questionId}/answer")
    public ApiResponse<FacilitationCommandResponse<QuestionResponse>> answerQuestion(
            @PathVariable UUID meetingId,
            @PathVariable UUID questionId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.AnswerQuestionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.answerQuestion(
                meetingId, questionId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/questions/{questionId}/dismiss")
    public ApiResponse<FacilitationCommandResponse<QuestionResponse>> dismissQuestion(
            @PathVariable UUID meetingId,
            @PathVariable UUID questionId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.VersionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.dismissQuestion(
                meetingId, questionId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/polls")
    public ApiResponse<FacilitationCommandResponse<PollResponse>> createPoll(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.CreatePollCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.createPoll(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/polls/{pollId}/open")
    public ApiResponse<FacilitationCommandResponse<PollResponse>> openPoll(
            @PathVariable UUID meetingId,
            @PathVariable UUID pollId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.VersionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.openPoll(
                meetingId, pollId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/polls/{pollId}/close")
    public ApiResponse<FacilitationCommandResponse<PollResponse>> closePoll(
            @PathVariable UUID meetingId,
            @PathVariable UUID pollId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.VersionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.closePoll(
                meetingId, pollId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/polls/{pollId}/vote")
    public ApiResponse<FacilitationCommandResponse<PollResponse>> vote(
            @PathVariable UUID meetingId,
            @PathVariable UUID pollId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.VotePollCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.vote(
                meetingId, pollId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/timer/start")
    public ApiResponse<FacilitationCommandResponse<TimerResponse>> startTimer(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.StartTimerCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.startTimer(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/timer/pause")
    public ApiResponse<FacilitationCommandResponse<TimerResponse>> pauseTimer(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.VersionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.pauseTimer(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/timer/resume")
    public ApiResponse<FacilitationCommandResponse<TimerResponse>> resumeTimer(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.VersionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.resumeTimer(
                meetingId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/timer/advance")
    public ApiResponse<FacilitationCommandResponse<TimerResponse>> advanceTimer(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingLiveFacilitationDtos.VersionCommand request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.advanceTimer(
                meetingId, request, idempotencyKey, correlationId));
    }
}
