package com.dwp.services.meeting.videomeeting.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeetingTranscriptAccessDtos {

    private MeetingTranscriptAccessDtos() {
    }

    public record QueryCommand(
            @NotNull @Min(0) Long expectedArtifactVersion,
            @Min(0) @Max(500) Integer cursor,
            @Min(1) @Max(50) Integer pageSize,
            @Size(max = 80) String query) {
    }

    public record SegmentResponse(
            String segmentId,
            long startMillis,
            long endMillis,
            String text) {
    }

    public record QueryResponse(
            UUID artifactId,
            long artifactVersion,
            List<SegmentResponse> segments,
            Integer nextCursor,
            boolean hasMore,
            boolean queryApplied,
            OffsetDateTime retentionUntil) {
    }
}
