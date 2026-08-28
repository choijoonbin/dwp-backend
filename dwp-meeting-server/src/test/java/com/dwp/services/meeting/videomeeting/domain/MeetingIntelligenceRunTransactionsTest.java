package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.NoticeState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.PlanState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ConsentEvidence;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceRun;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.RunState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.SourceArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.StoredRun;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingIntelligenceRunTransactionsTest {

    private static final long TENANT_ID = 77;
    private static final long USER_ID = 101;
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 28, 9, 0, 0, 0, ZoneOffset.UTC);

    @Mock private VideoMeetingRepository meetings;
    @Mock private VideoMeetingContentRepository content;
    @Mock private VideoMeetingIntelligenceRepository intelligence;
    @Mock private MeetingContentDependencies dependencies;
    @Mock private MeetingIntelligenceRetentionService retention;
    @Mock private MeetingTranscriptSource transcripts;
    @Mock private MeetingIntelligencePayloadProtector protector;
    @Mock private VideoMeetingAuditRecorder audit;

    private MeetingIntelligenceRunTransactions transactions;

    @BeforeEach
    void setup() {
        transactions = new MeetingIntelligenceRunTransactions(
                meetings, content, intelligence, new MeetingContentAccessPolicy(),
                dependencies, retention, transcripts, protector, audit);
    }

    @Test
    void sameIdempotencyKeyAndBodyReturnsTheOriginalUnexpiredRun() {
        UUID meetingId = UUID.randomUUID();
        IntelligenceRun original = run(meetingId, NOW.plusMinutes(1), 1, 0);
        lockHost(meetingId);
        when(intelligence.byIdempotency(TENANT_ID, meetingId, USER_ID, "idem-key-0001"))
                .thenReturn(Optional.of(new StoredRun("a".repeat(64), original)));

        var prepared = transactions.prepare(
                subject(), meetingId, command(), "idem-key-0001", "a".repeat(64),
                UUID.randomUUID(), NOW);

        assertThat(prepared.execute()).isFalse();
        assertThat(prepared.run().runId()).isEqualTo(original.runId());
        verify(intelligence, never()).tryCreateRunning(any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyReturns409() {
        UUID meetingId = UUID.randomUUID();
        lockHost(meetingId);
        when(intelligence.byIdempotency(TENANT_ID, meetingId, USER_ID, "idem-key-0002"))
                .thenReturn(Optional.of(new StoredRun(
                        "a".repeat(64), run(meetingId, NOW.plusMinutes(1), 1, 0))));

        assertThatThrownBy(() -> transactions.prepare(
                subject(), meetingId, command(), "idem-key-0002", "b".repeat(64),
                UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void concurrentSameKeyInsertReturnsTheWinningRunWithoutSecondExecution() {
        UUID meetingId = UUID.randomUUID();
        IntelligenceRun winner = run(meetingId, NOW.plusMinutes(2), 1, 0);
        readyFoundation(meetingId);
        when(intelligence.byIdempotency(TENANT_ID, meetingId, USER_ID, "idem-key-0003"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new StoredRun("a".repeat(64), winner)));
        when(intelligence.tryCreateRunning(any())).thenReturn(Optional.empty());

        var prepared = transactions.prepare(
                subject(), meetingId, command(), "idem-key-0003", "a".repeat(64),
                UUID.randomUUID(), NOW);

        assertThat(prepared.execute()).isFalse();
        assertThat(prepared.run().runId()).isEqualTo(winner.runId());
    }

    @Test
    void concurrentSameKeyDifferentBodyIsRejectedAfterUniqueConflict() {
        UUID meetingId = UUID.randomUUID();
        readyFoundation(meetingId);
        when(intelligence.byIdempotency(TENANT_ID, meetingId, USER_ID, "idem-key-0004"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new StoredRun(
                        "b".repeat(64), run(meetingId, NOW.plusMinutes(2), 1, 0))));
        when(intelligence.tryCreateRunning(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactions.prepare(
                subject(), meetingId, command(), "idem-key-0004", "a".repeat(64),
                UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void expiredRunIsAtomicallyReclaimedWithSameRunId() {
        UUID meetingId = UUID.randomUUID();
        IntelligenceRun expired = run(meetingId, NOW.minusSeconds(1), 1, 0);
        IntelligenceRun reclaimed = run(meetingId, NOW.plusMinutes(2), 2, 1);
        readyFoundation(meetingId);
        when(intelligence.byIdempotency(TENANT_ID, meetingId, USER_ID, "idem-key-0005"))
                .thenReturn(Optional.of(new StoredRun("a".repeat(64), expired)));
        when(intelligence.reclaimExpired(any(), any(), any(), any()))
                .thenReturn(Optional.of(reclaimed));

        var prepared = transactions.prepare(
                subject(), meetingId, command(), "idem-key-0005", "a".repeat(64),
                UUID.randomUUID(), NOW);

        assertThat(prepared.execute()).isTrue();
        assertThat(prepared.run().runId()).isEqualTo(expired.runId());
        assertThat(prepared.run().attemptCount()).isEqualTo(2);
    }

    @Test
    void disabledRetentionWorkerBlocksExecutionBeforeTranscriptRead() {
        when(dependencies.status()).thenReturn(new MeetingContentDependencies.Status(
                false, true, true, false, true, true));
        when(transcripts.available()).thenReturn(true);
        when(protector.available()).thenReturn(true);
        when(retention.ready()).thenReturn(false);

        assertThatThrownBy(transactions::ensureExecutionReadiness)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    @Test
    void e2eePlanBlocksPreparationBeforeRunInsert() {
        UUID meetingId = UUID.randomUUID();
        readyFoundation(meetingId);
        when(content.plan(TENANT_ID, meetingId)).thenReturn(Optional.of(
                plan(meetingId, true)));

        assertThatThrownBy(() -> transactions.prepare(
                subject(), meetingId, command(), "idem-key-0007", "a".repeat(64),
                UUID.randomUUID(), NOW))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(intelligence, never()).tryCreateRunning(any());
    }

    @Test
    void freshPreparationPersistsRunningFenceBeforeExternalIo() {
        UUID meetingId = UUID.randomUUID();
        readyFoundation(meetingId);
        when(intelligence.tryCreateRunning(any())).thenAnswer(invocation ->
                Optional.of(invocation.getArgument(0)));

        var prepared = transactions.prepare(
                subject(), meetingId, command(), "idem-key-0008", "a".repeat(64),
                UUID.randomUUID(), NOW);

        assertThat(prepared.execute()).isTrue();
        assertThat(prepared.run().state()).isEqualTo(RunState.RUNNING);
        assertThat(prepared.run().executionFence()).isNotNull();
        assertThat(prepared.run().leaseExpiresAt()).isEqualTo(NOW.plusMinutes(2));
        assertThat(prepared.run().providerCode()).isEqualTo("PENDING");
        verify(dependencies, never()).status();
        verify(transcripts, never()).available();
        verify(protector, never()).available();
        verify(retention, never()).ready();
    }

    @Test
    void differentKeyForTheSameActiveSourceReplaysWithoutAnotherInsert() {
        UUID meetingId = UUID.randomUUID();
        IntelligenceRun active = run(meetingId, NOW.plusMinutes(1), 1, 0);
        readyFoundation(meetingId);
        when(intelligence.activeForSource(
                TENANT_ID, meetingId, SOURCE_ID, "c".repeat(64),
                VideoMeetingIntelligenceModels.PROFILE, NOTICE_ID))
                .thenReturn(Optional.of(active));

        var prepared = transactions.prepare(
                subject(), meetingId, command(), "different-key", "z".repeat(64),
                UUID.randomUUID(), NOW);

        assertThat(prepared.execute()).isFalse();
        assertThat(prepared.run().runId()).isEqualTo(active.runId());
        verify(intelligence, never()).tryCreateRunning(any());
    }

    private void readyFoundation(UUID meetingId) {
        lockHost(meetingId);
        when(intelligence.byIdempotency(
                eq(TENANT_ID), eq(meetingId), eq(USER_ID), any()))
                .thenReturn(Optional.empty());
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy());
        when(content.plan(TENANT_ID, meetingId)).thenReturn(Optional.of(plan(meetingId, false)));
        SourceArtifact source = source(meetingId);
        when(intelligence.sourceTranscript(TENANT_ID, meetingId, command().sourceArtifactId()))
                .thenReturn(Optional.of(source));
        lenient().when(content.currentNotice(TENANT_ID, meetingId))
                .thenReturn(Optional.of(notice(meetingId, source.contentNoticeId())));
        lenient().when(intelligence.consentEvidence(
                TENANT_ID, meetingId, source.contentNoticeId()))
                .thenReturn(new ConsentEvidence(2, 2, source.consentSnapshotSha256()));
    }

    private void lockHost(UUID meetingId) {
        Meeting meeting = meeting(meetingId);
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(participant(meetingId)));
    }

    private VideoMeetingIntelligenceDtos.CreateRunCommand command() {
        return new VideoMeetingIntelligenceDtos.CreateRunCommand(
                SOURCE_ID, "ko-KR", 3);
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                USER_ID, TENANT_ID, UUID.randomUUID(), "Host",
                Set.of("USER"), Set.of("APP.MEETINGS:UPDATE"), Set.of());
    }

    private Meeting meeting(UUID meetingId) {
        return new Meeting(
                meetingId, TENANT_ID, "Meeting", null, null, LifecycleState.ENDED,
                AccessScope.INVITED, "7K9M4Q2X8R6T", NOW.minusHours(1), NOW,
                "Asia/Seoul", true, false, false, false, false,
                "LIVEKIT", "room", USER_ID, UUID.randomUUID(), "Host",
                NOW.minusHours(1), NOW, USER_ID,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                1, NOW.minusDays(1), NOW);
    }

    private Participant participant(UUID meetingId) {
        return new Participant(
                UUID.randomUUID(), TENANT_ID, meetingId, USER_ID, UUID.randomUUID(),
                "host@example.test", "Host", null, null,
                ParticipantRole.ORGANIZER, AttendanceState.LEFT, true,
                null, NOW.minusHours(1), USER_ID, NOW.minusHours(1), NOW,
                null, null, 0);
    }

    private TenantPolicy policy() {
        return new TenantPolicy(
                TENANT_ID, true, true, true, true, true, true,
                "REQUEST_ONLY", "HOST_OPT_IN", false, true,
                100, 365, 30, 30, 0);
    }

    private ContentPlan plan(UUID meetingId, boolean e2ee) {
        return new ContentPlan(
                UUID.randomUUID(), TENANT_ID, meetingId, true, true, true, e2ee,
                PlanState.READY, NOTICE_ID, 2, 3, NOW.minusHours(1));
    }

    private SourceArtifact source(UUID meetingId) {
        return new SourceArtifact(
                SOURCE_ID, TENANT_ID, meetingId, "AVAILABLE", "c".repeat(64),
                NOW.plusDays(10), true, "ap-northeast-2", NOTICE_ID, "d".repeat(64));
    }

    private ContentNotice notice(UUID meetingId, UUID noticeId) {
        return new ContentNotice(
                noticeId, TENANT_ID, meetingId, 2, NoticeState.PUBLISHED,
                "MEETING_CONTENT_PROCESSING", true, true, true, NOW.minusHours(2));
    }

    private IntelligenceRun run(
            UUID meetingId, OffsetDateTime leaseUntil, int attempt, long version) {
        return new IntelligenceRun(
                SHARED_RUN_ID, TENANT_ID, meetingId, SOURCE_ID, "c".repeat(64),
                NOTICE_ID, "d".repeat(64), VideoMeetingIntelligenceModels.PROFILE,
                "ko-KR", "ap-northeast-2", UUID.randomUUID(), leaseUntil, attempt,
                RunState.RUNNING, "PENDING", "PENDING",
                VideoMeetingIntelligenceModels.PROMPT_VERSION,
                VideoMeetingIntelligenceModels.SCHEMA_VERSION,
                "idem-key", "a".repeat(64), NOW.minusMinutes(3), USER_ID,
                NOW.minusMinutes(3), null, null, version);
    }

    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final UUID NOTICE_ID = UUID.randomUUID();
    private static final UUID SHARED_RUN_ID = UUID.randomUUID();
}
