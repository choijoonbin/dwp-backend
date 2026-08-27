package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingContentDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.BlockerCode;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ConsentCounts;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.NoticeAcknowledgement;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.NoticeState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.PlanState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingSession;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingContentServiceTest {

    private static final long TENANT_ID = 77L;
    private static final long USER_ID = 101L;
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 27, 8, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private VideoMeetingRepository meetings;
    @Mock
    private VideoMeetingContentRepository content;
    @Mock
    private MeetingMediaProvider mediaProvider;
    @Mock
    private MeetingContentDependencies dependencies;
    @Mock
    private VideoMeetingAuditRecorder audit;

    @BeforeEach
    void setContext() {
        MeetingRequestContext.set(subject(USER_ID));
    }

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void nonMemberCannotReadAnInternalMeetingContentPlan() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        when(meetings.accessibleMeeting(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(meeting));
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().contentPlan(meetingId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(content, never()).ensurePlan(anyLong(), any(), anyLong());
    }

    @Test
    void defaultDependencyBoundaryFailsClosedForEveryExternalSystem() {
        MeetingContentDependencies.Status status =
                MeetingContentDependencies.failClosedStatus();

        assertThat(status.egressAvailable()).isFalse();
        assertThat(status.storageAvailable()).isFalse();
        assertThat(status.kmsAvailable()).isFalse();
        assertThat(status.speechToTextAvailable()).isFalse();
        assertThat(status.languageModelAvailable()).isFalse();
        assertThat(status.auditAvailable()).isFalse();
    }

    @Test
    void attendeeCannotChangeTheServerAuthoritativeContentPlan() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant attendee = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        lockAs(meeting, attendee);

        assertThatThrownBy(() -> service().updateContentPlan(
                meetingId, new VideoMeetingContentDtos.UpdateContentPlanCommand(
                        true, true, true, false, 0),
                "content-plan-0001", null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(content, never()).ensurePlan(anyLong(), any(), anyLong());
    }

    @Test
    void planUpdatePublishesANewNoticeButStaysBlockedWithoutCapabilities() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        ContentPlan current = disabledPlan(meetingId);
        ContentNotice notice = notice(meetingId, 1);
        ContentPlan updated = requestedPlan(meetingId, notice.noticeId(), false);
        lockAs(meeting, host);
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy("NEVER"));
        when(content.ensurePlan(TENANT_ID, meetingId, USER_ID)).thenReturn(current);
        when(content.activeSession(TENANT_ID, meetingId)).thenReturn(Optional.empty());
        when(dependencies.status()).thenReturn(unavailableDependencies());
        when(mediaProvider.capability()).thenReturn(unavailableMedia());
        when(content.updatePlan(
                eq(current), eq(true), eq(true), eq(true), eq(false),
                eq(PlanState.BLOCKED), any(UUID.class), eq(1), eq(USER_ID), eq(NOW)))
                .thenReturn(updated);
        when(content.currentNotice(TENANT_ID, meetingId)).thenReturn(Optional.of(notice));
        when(content.consentCounts(TENANT_ID, meetingId, notice.noticeId()))
                .thenReturn(new ConsentCounts(1, 0));
        when(content.acknowledgedBy(
                TENANT_ID, meetingId, notice.noticeId(), host.participantId()))
                .thenReturn(false);

        VideoMeetingContentDtos.ContentPlanResponse response = service().updateContentPlan(
                meetingId, new VideoMeetingContentDtos.UpdateContentPlanCommand(
                        true, true, true, false, 0),
                "content-plan-0002", "corr-plan");

        assertThat(response.state()).isEqualTo("BLOCKED");
        assertThat(codes(response.blockers())).contains(
                "POLICY_NEVER", "MEDIA_PROVIDER", "AUDIT", "EGRESS", "STORAGE", "KMS",
                "STT", "LLM", "CONSENT");
        verify(content).saveCommand(
                eq(TENANT_ID), eq(meetingId), eq(USER_ID), eq("PLAN_UPDATE"),
                eq("content-plan-0002"), anyString(), eq(true), eq(200),
                eq(List.of()), eq(updated.planId()), eq(updated.version()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditState = ArgumentCaptor.forClass(Map.class);
        verify(audit).collaboration(
                eq(subject(USER_ID)), eq(meeting), eq("meeting.content-plan.updated"),
                eq("MEETING_CONTENT_PLAN"), eq(updated.planId().toString()),
                eq("corr-plan"), eq(true), auditState.capture());
        assertThat(auditState.getValue()).containsOnlyKeys(
                "planVersion", "planState", "recordingRequested",
                "transcriptionRequested", "aiSummaryRequested", "e2eeEnabled",
                "noticeRevision");
    }

    @Test
    void policyNeverBlocksRecordingWith409AndCreatesNoSession() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        ContentPlan plan = requestedPlan(meetingId, UUID.randomUUID(), false);
        prepareRecordingRequest(meeting, host, plan, policy("NEVER"), readyDependencies());

        VideoMeetingContentDtos.RecordingCommandResult result = service().requestRecording(
                meetingId, new VideoMeetingContentDtos.RequestRecordingCommand(plan.version()),
                "recording-request-0001", "corr-policy");

        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.accepted()).isFalse();
        assertThat(codes(result.response().blockers())).containsExactly("POLICY_NEVER");
        verify(content, never()).requestRecording(any(), any(), anyLong(), any());
    }

    @Test
    void unavailableDependenciesReturn503WithoutCreatingAnyProcessingState() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        ContentPlan plan = requestedPlan(meetingId, UUID.randomUUID(), false);
        prepareRecordingRequest(
                meeting, host, plan, policy("HOST_OPT_IN"),
                new MeetingContentDependencies.Status(false, false, false, false, false, false));

        VideoMeetingContentDtos.RecordingCommandResult result = service().requestRecording(
                meetingId, new VideoMeetingContentDtos.RequestRecordingCommand(plan.version()),
                "recording-request-0002", "corr-deps");

        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(result.response().recordingSession()).isNull();
        assertThat(codes(result.response().blockers())).containsExactly(
                "AUDIT", "EGRESS", "STORAGE", "KMS", "STT", "LLM");
        verify(content, never()).requestRecording(any(), any(), anyLong(), any());
        verify(content).saveCommand(
                eq(TENANT_ID), eq(meetingId), eq(USER_ID), eq("RECORDING_REQUEST"),
                eq("recording-request-0002"), anyString(), eq(false), eq(503),
                anyList(), eq(null), eq(plan.version()));
    }

    @Test
    void e2eeAndMissingConsentBlockRecordingEvenWhenDependenciesAreReady() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        ContentPlan plan = requestedPlan(meetingId, UUID.randomUUID(), true);
        prepareRecordingRequest(
                meeting, host, plan, policy("HOST_OPT_IN"), readyDependencies());
        when(content.consentCounts(TENANT_ID, meetingId, plan.currentNoticeId()))
                .thenReturn(new ConsentCounts(2, 1));

        VideoMeetingContentDtos.RecordingCommandResult result = service().requestRecording(
                meetingId, new VideoMeetingContentDtos.RequestRecordingCommand(plan.version()),
                "recording-request-0003", null);

        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(codes(result.response().blockers())).containsExactly("E2EE", "CONSENT");
        verify(content, never()).requestRecording(any(), any(), anyLong(), any());
    }

    @Test
    void readyRequestCreatesOnlyARequestedControlSession() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        ContentPlan plan = requestedPlan(meetingId, UUID.randomUUID(), false);
        ContentNotice notice = notice(meetingId, 1, plan.currentNoticeId());
        RecordingSession session = session(meetingId, plan, notice, RecordingState.REQUESTED, 0);
        prepareRecordingRequest(
                meeting, host, plan, policy("HOST_OPT_IN"), readyDependencies());
        when(content.currentNotice(TENANT_ID, meetingId)).thenReturn(Optional.of(notice));
        when(content.requestRecording(plan, notice, USER_ID, NOW)).thenReturn(session);

        VideoMeetingContentDtos.RecordingCommandResult result = service().requestRecording(
                meetingId, new VideoMeetingContentDtos.RequestRecordingCommand(plan.version()),
                "recording-request-0004", "corr-ready");

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.accepted()).isTrue();
        assertThat(result.response().commandState()).isEqualTo("REQUESTED");
        assertThat(result.response().recordingSession().state()).isEqualTo("REQUESTED");
        assertThat(result.response().recordingSession().state())
                .isNotIn("PROCESSING", "AVAILABLE");
        verify(content).requestRecording(plan, notice, USER_ID, NOW);
    }

    @Test
    void blockedCommandRetryUsesTheStoredReceiptWithoutReevaluation() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        ContentPlan plan = requestedPlan(meetingId, UUID.randomUUID(), false);
        lockAs(meeting, host);
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy("HOST_OPT_IN"));
        when(content.ensurePlan(TENANT_ID, meetingId, USER_ID)).thenReturn(plan);
        String hash = VideoMeetingCommandPolicy.requestHash(meetingId, plan.version());
        when(content.command(
                TENANT_ID, meetingId, USER_ID, "RECORDING_REQUEST", "recording-retry-01"))
                .thenReturn(Optional.of(new StoredCommand(
                        hash, false, 503, List.of(BlockerCode.EGRESS), null, plan.version())));

        VideoMeetingContentDtos.RecordingCommandResult result = service().requestRecording(
                meetingId, new VideoMeetingContentDtos.RequestRecordingCommand(plan.version()),
                "recording-retry-01", null);

        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(codes(result.response().blockers())).containsExactly("EGRESS");
        verify(dependencies, never()).status();
        verify(content, never()).saveCommand(
                anyLong(), any(), anyLong(), anyString(), anyString(), anyString(),
                anyBoolean(), anyInt(), anyList(), any(), anyLong());
        verify(audit, never()).collaboration(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyMap());
    }

    @Test
    void noticeAcknowledgementIsIdempotentAndAuditContainsNoMeetingContent() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant participant = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        ContentNotice notice = notice(meetingId, 2);
        ContentPlan plan = requestedPlan(meetingId, notice.noticeId(), false);
        NoticeAcknowledgement acknowledgement = new NoticeAcknowledgement(
                UUID.randomUUID(), notice.noticeId(), participant.participantId(), NOW);
        lockAs(meeting, participant);
        when(content.ensurePlan(TENANT_ID, meetingId, USER_ID)).thenReturn(plan);
        when(content.currentNotice(TENANT_ID, meetingId)).thenReturn(Optional.of(notice));
        when(content.acknowledge(
                TENANT_ID, meetingId, notice.noticeId(), participant.participantId(),
                USER_ID, NOW)).thenReturn(acknowledgement);

        VideoMeetingContentDtos.NoticeAcknowledgementResponse response =
                service().acknowledgeNotice(
                        meetingId, notice.noticeId(), "notice-ack-0001", "corr-ack");

        assertThat(response.acknowledgementId()).isEqualTo(acknowledgement.acknowledgementId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> auditState = ArgumentCaptor.forClass(Map.class);
        verify(audit).collaboration(
                eq(subject(USER_ID)), eq(meeting),
                eq("meeting.content-notice.acknowledged"),
                eq("MEETING_CONTENT_NOTICE"), eq(notice.noticeId().toString()),
                eq("corr-ack"), eq(false), auditState.capture());
        assertThat(auditState.getValue()).containsOnlyKeys("noticeRevision", "participantId");
    }

    @Test
    void noticeRetryReturnsTheOriginalAcknowledgementAfterNoticeSupersession() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant participant = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        ContentNotice originalNotice = notice(meetingId, 1);
        ContentNotice currentNotice = notice(meetingId, 2);
        ContentPlan plan = requestedPlan(meetingId, currentNotice.noticeId(), false);
        NoticeAcknowledgement acknowledgement = new NoticeAcknowledgement(
                UUID.randomUUID(), originalNotice.noticeId(), participant.participantId(),
                NOW.minusMinutes(2));
        lockAs(meeting, participant);
        when(content.ensurePlan(TENANT_ID, meetingId, USER_ID)).thenReturn(plan);
        String hash = VideoMeetingCommandPolicy.requestHash(
                meetingId, originalNotice.noticeId(), participant.participantId());
        when(content.command(
                TENANT_ID, meetingId, USER_ID, "NOTICE_ACK", "notice-ack-retry"))
                .thenReturn(Optional.of(new StoredCommand(
                        hash, true, 200, List.of(),
                        acknowledgement.acknowledgementId(), plan.version())));
        when(content.acknowledgement(
                TENANT_ID, meetingId, acknowledgement.acknowledgementId()))
                .thenReturn(Optional.of(acknowledgement));
        when(content.notice(TENANT_ID, meetingId, originalNotice.noticeId()))
                .thenReturn(Optional.of(originalNotice));

        VideoMeetingContentDtos.NoticeAcknowledgementResponse response =
                service().acknowledgeNotice(
                        meetingId, originalNotice.noticeId(), "notice-ack-retry", null);

        assertThat(response.noticeId()).isEqualTo(originalNotice.noticeId());
        assertThat(response.noticeRevision()).isEqualTo(1);
        assertThat(response.acknowledgedAt()).isEqualTo(acknowledgement.acknowledgedAt());
        verify(content, never()).acknowledge(
                anyLong(), any(), any(), any(), anyLong(), any());
        verify(audit, never()).collaboration(
                any(), any(), any(), any(), any(), any(), anyBoolean(), anyMap());
    }

    @Test
    void stopIntentBecomesStopRequestedButReturns503WhenEgressIsUnavailable() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        ContentPlan plan = requestedPlan(meetingId, UUID.randomUUID(), false);
        ContentNotice notice = notice(meetingId, 1, plan.currentNoticeId());
        RecordingSession recording = session(
                meetingId, plan, notice, RecordingState.RECORDING, 2);
        RecordingSession stopRequested = new RecordingSession(
                recording.recordingSessionId(), TENANT_ID, meetingId, plan.version(),
                notice.noticeId(), RecordingState.STOP_REQUESTED, recording.requestedAt(),
                USER_ID, NOW, USER_ID, recording.startedAt(), null, null, null, 3);
        lockAs(meeting, host);
        when(content.ensurePlan(TENANT_ID, meetingId, USER_ID)).thenReturn(plan);
        when(content.activeSession(TENANT_ID, meetingId)).thenReturn(Optional.of(recording));
        when(content.requestStop(recording, USER_ID, NOW)).thenReturn(stopRequested);
        when(dependencies.status()).thenReturn(unavailableDependencies());

        VideoMeetingContentDtos.RecordingCommandResult result = service().stopRecording(
                meetingId, new VideoMeetingContentDtos.StopRecordingCommand(2),
                "recording-stop-0001", "corr-stop");

        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(result.accepted()).isFalse();
        assertThat(result.response().commandState()).isEqualTo("STOP_REQUESTED");
        assertThat(codes(result.response().blockers())).containsExactly("EGRESS", "AUDIT");
        verify(content).requestStop(recording, USER_ID, NOW);
    }

    private void prepareRecordingRequest(
            Meeting meeting,
            Participant host,
            ContentPlan plan,
            TenantPolicy policy,
            MeetingContentDependencies.Status status) {
        lockAs(meeting, host);
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy);
        when(content.ensurePlan(TENANT_ID, meeting.meetingId(), USER_ID)).thenReturn(plan);
        when(content.activeSession(TENANT_ID, meeting.meetingId())).thenReturn(Optional.empty());
        when(content.consentCounts(TENANT_ID, meeting.meetingId(), plan.currentNoticeId()))
                .thenReturn(new ConsentCounts(1, 1));
        when(dependencies.status()).thenReturn(status);
        when(mediaProvider.capability()).thenReturn(availableMedia());
    }

    private void lockAs(Meeting meeting, Participant participant) {
        when(meetings.lockMeeting(TENANT_ID, meeting.meetingId())).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meeting.meetingId(), USER_ID))
                .thenReturn(Optional.of(participant));
    }

    private VideoMeetingContentService service() {
        return new VideoMeetingContentService(
                meetings, content, mediaProvider, dependencies, audit,
                Clock.fixed(Instant.parse("2026-08-27T08:00:00Z"), ZoneOffset.UTC));
    }

    private MeetingRequestContext.Subject subject(long userId) {
        return new MeetingRequestContext.Subject(
                userId, TENANT_ID, UUID.nameUUIDFromBytes(("person-" + userId).getBytes()),
                "User " + userId, Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private TenantPolicy policy(String recordingPolicy) {
        return new TenantPolicy(
                TENANT_ID, true, true, false, true, true, true,
                "REQUEST_ONLY", recordingPolicy, false, true, 100,
                1095, 365, 90, 0);
    }

    private Meeting meeting(UUID meetingId, LifecycleState state) {
        return new Meeting(
                meetingId, TENANT_ID, "Quarterly review", null, null, state,
                AccessScope.INTERNAL, "7K9M4Q2X8R6T", NOW.minusHours(1), NOW.plusHours(1),
                "Asia/Seoul", true, false, false, false, false,
                state == LifecycleState.LIVE ? "LIVEKIT" : null,
                state == LifecycleState.LIVE ? "room" : null,
                USER_ID, subject(USER_ID).personPublicId(), "Organizer",
                state == LifecycleState.LIVE ? NOW.minusHours(1) : null,
                null, null, JsonNodeFactory.instance.arrayNode(),
                JsonNodeFactory.instance.arrayNode(), 1, NOW.minusDays(1), NOW);
    }

    private Participant participant(UUID meetingId, long userId, ParticipantRole role) {
        return new Participant(
                UUID.nameUUIDFromBytes((meetingId + ":" + userId).getBytes()),
                TENANT_ID, meetingId, userId, subject(userId).personPublicId(),
                "user" + userId + "@sk.com", "User " + userId, null, null,
                role, AttendanceState.JOINED, true, NOW.minusMinutes(5),
                NOW.minusMinutes(4), USER_ID, NOW.minusMinutes(3), null,
                null, null, 1);
    }

    private ContentPlan disabledPlan(UUID meetingId) {
        return new ContentPlan(
                UUID.randomUUID(), TENANT_ID, meetingId, false, false, false, false,
                PlanState.DISABLED, null, 0, 0, NOW.minusMinutes(1));
    }

    private ContentPlan requestedPlan(UUID meetingId, UUID noticeId, boolean e2ee) {
        return new ContentPlan(
                UUID.randomUUID(), TENANT_ID, meetingId, true, true, true, e2ee,
                PlanState.BLOCKED, noticeId, 1, 3, NOW);
    }

    private ContentNotice notice(UUID meetingId, int revision) {
        return notice(meetingId, revision, UUID.randomUUID());
    }

    private ContentNotice notice(UUID meetingId, int revision, UUID noticeId) {
        return new ContentNotice(
                noticeId, TENANT_ID, meetingId, revision, NoticeState.PUBLISHED,
                "MEETING_CONTENT_PROCESSING", true, true, true, NOW.minusMinutes(5));
    }

    private RecordingSession session(
            UUID meetingId,
            ContentPlan plan,
            ContentNotice notice,
            RecordingState state,
            long version) {
        return new RecordingSession(
                UUID.randomUUID(), TENANT_ID, meetingId, plan.version(), notice.noticeId(),
                state, NOW.minusMinutes(2), USER_ID, null, null,
                state == RecordingState.RECORDING ? NOW.minusMinutes(1) : null,
                null, null, null, version);
    }

    private MeetingContentDependencies.Status readyDependencies() {
        return new MeetingContentDependencies.Status(true, true, true, true, true, true);
    }

    private MeetingContentDependencies.Status unavailableDependencies() {
        return new MeetingContentDependencies.Status(false, false, false, false, false, false);
    }

    private MeetingMediaProvider.Capability availableMedia() {
        return new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300);
    }

    private MeetingMediaProvider.Capability unavailableMedia() {
        return new MeetingMediaProvider.Capability(
                false, "NONE", "MEETING_PROVIDER_DISABLED",
                false, false, false, false, 300);
    }

    private List<String> codes(List<VideoMeetingContentDtos.BlockerResponse> blockers) {
        return blockers.stream().map(VideoMeetingContentDtos.BlockerResponse::code).toList();
    }
}
