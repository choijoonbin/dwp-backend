package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.PersonSnapshot;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingDtos {

    private VideoMeetingDtos() {
    }

    public record GuestInvitee(
            @Email @Size(max = 255) String emailAddress,
            @NotBlank @Size(max = 160) String displayName) {
    }

    public record InstantMeetingRequest(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 4000) String description,
            @Size(max = 8000) String agenda,
            @NotNull VideoMeetingModels.AccessScope accessScope,
            Boolean waitingRoomEnabled,
            Boolean guestAccessEnabled,
            Boolean allowJoinBeforeHost,
            Boolean defaultMicrophoneEnabled,
            Boolean defaultCameraEnabled,
            @Size(max = 200) List<@Positive Long> participantUserIds,
            @Size(max = 100) List<@Valid GuestInvitee> guestInvitees) {
    }

    public record ScheduleMeetingRequest(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 4000) String description,
            @Size(max = 8000) String agenda,
            @NotNull @Future OffsetDateTime startsAt,
            @Min(5) @Max(1440) int durationMinutes,
            @NotBlank @Size(max = 80) String timeZone,
            @NotNull VideoMeetingModels.AccessScope accessScope,
            Boolean waitingRoomEnabled,
            Boolean guestAccessEnabled,
            Boolean allowJoinBeforeHost,
            Boolean defaultMicrophoneEnabled,
            Boolean defaultCameraEnabled,
            @Size(max = 200) List<@Positive Long> participantUserIds,
            @Size(max = 100) List<@Valid GuestInvitee> guestInvitees) {
    }

    public record JoinRequestCommand(@Size(max = 100) String displayName) {
    }

    public record IssueTokenCommand(UUID joinRequestId) {
    }

    public record VersionedCommand(@PositiveOrZero long expectedVersion) {
    }

    public record AdmissionCommand(@PositiveOrZero long expectedVersion) {
    }

    public record TenantPolicyUpdateRequest(
            boolean meetingsEnabled,
            boolean waitingRoomRequired,
            boolean guestsAllowed,
            boolean participantChatAllowed,
            boolean reactionsAllowed,
            boolean screenShareAllowed,
            @NotBlank @Pattern(regexp = "NEVER") String recordingPolicy,
            boolean allowJoinBeforeHost,
            boolean requireAuthenticatedInternalUsers,
            @Min(2) @Max(1000) int maximumParticipants,
            @Min(1) @Max(3650) int retentionDays,
            @Min(1) @Max(3650) int artifactRetentionDays,
            @Min(0) @Max(365) Integer chatRetentionDays,
            @PositiveOrZero @JsonAlias("version") long expectedVersion) {

        public TenantPolicyUpdateRequest(
                boolean meetingsEnabled,
                boolean waitingRoomRequired,
                boolean guestsAllowed,
                boolean participantChatAllowed,
                boolean reactionsAllowed,
                boolean screenShareAllowed,
                String recordingPolicy,
                boolean allowJoinBeforeHost,
                boolean requireAuthenticatedInternalUsers,
                int maximumParticipants,
                int retentionDays,
                int artifactRetentionDays,
                long expectedVersion) {
            this(meetingsEnabled, waitingRoomRequired, guestsAllowed,
                    participantChatAllowed, reactionsAllowed, screenShareAllowed,
                    recordingPolicy, allowJoinBeforeHost,
                    requireAuthenticatedInternalUsers, maximumParticipants,
                    retentionDays, artifactRetentionDays, null, expectedVersion);
        }
    }

    public record CapabilityResponse(
            boolean available,
            String provider,
            String unavailableReason,
            boolean audio,
            boolean video,
            boolean screenShare,
            boolean participantList,
            boolean chat,
            boolean reactions,
            boolean handRaise,
            boolean captions,
            int maximumParticipants,
            int tokenTtlSeconds,
            String unmuteControl,
            boolean recordingConfigured,
            boolean transcriptConfigured,
            boolean aiNotesConfigured) {

        public static CapabilityResponse from(
                MeetingMediaProvider.Capability capability,
                TenantPolicy policy) {
            boolean available = capability.available() && policy.meetingsEnabled();
            return new CapabilityResponse(
                    available,
                    capability.provider(),
                    policy.meetingsEnabled()
                            ? capability.unavailableReason() : "MEETINGS_DISABLED_BY_POLICY",
                    available && capability.audio(),
                    available && capability.video(),
                    available && capability.screenShare() && policy.screenShareAllowed(),
                    available && capability.participantList(),
                    available && policy.participantChatAllowed(),
                    available && policy.reactionsAllowed(),
                    available && policy.reactionsAllowed(),
                    false,
                    policy.maximumParticipants(),
                    capability.tokenTtlSeconds(),
                    policy.unmuteControl(),
                    false,
                    false,
                    false);
        }
    }

    public record MeetingPersonResponse(
            long userId,
            UUID personPublicId,
            String emailAddress,
            String displayName,
            String jobTitle,
            String organizationName) {

        public static MeetingPersonResponse from(PersonSnapshot person) {
            return new MeetingPersonResponse(
                    person.userId(), person.personPublicId(), person.emailAddress(),
                    person.displayName(), person.jobTitle(), person.organizationName());
        }
    }

    public record PolicyResponse(
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
            boolean recordingConfigured,
            boolean aiNotesConfigured,
            long version) {

        public static PolicyResponse from(VideoMeetingModels.TenantPolicy policy) {
            return new PolicyResponse(
                    policy.meetingsEnabled(), policy.waitingRoomRequired(),
                    policy.guestsAllowed(), policy.participantChatAllowed(),
                    policy.reactionsAllowed(), policy.screenShareAllowed(),
                    policy.unmuteControl(), policy.recordingPolicy(),
                    policy.allowJoinBeforeHost(), policy.requireAuthenticatedInternalUsers(),
                    policy.maximumParticipants(), policy.retentionDays(),
                    policy.artifactRetentionDays(), policy.chatRetentionDays(),
                    false, false, policy.version());
        }
    }

    public record MeetingSummary(
            UUID meetingId,
            String title,
            String description,
            String agenda,
            String lifecycleState,
            String accessScope,
            String meetingCode,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int durationMinutes,
            String timeZone,
            long organizerUserId,
            String organizerName,
            boolean waitingRoomEnabled,
            boolean allowJoinBeforeHost,
            boolean defaultMicrophoneEnabled,
            boolean defaultCameraEnabled,
            int attendeeCount,
            String participantRole,
            boolean canHost,
            boolean canModerate,
            long version) {
    }

    public record HomeMetricsResponse(
            int meetingsToday,
            long meetingMinutesToday,
            int waitingForApproval,
            Integer qualityScore,
            Integer averageJoinSeconds) {

        public static HomeMetricsResponse from(VideoMeetingModels.HomeMetrics metrics) {
            return new HomeMetricsResponse(
                    metrics.meetingsToday(), metrics.meetingMinutesToday(),
                    metrics.waitingForApproval(), metrics.qualityScore(),
                    metrics.averageJoinSeconds());
        }
    }

    public record HomeResponse(
            OffsetDateTime serverNow,
            String timeZone,
            CapabilityResponse capabilities,
            MeetingSummary activeMeeting,
            MeetingSummary nextMeeting,
            List<MeetingSummary> today,
            List<MeetingSummary> recent,
            HomeMetricsResponse metrics) {
    }

    public record ParticipantResponse(
            UUID participantId,
            Long userId,
            UUID personPublicId,
            String emailAddress,
            String displayName,
            String jobTitle,
            String organizationName,
            String participantRole,
            String attendanceState,
            boolean canSelfUnmute,
            OffsetDateTime joinRequestedAt,
            OffsetDateTime admittedAt,
            OffsetDateTime joinedAt,
            OffsetDateTime leftAt,
            OffsetDateTime unmuteRequestedAt,
            long version) {

        public static ParticipantResponse from(VideoMeetingModels.Participant participant) {
            return from(participant, true);
        }

        public static ParticipantResponse from(
                VideoMeetingModels.Participant participant,
                boolean includePrivateProfile) {
            return new ParticipantResponse(
                    participant.participantId(),
                    includePrivateProfile ? participant.userId() : null,
                    includePrivateProfile ? participant.personPublicId() : null,
                    includePrivateProfile ? participant.emailAddress() : null,
                    participant.displayName(),
                    includePrivateProfile ? participant.jobTitle() : null,
                    includePrivateProfile ? participant.organizationName() : null,
                    participant.participantRole().name(),
                    participant.attendanceState().name(), participant.canSelfUnmute(),
                    includePrivateProfile ? participant.joinRequestedAt() : null,
                    includePrivateProfile ? participant.admittedAt() : null,
                    includePrivateProfile ? participant.joinedAt() : null,
                    includePrivateProfile ? participant.leftAt() : null,
                    includePrivateProfile ? participant.unmuteRequestedAt() : null,
                    participant.version());
        }
    }

    public record JoinRequestResponse(
            UUID requestId,
            String state,
            String displayName,
            String email,
            String organizationName,
            boolean external,
            OffsetDateTime requestedAt,
            long version) {

        public static JoinRequestResponse from(VideoMeetingModels.Participant participant) {
            return new JoinRequestResponse(
                    participant.participantId(), joinState(participant.attendanceState()),
                    participant.displayName(), participant.emailAddress(),
                    participant.organizationName(),
                    participant.participantRole() == VideoMeetingModels.ParticipantRole.GUEST,
                    participant.joinRequestedAt(), participant.version());
        }

        private static String joinState(VideoMeetingModels.AttendanceState state) {
            return switch (state) {
                case REQUESTED, INVITED -> "WAITING";
                case ADMITTED, JOINED, LEFT -> "APPROVED";
                case DENIED -> "DENIED";
            };
        }
    }

    public record LobbyResponse(List<JoinRequestResponse> waiting) {
    }

    public record ArtifactResponse(
            UUID artifactId,
            String artifactType,
            String artifactState,
            String contentType,
            Long sizeBytes,
            OffsetDateTime retentionUntil,
            JsonNode metadata,
            long version) {

        public static ArtifactResponse from(VideoMeetingModels.Artifact artifact) {
            return new ArtifactResponse(
                    artifact.artifactId(), artifact.artifactType(), artifact.artifactState(),
                    artifact.contentType(), artifact.sizeBytes(), artifact.retentionUntil(),
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                    artifact.version());
        }
    }

    public record MeetingDetailResponse(
            UUID meetingId,
            String title,
            String description,
            String agenda,
            String lifecycleState,
            String accessScope,
            String meetingCode,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int durationMinutes,
            String timeZone,
            boolean waitingRoomEnabled,
            boolean guestAccessEnabled,
            boolean allowJoinBeforeHost,
            boolean defaultMicrophoneEnabled,
            boolean defaultCameraEnabled,
            String provider,
            long organizerUserId,
            UUID organizerPersonPublicId,
            String organizerName,
            String participantRole,
            boolean canHost,
            boolean canModerate,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            JsonNode decisions,
            JsonNode followUpActions,
            List<ParticipantResponse> participants,
            List<ArtifactResponse> artifacts,
            boolean recordingAvailable,
            boolean transcriptAvailable,
            boolean aiNotesAvailable,
            long version) {

        public static MeetingDetailResponse from(
                VideoMeetingModels.MeetingDetail detail,
                VideoMeetingModels.ParticipantRole viewerRole) {
            return from(detail, viewerRole, true, true);
        }

        public static MeetingDetailResponse from(
                VideoMeetingModels.MeetingDetail detail,
                VideoMeetingModels.ParticipantRole viewerRole,
                boolean contentVisible,
                boolean participantAdministrationVisible) {
            VideoMeetingModels.Meeting meeting = detail.meeting();
            VideoMeetingModels.ParticipantRole effectiveRole = effectiveRole(viewerRole);
            List<VideoMeetingModels.Participant> visibleParticipants =
                    participantAdministrationVisible
                            ? detail.participants()
                            : detail.participants().stream()
                                    .filter(VideoMeetingModels.Participant::admitted)
                                    .toList();
            List<VideoMeetingModels.Artifact> visibleArtifacts = contentVisible
                    ? detail.artifacts() : List.of();
            return new MeetingDetailResponse(
                    meeting.meetingId(), meeting.title(), meeting.description(), meeting.agenda(),
                    meeting.lifecycleState().name(), meeting.accessScope().name(),
                    VideoMeetingDtos.meetingCode(meeting.joinCode()),
                    meeting.scheduledStartAt(),
                    meeting.scheduledEndAt(),
                    VideoMeetingDtos.durationMinutes(meeting), meeting.timeZone(),
                    meeting.waitingRoomEnabled(),
                    meeting.guestAccessEnabled(), meeting.allowJoinBeforeHost(),
                    meeting.defaultMicrophoneEnabled(), meeting.defaultCameraEnabled(),
                    meeting.provider(), meeting.organizerUserId(),
                    meeting.organizerPersonPublicId(), meeting.organizerName(),
                    effectiveRole.name(), effectiveRole.canHost(), effectiveRole.canHost(),
                    meeting.startedAt(), meeting.endedAt(),
                    contentVisible ? meeting.decisions() : emptyArray(),
                    contentVisible ? meeting.followUpActions() : emptyArray(),
                    visibleParticipants.stream()
                            .map(participant -> ParticipantResponse.from(
                                    participant, participantAdministrationVisible))
                            .toList(),
                    visibleArtifacts.stream().map(ArtifactResponse::from).toList(),
                    artifactAvailable(visibleArtifacts, "RECORDING"),
                    artifactAvailable(visibleArtifacts, "TRANSCRIPT"),
                    artifactAvailable(visibleArtifacts, "SUMMARY"),
                    meeting.version());
        }

        private static JsonNode emptyArray() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        }

        private static boolean artifactAvailable(
                List<VideoMeetingModels.Artifact> artifacts,
                String artifactType) {
            return artifacts.stream().anyMatch(artifact ->
                    artifactType.equals(artifact.artifactType())
                            && "AVAILABLE".equals(artifact.artifactState()));
        }
    }

    public record MeetingCreatedResponse(MeetingDetailResponse meeting, String meetingCode) {
    }

    public record JoinCodeResolutionResponse(
            MeetingSummary meeting,
            boolean joinAllowed,
            String denialReason,
            boolean waitingRoomRequired) {
    }

    public record ParticipantTokenResponse(
            UUID meetingId,
            String sessionId,
            String provider,
            String serverUrl,
            String participantToken,
            String participantRole,
            OffsetDateTime expiresAt,
            EffectivePermissionsResponse effectivePermissions) {
    }

    public record EffectivePermissionsResponse(
            boolean microphone,
            boolean camera,
            boolean screenShare,
            boolean participantList,
            boolean chat,
            boolean reactions,
            boolean handRaise) {

        public static EffectivePermissionsResponse from(
                MeetingMediaProvider.EffectivePermissions permissions) {
            return new EffectivePermissionsResponse(
                    permissions.microphone(), permissions.camera(),
                    permissions.screenShare(), permissions.participantList(),
                    permissions.chat(), permissions.reactions(), permissions.handRaise());
        }
    }

    public record PageResponse<T>(List<T> items, long total, int page, int pageSize) {
    }

    public record HistoryItemResponse(
            UUID meetingId,
            String title,
            OffsetDateTime endedAt,
            int actualDurationMinutes,
            int participantPeak,
            Integer averageQualityScore,
            boolean recordingAvailable,
            boolean transcriptAvailable) {
    }

    public record AdminCapabilitiesResponse(
            boolean video,
            boolean screenShare,
            boolean chat,
            boolean captions,
            boolean recordingConfigured,
            boolean transcriptConfigured,
            boolean aiNotesConfigured) {
    }

    public record AdminOverviewResponse(
            int liveMeetings,
            int scheduledToday,
            int waitingParticipants,
            int meetingsLastSevenDays,
            Integer averageQualityScore,
            int failedJoinAttempts,
            AdminCapabilitiesResponse capabilities) {
    }

    public static MeetingSummary summary(VideoMeetingModels.MeetingCard card) {
        return summary(card.meeting(), card.participantCount(), card.viewerRole());
    }

    public static MeetingSummary summary(
            VideoMeetingModels.Meeting meeting,
            int participantCount,
            VideoMeetingModels.ParticipantRole viewerRole) {
        VideoMeetingModels.ParticipantRole effectiveRole = effectiveRole(viewerRole);
        return new MeetingSummary(
                meeting.meetingId(), meeting.title(), meeting.description(), meeting.agenda(),
                meeting.lifecycleState().name(), meeting.accessScope().name(),
                meetingCode(meeting.joinCode()),
                meeting.scheduledStartAt(), meeting.scheduledEndAt(), durationMinutes(meeting),
                meeting.timeZone(), meeting.organizerUserId(), meeting.organizerName(),
                meeting.waitingRoomEnabled(), meeting.allowJoinBeforeHost(),
                meeting.defaultMicrophoneEnabled(), meeting.defaultCameraEnabled(),
                participantCount, effectiveRole.name(), effectiveRole.canHost(),
                effectiveRole.canHost(), meeting.version());
    }

    public static HistoryItemResponse history(VideoMeetingModels.MeetingCard card) {
        VideoMeetingModels.Meeting meeting = card.meeting();
        int actualDuration = meeting.startedAt() == null || meeting.endedAt() == null
                ? 0 : safeMinutes(Duration.between(meeting.startedAt(), meeting.endedAt()));
        return new HistoryItemResponse(
                meeting.meetingId(), meeting.title(), meeting.endedAt(), actualDuration,
                card.participantCount(), null, false, false);
    }

    private static VideoMeetingModels.ParticipantRole effectiveRole(
            VideoMeetingModels.ParticipantRole viewerRole) {
        return viewerRole == null ? VideoMeetingModels.ParticipantRole.ATTENDEE : viewerRole;
    }

    private static int durationMinutes(VideoMeetingModels.Meeting meeting) {
        if (meeting.scheduledStartAt() == null || meeting.scheduledEndAt() == null) return 0;
        return safeMinutes(Duration.between(
                meeting.scheduledStartAt(), meeting.scheduledEndAt()));
    }

    private static int safeMinutes(Duration duration) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, duration.toMinutes()));
    }

    public static String meetingCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return rawCode;
        StringBuilder formatted = new StringBuilder(rawCode.length() + 3);
        for (int index = 0; index < rawCode.length(); index++) {
            if (index > 0 && index % 4 == 0) formatted.append('-');
            formatted.append(rawCode.charAt(index));
        }
        return formatted.toString();
    }
}
