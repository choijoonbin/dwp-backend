package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Media-plane boundary. Scheduling, admission, policy, and audit remain in Meeting Core. */
public interface MeetingMediaProvider {

    Capability capability();

    PreparedRoom prepareRoom(UUID meetingId, long tenantId, int maximumParticipants);

    ParticipantToken issueParticipantToken(
            Meeting meeting,
            Participant participant,
            MeetingRequestContext.Subject subject,
            EffectivePermissions permissions,
            OffsetDateTime issuedAt);

    void endRoom(Meeting meeting);

    record Capability(
            boolean available,
            String provider,
            String unavailableReason,
            boolean audio,
            boolean video,
            boolean screenShare,
            boolean participantList,
            int tokenTtlSeconds) {
    }

    record PreparedRoom(String provider, String roomName) {
    }

    record ParticipantToken(String serverUrl, String token, OffsetDateTime expiresAt) {
    }

    record EffectivePermissions(
            boolean microphone,
            boolean camera,
            boolean screenShare,
            boolean participantList,
            boolean chat,
            boolean reactions,
            boolean handRaise) {

        public boolean canPublishMedia() {
            return microphone || camera || screenShare;
        }

        public boolean canPublishData() {
            return chat || reactions || handRaise;
        }
    }
}
