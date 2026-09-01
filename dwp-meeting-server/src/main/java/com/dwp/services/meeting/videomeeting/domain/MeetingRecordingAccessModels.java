package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;

import java.time.OffsetDateTime;
import java.util.UUID;

final class MeetingRecordingAccessModels {

    private MeetingRecordingAccessModels() {
    }

    record RecordingArtifact(
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            String artifactState,
            String storageProvider,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String sha256,
            OffsetDateTime retentionUntil,
            long version) {
    }

    record PreparedAccess(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            Participant participant,
            RecordingArtifact artifact,
            OffsetDateTime expiresNoLaterThan,
            String correlationId) {
    }
}
