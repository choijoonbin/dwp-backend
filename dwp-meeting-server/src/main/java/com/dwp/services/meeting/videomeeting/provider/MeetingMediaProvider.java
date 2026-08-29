package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Media-plane boundary. Scheduling, admission, policy, and audit remain in Meeting Core. */
public interface MeetingMediaProvider {

    Capability capability();

    /**
     * Performs a bounded provider control-plane probe for explicit readiness checks.
     * This must not be called on ordinary meeting request paths.
     */
    default boolean operationallyReady() {
        return false;
    }

    /** Pure, deterministic room identity calculation. This method must never perform I/O. */
    PreparedRoom planRoom(UUID meetingId, long tenantId);

    /**
     * Pure room identity calculation for a server-issued media-session incarnation.
     * Implementations that do not support incarnations retain their previous behaviour.
     */
    default PreparedRoom planRoom(UUID meetingId, long tenantId, UUID incarnation) {
        PreparedRoom planned = planRoom(meetingId, tenantId);
        return new PreparedRoom(
                planned.provider(), planned.roomName(), tenantId, meetingId, incarnation);
    }

    /** Idempotently creates or adopts the planned room. This is the provider I/O boundary. */
    void ensureRoom(PreparedRoom room, int maximumParticipants);

    ParticipantToken issueParticipantToken(
            Meeting meeting,
            Participant participant,
            MeetingRequestContext.Subject subject,
            EffectivePermissions permissions,
            OffsetDateTime issuedAt);

    /** Issues a token bound to the current server-authoritative room incarnation. */
    default ParticipantToken issueParticipantToken(
            Meeting meeting,
            Participant participant,
            MeetingRequestContext.Subject subject,
            EffectivePermissions permissions,
            OffsetDateTime issuedAt,
            UUID roomIncarnation) {
        return issueParticipantToken(meeting, participant, subject, permissions, issuedAt);
    }

    /** Idempotently terminates the named room; an already absent room is successful. */
    void endRoom(String roomName);

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

    record PreparedRoom(
            String provider,
            String roomName,
            long tenantId,
            UUID meetingId,
            UUID incarnation) {

        /** Compatibility constructor for callers that only terminate an already-known room. */
        public PreparedRoom(String provider, String roomName) {
            this(provider, roomName, 0L, null, null);
        }

        /** Canonical, content-free metadata persisted by the media provider. */
        public String roomMetadata() {
            if (tenantId <= 0 || meetingId == null || incarnation == null) return null;
            return "{\"schemaVersion\":1,\"tenantId\":" + tenantId
                    + ",\"meetingId\":\"" + meetingId
                    + "\",\"roomIncarnation\":\"" + incarnation + "\"}";
        }
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
