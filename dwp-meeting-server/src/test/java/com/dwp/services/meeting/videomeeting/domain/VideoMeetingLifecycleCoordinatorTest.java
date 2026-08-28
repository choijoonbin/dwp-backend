package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.MediaOperation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationType;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.Preparation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.Result;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingDetail;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingLifecycleCoordinatorTest {

    private static final UUID MEETING_ID =
            UUID.fromString("01fc0ccf-2f9b-4e53-a76d-31dad67434cf");
    private static final MeetingMediaProvider.PreparedRoom ROOM =
            new MeetingMediaProvider.PreparedRoom("LIVEKIT", "dwp-meeting-t77-room");

    @Mock
    private VideoMeetingLifecycleTransactions transactions;
    @Mock
    private MeetingMediaProvider mediaProvider;

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void providerIoRunsBetweenDurablePrepareAndFencedFinalize() {
        MeetingRequestContext.set(subject());
        MediaOperation operation = operation(UUID.randomUUID(), UUID.randomUUID(), 1);
        Preparation preparation = Preparation.execute(
                operation, ROOM, 100, ParticipantRole.ORGANIZER);
        Result result = new Result(
                new MeetingDetail(meeting(LifecycleState.LIVE, 3), List.of(), List.of()),
                ParticipantRole.ORGANIZER);
        when(transactions.prepareStart(
                subject(), MEETING_ID, 2, "start-command-001", "corr-start"))
                .thenReturn(preparation);
        when(transactions.completeStart(subject(), operation)).thenReturn(result);

        VideoMeetingDtos.MeetingDetailResponse response = coordinator().start(
                MEETING_ID, new VideoMeetingDtos.VersionedCommand(2),
                "start-command-001", "corr-start");

        assertThat(response.lifecycleState()).isEqualTo("LIVE");
        InOrder order = inOrder(transactions, mediaProvider);
        order.verify(transactions).prepareStart(
                subject(), MEETING_ID, 2, "start-command-001", "corr-start");
        order.verify(mediaProvider).ensureRoom(ROOM, 100);
        order.verify(transactions).completeStart(subject(), operation);
        verify(transactions, never()).failProvider(operation);
    }

    @Test
    void providerOrchestratorIsNonTransactionalAndDatabasePhasesRequireNewTransactions()
            throws Exception {
        Method start = VideoMeetingLifecycleCoordinator.class.getDeclaredMethod(
                "start", UUID.class, VideoMeetingDtos.VersionedCommand.class,
                String.class, String.class);
        assertThat(start.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(VideoMeetingLifecycleCoordinator.class
                .isAnnotationPresent(Transactional.class)).isFalse();
        for (String name : List.of(
                "prepareStart", "prepareEnd", "completeStart", "completeEnd",
                "failProvider")) {
            Method method = java.util.Arrays.stream(
                            VideoMeetingLifecycleTransactions.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElseThrow();
            assertThat(method.getAnnotation(Transactional.class).propagation())
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    @Test
    void crashAfterProviderSuccessLeavesDurableRunForSameRoomAdoption() {
        MeetingRequestContext.set(subject());
        MediaOperation firstAttempt = operation(UUID.randomUUID(), UUID.randomUUID(), 1);
        MediaOperation reclaimed = operation(
                firstAttempt.operationId(), UUID.randomUUID(), 2);
        Preparation first = Preparation.execute(
                firstAttempt, ROOM, 100, ParticipantRole.ORGANIZER);
        Preparation second = Preparation.execute(
                reclaimed, ROOM, 100, ParticipantRole.ORGANIZER);
        Result result = new Result(
                new MeetingDetail(meeting(LifecycleState.LIVE, 3), List.of(), List.of()),
                ParticipantRole.ORGANIZER);
        when(transactions.prepareStart(
                subject(), MEETING_ID, 2, "start-command-002", "corr-start"))
                .thenReturn(first, second);
        when(transactions.completeStart(subject(), firstAttempt))
                .thenThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT, "simulated process-loss boundary"));
        when(transactions.completeStart(subject(), reclaimed)).thenReturn(result);

        assertThatThrownBy(() -> coordinator().start(
                MEETING_ID, new VideoMeetingDtos.VersionedCommand(2),
                "start-command-002", "corr-start"))
                .isInstanceOf(BaseException.class);
        VideoMeetingDtos.MeetingDetailResponse recovered = coordinator().start(
                MEETING_ID, new VideoMeetingDtos.VersionedCommand(2),
                "start-command-002", "corr-start");

        assertThat(recovered.lifecycleState()).isEqualTo("LIVE");
        verify(mediaProvider, org.mockito.Mockito.times(2)).ensureRoom(ROOM, 100);
        verify(transactions, never()).failProvider(firstAttempt);
    }

    @Test
    void endRetryUsesTheSameRoomAfterAResponseLossAtFinalize() {
        MeetingRequestContext.set(subject());
        MediaOperation firstAttempt = operation(
                UUID.randomUUID(), UUID.randomUUID(), 1, OperationType.END);
        MediaOperation reclaimed = operation(
                firstAttempt.operationId(), UUID.randomUUID(), 2, OperationType.END);
        Preparation first = Preparation.execute(
                firstAttempt, ROOM, 0, ParticipantRole.ORGANIZER);
        Preparation second = Preparation.execute(
                reclaimed, ROOM, 0, ParticipantRole.ORGANIZER);
        Result result = new Result(
                new MeetingDetail(meeting(LifecycleState.ENDED, 4), List.of(), List.of()),
                ParticipantRole.ORGANIZER);
        when(transactions.prepareEnd(
                subject(), MEETING_ID, 3, "end-command-001", "corr-end"))
                .thenReturn(first, second);
        when(transactions.completeEnd(subject(), firstAttempt))
                .thenThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT, "simulated response-loss boundary"));
        when(transactions.completeEnd(subject(), reclaimed)).thenReturn(result);

        assertThatThrownBy(() -> coordinator().end(
                MEETING_ID, new VideoMeetingDtos.VersionedCommand(3),
                "end-command-001", "corr-end"))
                .isInstanceOf(BaseException.class);
        VideoMeetingDtos.MeetingDetailResponse recovered = coordinator().end(
                MEETING_ID, new VideoMeetingDtos.VersionedCommand(3),
                "end-command-001", "corr-end");

        assertThat(recovered.lifecycleState()).isEqualTo("ENDED");
        verify(mediaProvider, org.mockito.Mockito.times(2)).endRoom(ROOM.roomName());
        verify(transactions, never()).failProvider(firstAttempt);
    }

    @Test
    void providerFailureIsPersistedWithTheCurrentFence() {
        MeetingRequestContext.set(subject());
        MediaOperation operation = operation(UUID.randomUUID(), UUID.randomUUID(), 1);
        Preparation preparation = Preparation.execute(
                operation, ROOM, 100, ParticipantRole.ORGANIZER);
        BaseException failure = new BaseException(
                ErrorCode.EXTERNAL_SERVICE_ERROR, "provider unavailable");
        when(transactions.prepareStart(
                subject(), MEETING_ID, 2, "start-command-003", "corr-start"))
                .thenReturn(preparation);
        org.mockito.Mockito.doThrow(failure).when(mediaProvider).ensureRoom(ROOM, 100);

        assertThatThrownBy(() -> coordinator().start(
                MEETING_ID, new VideoMeetingDtos.VersionedCommand(2),
                "start-command-003", "corr-start"))
                .isSameAs(failure);

        verify(transactions).failProvider(operation);
        verify(transactions, never()).completeStart(subject(), operation);
    }

    private VideoMeetingLifecycleCoordinator coordinator() {
        return new VideoMeetingLifecycleCoordinator(transactions, mediaProvider);
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                101L, 77L, UUID.fromString("5af80da3-0dd8-b3bc-2f44-22d90eecaac4"),
                "Park Hyunwoo", Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:UPDATE"), Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private MediaOperation operation(UUID operationId, UUID fence, int attempt) {
        return operation(operationId, fence, attempt, OperationType.START);
    }

    private MediaOperation operation(
            UUID operationId, UUID fence, int attempt, OperationType type) {
        return new MediaOperation(
                operationId, 77L, MEETING_ID, type,
                OperationState.RUNNING, 101L, 2, "start-command-002",
                "a".repeat(64), "corr-start", fence,
                OffsetDateTime.of(2026, 8, 28, 1, 2, 0, 0, ZoneOffset.UTC),
                attempt, ROOM.provider(), ROOM.roomName());
    }

    private Meeting meeting(LifecycleState state, long version) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 28, 1, 0, 0, 0, ZoneOffset.UTC);
        return new Meeting(
                MEETING_ID, 77L, "Architecture review", null, null, state,
                AccessScope.INVITED, "7K9M4Q2X8R6T", now, now.plusHours(1),
                "Asia/Seoul", true, false, false, false, false,
                state == LifecycleState.LIVE || state == LifecycleState.ENDED
                        ? ROOM.provider() : null,
                state == LifecycleState.LIVE || state == LifecycleState.ENDED
                        ? ROOM.roomName() : null,
                101L, subject().personPublicId(), subject().displayName(),
                state == LifecycleState.LIVE || state == LifecycleState.ENDED ? now : null,
                state == LifecycleState.ENDED ? now.plusHours(1) : null,
                state == LifecycleState.ENDED ? 101L : null,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                version, now.minusDays(1), now);
    }
}
