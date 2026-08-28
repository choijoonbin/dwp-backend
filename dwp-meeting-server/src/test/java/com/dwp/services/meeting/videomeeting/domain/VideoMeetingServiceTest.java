package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Artifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingDetail;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.PersonSnapshot;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingServiceTest {

    private static final long TENANT_ID = 77L;
    private static final long USER_ID = 101L;
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 26, 9, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private VideoMeetingRepository repository;
    @Mock
    private MeetingMediaProvider mediaProvider;
    @Mock
    private MeetingJoinCodeGenerator joinCodeGenerator;
    @Mock
    private VideoMeetingLifecycleCoordinator lifecycle;
    @Mock
    private VideoMeetingContentAdmissionGuard contentAdmissionGuard;
    @Mock
    private VideoMeetingAuditRecorder audit;

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void waitingRoomJoinRequestStaysPendingAndUsesTheVerifiedTenant() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LOBBY, 0);
        Participant invited = participant(meetingId, ParticipantRole.ATTENDEE,
                AttendanceState.INVITED, 0);
        Participant requested = participant(meetingId, ParticipantRole.ATTENDEE,
                AttendanceState.REQUESTED, 1);
        MeetingRequestContext.set(subject());
        when(repository.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(repository.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy());
        when(repository.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(invited));
        when(repository.requestJoin(
                TENANT_ID, meetingId, invited.participantId(), false, USER_ID))
                .thenReturn(requested);

        VideoMeetingDtos.JoinRequestResponse response = service().requestJoin(
                meetingId, new VideoMeetingDtos.JoinRequestCommand("박현우"),
                "join-command-001", "corr-join");

        assertThat(response.state()).isEqualTo("WAITING");
        verify(repository).requestJoin(
                TENANT_ID, meetingId, invited.participantId(), false, USER_ID);
        verify(repository).recordEvent(
                eq(meeting), eq(requested), eq(USER_ID), eq("JOIN_REQUESTED"),
                eq("corr-join"), eq("join-command-001"), anyMap());
        verify(audit).participantAccess(
                eq(subject()), eq(meeting), eq(requested), eq("meeting.join.requested"),
                eq("corr-join"), eq("SUCCESS"), anyMap());
    }

    @Test
    void ordinaryAttendeeCannotStartTheMeeting() {
        UUID meetingId = UUID.randomUUID();
        MeetingRequestContext.set(subject());
        when(lifecycle.start(
                eq(meetingId), any(), eq("start-command-001"), eq("corr-start")))
                .thenThrow(new BaseException(
                        ErrorCode.FORBIDDEN, "A meeting host role is required."));

        assertThatThrownBy(() -> service().start(
                meetingId, new VideoMeetingDtos.VersionedCommand(2),
                "start-command-001", "corr-start"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(mediaProvider, never()).ensureRoom(any(), anyInt());
        verify(repository, never()).start(any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void hostStartsAFormalMeetingThroughTheMediaPort() {
        UUID meetingId = UUID.randomUUID();
        Meeting live = meeting(meetingId, LifecycleState.LIVE, 3);
        Participant organizer = participant(
                meetingId, ParticipantRole.ORGANIZER, AttendanceState.ADMITTED, 0);
        MeetingRequestContext.set(subject());
        VideoMeetingDtos.MeetingDetailResponse expected =
                VideoMeetingDtos.MeetingDetailResponse.from(
                        new MeetingDetail(live, List.of(organizer), List.of()),
                        ParticipantRole.ORGANIZER);
        when(lifecycle.start(
                eq(meetingId), any(), eq("start-command-002"), eq("corr-start")))
                .thenReturn(expected);

        VideoMeetingDtos.MeetingDetailResponse response = service().start(
                meetingId, new VideoMeetingDtos.VersionedCommand(2),
                "start-command-002", "corr-start");

        assertThat(response.lifecycleState()).isEqualTo("LIVE");
        verify(lifecycle).start(
                eq(meetingId), any(), eq("start-command-002"), eq("corr-start"));
    }

    @Test
    void schedulingDelegatesCreationWithoutLosingTheLifecycleOrTimeBoundary() {
        UUID meetingId = UUID.randomUUID();
        Meeting scheduled = meeting(meetingId, LifecycleState.SCHEDULED, 0);
        PersonSnapshot organizer = new PersonSnapshot(
                TENANT_ID, USER_ID, subject().personPublicId(),
                "hyunwoo.park@sk.com", "박현우", "Digital Platform 부문장",
                "Digital Platform 부문");
        Participant organizerParticipant = participant(
                meetingId, ParticipantRole.ORGANIZER, AttendanceState.ADMITTED, 0);
        MeetingRequestContext.set(subject());
        when(repository.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy());
        when(repository.byIdempotency(TENANT_ID, USER_ID, "schedule-command-001"))
                .thenReturn(Optional.empty());
        when(repository.person(TENANT_ID, USER_ID)).thenReturn(Optional.of(organizer));
        when(joinCodeGenerator.generate()).thenReturn("7K9M4Q2X8R6T");
        when(repository.joinCodeExists(TENANT_ID, "7K9M4Q2X8R6T")).thenReturn(false);
        when(repository.create(any())).thenReturn(scheduled);
        when(repository.people(TENANT_ID, List.of())).thenReturn(List.of());
        when(repository.detail(scheduled)).thenReturn(
                new MeetingDetail(scheduled, List.of(organizerParticipant), List.of()));

        VideoMeetingDtos.MeetingCreatedResponse response = service().schedule(
                new VideoMeetingDtos.ScheduleMeetingRequest(
                        "Architecture review", "Description", "Agenda", NOW.plusHours(1),
                        60, "Asia/Seoul", AccessScope.INVITED,
                        true, false, false, false, false, List.of(), List.of()),
                "schedule-command-001", "corr-schedule");

        ArgumentCaptor<VideoMeetingRepository.CreateMeeting> command =
                ArgumentCaptor.forClass(VideoMeetingRepository.CreateMeeting.class);
        verify(repository).create(command.capture());
        assertThat(command.getValue().lifecycleState()).isEqualTo("SCHEDULED");
        assertThat(command.getValue().scheduledStartAt()).isEqualTo(NOW.plusHours(1));
        assertThat(command.getValue().scheduledEndAt()).isEqualTo(NOW.plusHours(2));
        assertThat(response.meeting().lifecycleState()).isEqualTo("SCHEDULED");
    }

    @Test
    void participantTokenRequiresAdmissionAndNeverEntersTheAuditPayload() {
        UUID meetingId = UUID.randomUUID();
        Meeting live = meeting(meetingId, LifecycleState.LIVE, 3);
        Participant admitted = participant(
                meetingId, ParticipantRole.ATTENDEE, AttendanceState.ADMITTED, 1);
        MeetingRequestContext.set(subject());
        when(repository.accessibleMeeting(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(live));
        when(repository.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy());
        when(repository.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(admitted));
        when(mediaProvider.capability()).thenReturn(capability());
        when(mediaProvider.issueParticipantToken(
                live, admitted, subject(), effectivePermissions(), NOW))
                .thenReturn(new MeetingMediaProvider.ParticipantToken(
                        "ws://livekit", "secret-participant-token", NOW.plusMinutes(5)));

        VideoMeetingDtos.ParticipantTokenResponse response = service().token(
                meetingId, null, "corr-token");

        assertThat(response.participantToken()).isEqualTo("secret-participant-token");
        assertThat(response.effectivePermissions().screenShare()).isTrue();
        assertThat(response.effectivePermissions().chat()).isTrue();
        verify(contentAdmissionGuard).requireCurrentNoticeAcknowledgement(
                TENANT_ID, meetingId, admitted.participantId());
        verify(repository, never()).markJoined(anyLong(), any(), any(), anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(repository).recordEvent(
                eq(live), eq(admitted), eq(USER_ID), eq("TOKEN_ISSUED"),
                eq("corr-token"), eq(null), payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("expiresAt");
        assertThat(payload.getValue().toString()).doesNotContain("secret-participant-token");
        verify(audit).participantAccess(
                eq(subject()), eq(live), eq(admitted), eq("meeting.media-token.issued"),
                eq("corr-token"), eq("SUCCESS"), eq(payload.getValue()));
    }

    @Test
    void mediaConnectionAndLeaveDriveAttendanceInsteadOfTokenIssuance() {
        UUID meetingId = UUID.randomUUID();
        Meeting live = meeting(meetingId, LifecycleState.LIVE, 3);
        Participant admitted = participant(
                meetingId, ParticipantRole.ATTENDEE, AttendanceState.ADMITTED, 1);
        Participant joined = participant(
                meetingId, ParticipantRole.ATTENDEE, AttendanceState.JOINED, 2);
        Participant left = participant(
                meetingId, ParticipantRole.ATTENDEE, AttendanceState.LEFT, 3);
        MeetingRequestContext.set(subject());
        when(repository.accessibleMeeting(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(live));
        when(repository.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(admitted))
                .thenReturn(Optional.of(joined));
        when(repository.markJoined(
                TENANT_ID, meetingId, admitted.participantId(), USER_ID)).thenReturn(joined);
        when(repository.markLeft(
                TENANT_ID, meetingId, joined.participantId(), USER_ID)).thenReturn(left);

        VideoMeetingDtos.ParticipantResponse connected = service().connected(
                meetingId, "corr-connected");
        VideoMeetingDtos.ParticipantResponse disconnected = service().leave(
                meetingId, "corr-left");

        assertThat(connected.attendanceState()).isEqualTo("JOINED");
        assertThat(disconnected.attendanceState()).isEqualTo("LEFT");
        verify(repository).recordEvent(
                eq(live), eq(joined), eq(USER_ID), eq("PARTICIPANT_JOINED"),
                eq("corr-connected"), eq(null), anyMap());
        verify(repository).recordEvent(
                eq(live), eq(left), eq(USER_ID), eq("PARTICIPANT_LEFT"),
                eq("corr-left"), eq(null), anyMap());
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentMeetingContent() {
        Meeting existing = meeting(UUID.randomUUID(), LifecycleState.LOBBY, 0);
        MeetingRequestContext.set(subject());
        when(repository.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy());
        when(repository.byIdempotency(TENANT_ID, USER_ID, "instant-command-001"))
                .thenReturn(Optional.of(new VideoMeetingRepository.IdempotentMeeting(
                        existing, "0".repeat(64))));

        assertThatThrownBy(() -> service().instant(
                new VideoMeetingDtos.InstantMeetingRequest(
                        "Different meeting", null, null, AccessScope.INVITED,
                        true, false, false, false, false, List.of(), List.of()),
                "instant-command-001", "corr-instant"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(repository, never()).create(any());
    }

    @Test
    void artifactRetentionCannotExceedMeetingRetention() {
        MeetingRequestContext.set(subject());
        VideoMeetingDtos.TenantPolicyUpdateRequest request =
                new VideoMeetingDtos.TenantPolicyUpdateRequest(
                        true, true, true, true, true, true, "NEVER",
                        false, true, 100, 90, 91, 0);

        assertThatThrownBy(() -> service().updatePolicy(
                request, "policy-command-001", "corr-policy"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(repository, never()).updatePolicy(anyLong(), any(), anyLong());
    }

    @Test
    void omittedChatRetentionCannotSilentlyViolateTheExistingRetentionBoundary() {
        MeetingRequestContext.set(subject());
        when(repository.policy(TENANT_ID)).thenReturn(Optional.of(policy()));
        VideoMeetingDtos.TenantPolicyUpdateRequest request =
                new VideoMeetingDtos.TenantPolicyUpdateRequest(
                        true, true, false, true, true, true, "NEVER",
                        false, true, 100, 60, 30, 0);

        assertThatThrownBy(() -> service().updatePolicy(
                request, "policy-command-002", "corr-policy"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(repository, never()).updatePolicy(anyLong(), any(), anyLong());
    }

    @Test
    void invitedMeetingCodeDoesNotRevealMeetingMetadataToANonMember() {
        UUID meetingId = UUID.randomUUID();
        Meeting invitedMeeting = meeting(meetingId, LifecycleState.SCHEDULED, 0);
        BaseException unavailable = new BaseException(
                ErrorCode.ENTITY_NOT_FOUND, "The meeting code is invalid or unavailable.");
        MeetingRequestContext.set(subject());
        when(joinCodeGenerator.normalize("7K9M4Q2X8R6T")).thenReturn("7K9M4Q2X8R6T");
        when(repository.resolveCode(TENANT_ID, "7K9M4Q2X8R6T"))
                .thenReturn(Optional.of(invitedMeeting));
        when(repository.participant(TENANT_ID, meetingId, USER_ID)).thenReturn(Optional.empty());
        when(joinCodeGenerator.invalidCode()).thenReturn(unavailable);

        assertThatThrownBy(() -> service().resolveCode("7K9M4Q2X8R6T"))
                .isSameAs(unavailable);

        verify(repository, never()).detail(any());
    }

    @Test
    void detailAvailabilityComesOnlyFromAvailableGovernedArtifacts() {
        UUID meetingId = UUID.randomUUID();
        Meeting ended = meeting(meetingId, LifecycleState.ENDED, 4);
        Artifact recording = artifact(meetingId, "RECORDING", "AVAILABLE", "video/mp4");
        Artifact transcript = artifact(meetingId, "TRANSCRIPT", "PROCESSING", "text/vtt");
        Artifact summary = artifact(meetingId, "SUMMARY", "AVAILABLE", "application/json");

        VideoMeetingDtos.MeetingDetailResponse response =
                VideoMeetingDtos.MeetingDetailResponse.from(
                        new MeetingDetail(ended, List.of(),
                                List.of(recording, transcript, summary)),
                        ParticipantRole.ORGANIZER);

        assertThat(response.recordingAvailable()).isTrue();
        assertThat(response.transcriptAvailable()).isFalse();
        assertThat(response.aiNotesAvailable()).isTrue();
    }

    @Test
    void attendeeDetailRedactsPrivateProfilesAndUnpublishedRecapContent() {
        UUID meetingId = UUID.randomUUID();
        Meeting live = meeting(meetingId, LifecycleState.LIVE, 3);
        Participant admitted = participant(
                meetingId, ParticipantRole.ATTENDEE, AttendanceState.JOINED, 1);
        Participant requested = participant(
                meetingId, ParticipantRole.GUEST, AttendanceState.REQUESTED, 1);
        Artifact recording = artifact(meetingId, "RECORDING", "AVAILABLE", "video/mp4");

        VideoMeetingDtos.MeetingDetailResponse response =
                VideoMeetingDtos.MeetingDetailResponse.from(
                        new MeetingDetail(live, List.of(admitted, requested), List.of(recording)),
                        ParticipantRole.ATTENDEE, false, false);

        assertThat(response.participants()).hasSize(1);
        VideoMeetingDtos.ParticipantResponse participant = response.participants().getFirst();
        assertThat(participant.displayName()).isEqualTo("박현우");
        assertThat(participant.emailAddress()).isNull();
        assertThat(participant.jobTitle()).isNull();
        assertThat(participant.organizationName()).isNull();
        assertThat(participant.joinedAt()).isNull();
        assertThat(response.artifacts()).isEmpty();
        assertThat(response.recordingAvailable()).isFalse();
        assertThat(response.decisions()).isEmpty();
        assertThat(response.followUpActions()).isEmpty();
    }

    private VideoMeetingService service() {
        return new VideoMeetingService(
                repository, mediaProvider, joinCodeGenerator, lifecycle,
                contentAdmissionGuard, audit,
                Clock.fixed(Instant.parse("2026-08-26T09:00:00Z"), ZoneOffset.UTC));
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                USER_ID, TENANT_ID, UUID.fromString("5af80da3-0dd8-b3bc-2f44-22d90eecaac4"),
                "박현우", Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:VIEW", "APP.MEETINGS:CREATE", "APP.MEETINGS:UPDATE"),
                Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private TenantPolicy policy() {
        return new TenantPolicy(
                TENANT_ID, true, true, true, true, true, true,
                "REQUEST_ONLY", "NEVER", false, true, 100, 1095, 365, 0);
    }

    private MeetingMediaProvider.Capability capability() {
        return new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300);
    }

    private MeetingMediaProvider.EffectivePermissions effectivePermissions() {
        return new MeetingMediaProvider.EffectivePermissions(
                true, true, true, true, true, true, true);
    }

    private Meeting meeting(UUID id, LifecycleState state, long version) {
        boolean liveOrEnded = state == LifecycleState.LIVE || state == LifecycleState.ENDED;
        return new Meeting(
                id, TENANT_ID, "Test meeting", "Description", "Agenda", state,
                AccessScope.INVITED, "7K9M4Q2X8R6T", NOW.plusHours(1),
                NOW.plusHours(2), "Asia/Seoul", true, false,
                false, false, false,
                liveOrEnded ? "LIVEKIT" : null, liveOrEnded ? "formal-room" : null,
                USER_ID, subject().personPublicId(), "박현우",
                liveOrEnded ? NOW : null, state == LifecycleState.ENDED ? NOW.plusHours(1) : null,
                state == LifecycleState.ENDED ? USER_ID : null,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                version, NOW.minusDays(1), NOW);
    }

    private Participant participant(
            UUID meetingId, ParticipantRole role, AttendanceState state, long version) {
        boolean admitted = state == AttendanceState.ADMITTED
                || state == AttendanceState.JOINED || state == AttendanceState.LEFT;
        boolean joined = state == AttendanceState.JOINED || state == AttendanceState.LEFT;
        return new Participant(
                UUID.randomUUID(), TENANT_ID, meetingId, USER_ID, subject().personPublicId(),
                "hyunwoo.park@sk.com", "박현우", "Digital Platform 부문장",
                "Digital Platform 부문", role, state, true,
                state == AttendanceState.REQUESTED ? NOW.minusMinutes(1) : null,
                admitted ? NOW.minusMinutes(2) : null, admitted ? USER_ID : null,
                joined ? NOW : null, state == AttendanceState.LEFT ? NOW.plusMinutes(30) : null,
                null, null, version);
    }

    private Artifact artifact(
            UUID meetingId, String type, String state, String contentType) {
        return new Artifact(
                UUID.randomUUID(), TENANT_ID, meetingId, type, state, contentType,
                128L, NOW.plusDays(30), JsonNodeFactory.instance.objectNode(), 0);
    }
}
