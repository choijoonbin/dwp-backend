package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository.TranscriptArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;

final class MeetingTranscriptAccessModels {

    private MeetingTranscriptAccessModels() {
    }

    record PreparedQuery(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            Participant participant,
            TranscriptArtifact artifact,
            String correlationId) {
    }
}
