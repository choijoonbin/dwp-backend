package com.dwp.services.meeting.videomeeting.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingParticipantDisconnectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/v1/meetings/{meetingId}/participants/{participantId}/disconnect")
public class MeetingParticipantDisconnectController {
    private final MeetingParticipantDisconnectService service;
    public MeetingParticipantDisconnectController(MeetingParticipantDisconnectService service) {
        this.service = service;
    }
    @PostMapping
    public ResponseEntity<ApiResponse<DisconnectResponse>> disconnect(
            @PathVariable UUID meetingId, @PathVariable UUID participantId,
            @Valid @RequestBody DisconnectCommand command,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                service.disconnect(meetingId, participantId, command, idempotencyKey, correlationId)));
    }
    public record DisconnectCommand(@Min(0) long expectedVersion) { }
    public record DisconnectResponse(UUID meetingId, UUID participantId, UUID commandId,
                                     String state, boolean blockedForCurrentSession) { }
}
