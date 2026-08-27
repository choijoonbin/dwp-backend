package com.dwp.services.meeting.videomeeting.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingModels {

    private VideoMeetingModels() {
    }

    public enum LifecycleState {
        DRAFT, SCHEDULED, LOBBY, LIVE, ENDED, CANCELLED
    }

    public enum ParticipantRole {
        ORGANIZER, CO_HOST, PRESENTER, ATTENDEE, GUEST;

        public boolean canHost() {
            return this == ORGANIZER || this == CO_HOST;
        }
    }

    public enum AttendanceState {
        INVITED, REQUESTED, ADMITTED, DENIED, JOINED, LEFT
    }

    public enum AccessScope {
        INTERNAL, INVITED, PUBLIC_CODE
    }

    public record TenantPolicy(
            long tenantId,
            boolean meetingsEnabled,
            boolean waitingRoomRequired,
            boolean guestsAllowed,
            boolean participantChatAllowed,
            boolean reactionsAllowed,
            boolean screenShareAllowed,
            String unmuteControl,
            String recordingPolicy,
            boolean allowJoinBeforeHost,
            boolean requireAuthenticatedInternalUsers,
            int maximumParticipants,
            int retentionDays,
            int artifactRetentionDays,
            int chatRetentionDays,
            long version) {

        public TenantPolicy(
                long tenantId,
                boolean meetingsEnabled,
                boolean waitingRoomRequired,
                boolean guestsAllowed,
                boolean participantChatAllowed,
                boolean reactionsAllowed,
                boolean screenShareAllowed,
                String unmuteControl,
                String recordingPolicy,
                boolean allowJoinBeforeHost,
                boolean requireAuthenticatedInternalUsers,
                int maximumParticipants,
                int retentionDays,
                int artifactRetentionDays,
                long version) {
            this(tenantId, meetingsEnabled, waitingRoomRequired, guestsAllowed,
                    participantChatAllowed, reactionsAllowed, screenShareAllowed,
                    unmuteControl, recordingPolicy, allowJoinBeforeHost,
                    requireAuthenticatedInternalUsers, maximumParticipants,
                    retentionDays, artifactRetentionDays, 90, version);
        }
    }

    public record PersonSnapshot(
            long tenantId,
            long userId,
            UUID personPublicId,
            String emailAddress,
            String displayName,
            String jobTitle,
            String organizationName) {
    }

    public record Meeting(
            UUID meetingId,
            long tenantId,
            String title,
            String description,
            String agenda,
            LifecycleState lifecycleState,
            AccessScope accessScope,
            String joinCode,
            OffsetDateTime scheduledStartAt,
            OffsetDateTime scheduledEndAt,
            String timeZone,
            boolean waitingRoomEnabled,
            boolean guestAccessEnabled,
            boolean allowJoinBeforeHost,
            boolean defaultMicrophoneEnabled,
            boolean defaultCameraEnabled,
            String provider,
            String roomName,
            long organizerUserId,
            UUID organizerPersonPublicId,
            String organizerName,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Long endedBy,
            JsonNode decisions,
            JsonNode followUpActions,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        public boolean live() {
            return lifecycleState == LifecycleState.LIVE;
        }

        public boolean terminal() {
            return lifecycleState == LifecycleState.ENDED
                    || lifecycleState == LifecycleState.CANCELLED;
        }
    }

    public record Participant(
            UUID participantId,
            long tenantId,
            UUID meetingId,
            Long userId,
            UUID personPublicId,
            String emailAddress,
            String displayName,
            String jobTitle,
            String organizationName,
            ParticipantRole participantRole,
            AttendanceState attendanceState,
            boolean canSelfUnmute,
            OffsetDateTime joinRequestedAt,
            OffsetDateTime admittedAt,
            Long admittedBy,
            OffsetDateTime joinedAt,
            OffsetDateTime leftAt,
            OffsetDateTime unmuteRequestedAt,
            Long unmuteRequestedBy,
            long version) {

        public boolean canHost() {
            return participantRole.canHost();
        }

        public boolean admitted() {
            return attendanceState == AttendanceState.ADMITTED
                    || attendanceState == AttendanceState.JOINED
                    || attendanceState == AttendanceState.LEFT;
        }
    }

    public record Artifact(
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            String artifactType,
            String artifactState,
            String contentType,
            Long sizeBytes,
            OffsetDateTime retentionUntil,
            JsonNode metadata,
            long version) {
    }

    public record MeetingDetail(
            Meeting meeting,
            List<Participant> participants,
            List<Artifact> artifacts) {
    }

    public record HomeMetrics(
            int meetingsToday,
            long meetingMinutesToday,
            int waitingForApproval,
            Integer qualityScore,
            Integer averageJoinSeconds) {
    }

    public record MeetingCard(
            Meeting meeting,
            int participantCount,
            ParticipantRole viewerRole) {
    }

    public record HomeProjection(
            List<MeetingCard> live,
            List<MeetingCard> upcoming,
            List<MeetingCard> recent,
            HomeMetrics metrics) {
    }
}
