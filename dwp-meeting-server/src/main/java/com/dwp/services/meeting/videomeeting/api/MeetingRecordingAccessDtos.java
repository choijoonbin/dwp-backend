package com.dwp.services.meeting.videomeeting.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class MeetingRecordingAccessDtos {

    private MeetingRecordingAccessDtos() {
    }

    public record AccessTicketCommand(
            @NotNull @PositiveOrZero Long expectedArtifactVersion) {
    }

    public record AccessTicketResponse(
            UUID artifactId,
            long artifactVersion,
            String accessUrl,
            OffsetDateTime expiresAt,
            String contentType) {
    }
}
