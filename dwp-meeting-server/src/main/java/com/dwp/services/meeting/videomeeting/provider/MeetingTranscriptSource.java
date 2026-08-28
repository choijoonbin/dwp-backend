package com.dwp.services.meeting.videomeeting.provider;

import java.util.List;
import java.util.UUID;

/** Reads normalized transcript segments from trusted storage without exposing object keys to APIs. */
public interface MeetingTranscriptSource {

    boolean available();

    List<MeetingIntelligenceProvider.TranscriptSegment> read(ReadContext context);

    record ReadContext(
            long tenantId,
            UUID meetingId,
            UUID runId,
            UUID artifactId,
            String expectedSha256,
            String correlationId) {
    }
}
