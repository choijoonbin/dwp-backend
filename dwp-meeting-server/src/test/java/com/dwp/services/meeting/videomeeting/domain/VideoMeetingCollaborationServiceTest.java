package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingCollaborationDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessage;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessageState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.HandRequest;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.HandRequestState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingCollaborationServiceTest {

    private static final long TENANT_ID = 77L;
    private static final long USER_ID = 101L;
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 27, 8, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private VideoMeetingRepository meetings;
    @Mock
    private VideoMeetingCollaborationRepository collaboration;
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
    void nonMemberCannotReadInternalMeetingChat() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        when(meetings.accessibleMeeting(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(meeting));
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().chatMessages(meetingId, 0, 100))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(collaboration, never()).chatMessages(anyLong(), any(), anyLong(), anyInt());
    }

    @Test
    void sendChatUsesServerRetentionAndNeverCopiesContentIntoAudit() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant sender = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        ChatMessage stored = chat(meetingId, sender, 11, NOW.plusDays(90));
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(sender));
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy(90));
        when(collaboration.command(
                eq(TENANT_ID), eq(meetingId), eq(USER_ID), eq("CHAT_SEND"), any()))
                .thenReturn(Optional.empty());
        when(collaboration.nextSequence(TENANT_ID, meetingId)).thenReturn(11L);
        when(collaboration.createChatMessage(
                TENANT_ID, meetingId, sender, 11, "분기 목표를 확정합니다.",
                NOW, NOW.plusDays(90))).thenReturn(stored);

        VideoMeetingCollaborationDtos.ChatMessageResponse response = service().sendChatMessage(
                meetingId,
                new VideoMeetingCollaborationDtos.SendChatMessageCommand(
                        "  분기 목표를 확정합니다.  "),
                "chat-send-0001", "corr-chat");

        assertThat(response.sequence()).isEqualTo(11);
        assertThat(response.retentionUntil()).isEqualTo(NOW.plusDays(90));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(audit).collaboration(
                eq(subject(USER_ID)), eq(meeting), eq("meeting.chat.sent"),
                eq("MEETING_CHAT_MESSAGE"), eq(stored.messageId().toString()),
                eq("corr-chat"), eq(false), state.capture());
        assertThat(state.getValue()).containsOnlyKeys("sequence", "state");
        assertThat(state.getValue().toString()).doesNotContain("분기 목표");
    }

    @Test
    void zeroDayRetentionPersistsDuringLiveMeetingWithImmediateExpiryBoundary() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant sender = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        ChatMessage stored = chat(meetingId, sender, 4, NOW);
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(sender));
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy(0));
        when(collaboration.command(anyLong(), any(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(collaboration.nextSequence(TENANT_ID, meetingId)).thenReturn(4L);
        when(collaboration.createChatMessage(
                TENANT_ID, meetingId, sender, 4, "ephemeral", NOW, NOW))
                .thenReturn(stored);

        service().sendChatMessage(
                meetingId, new VideoMeetingCollaborationDtos.SendChatMessageCommand("ephemeral"),
                "chat-send-0002", null);

        verify(collaboration).createChatMessage(
                TENANT_ID, meetingId, sender, 4, "ephemeral", NOW, NOW);
    }

    @Test
    void retryingChatSendReturnsTheOriginalResourceWithoutAllocatingSequence() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant sender = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        ChatMessage stored = chat(meetingId, sender, 7, NOW.plusDays(90));
        String hash = VideoMeetingCommandPolicy.requestHash(meetingId, "same message");
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(sender));
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy(90));
        when(collaboration.command(
                TENANT_ID, meetingId, USER_ID, "CHAT_SEND", "chat-send-0003"))
                .thenReturn(Optional.of(new StoredCommand(hash, stored.messageId(), 7, 1)));
        when(collaboration.chatMessage(TENANT_ID, meetingId, stored.messageId()))
                .thenReturn(Optional.of(stored));

        VideoMeetingCollaborationDtos.ChatMessageResponse response = service().sendChatMessage(
                meetingId, new VideoMeetingCollaborationDtos.SendChatMessageCommand("same message"),
                "chat-send-0003", null);

        assertThat(response.messageId()).isEqualTo(stored.messageId());
        verify(collaboration, never()).nextSequence(anyLong(), any());
        verify(audit, never()).collaboration(any(), any(), any(), any(), any(), any(),
                eq(false), anyMap());
    }

    @Test
    void reusingACommandKeyWithDifferentChatContentFailsClosed() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant sender = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(sender));
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy(90));
        when(collaboration.command(
                TENANT_ID, meetingId, USER_ID, "CHAT_SEND", "chat-send-0004"))
                .thenReturn(Optional.of(new StoredCommand(
                        "0".repeat(64), UUID.randomUUID(), 7, 1)));

        assertThatThrownBy(() -> service().sendChatMessage(
                meetingId, new VideoMeetingCollaborationDtos.SendChatMessageCommand("new text"),
                "chat-send-0004", null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(collaboration, never()).nextSequence(anyLong(), any());
    }

    @Test
    void attendeeCannotDeleteAnotherParticipantsMessage() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant viewer = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        Participant sender = participant(meetingId, 202L, ParticipantRole.ATTENDEE);
        ChatMessage message = chat(meetingId, sender, 3, NOW.plusDays(90));
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(viewer));
        when(collaboration.chatMessage(TENANT_ID, meetingId, message.messageId()))
                .thenReturn(Optional.of(message));

        assertThatThrownBy(() -> service().deleteChatMessage(
                meetingId, message.messageId(), null, "chat-delete-01", null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(collaboration, never()).deleteChatMessage(any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void raisingAgainReturnsTheExistingActiveQueueEntry() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant requester = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        HandRequest existing = hand(meetingId, requester, 8, HandRequestState.RAISED);
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(requester));
        when(meetings.ensurePolicy(TENANT_ID, USER_ID)).thenReturn(policy(90));
        when(collaboration.command(anyLong(), any(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(collaboration.activeHand(TENANT_ID, meetingId, requester.participantId()))
                .thenReturn(Optional.of(existing));

        VideoMeetingCollaborationDtos.HandRequestResponse response = service().raiseHand(
                meetingId, "hand-raise-0001", null);

        assertThat(response.requestId()).isEqualTo(existing.requestId());
        verify(collaboration, never()).nextSequence(anyLong(), any());
        verify(collaboration).saveCommand(
                eq(TENANT_ID), eq(meetingId), eq(USER_ID), eq("HAND_RAISE"),
                eq("hand-raise-0001"), any(), eq(existing.requestId()), eq(8L), eq(1));
    }

    @Test
    void retryingLowerHandReturnsTheResolvedRequestWithoutAnotherTransition() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant requester = participant(meetingId, USER_ID, ParticipantRole.ATTENDEE);
        HandRequest lowered = hand(meetingId, requester, 9, HandRequestState.LOWERED);
        String hash = VideoMeetingCommandPolicy.requestHash(
                meetingId, lowered.requestId(), HandRequestState.LOWERED);
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(requester));
        when(collaboration.handRequest(TENANT_ID, meetingId, lowered.requestId()))
                .thenReturn(Optional.of(lowered));
        when(collaboration.command(
                TENANT_ID, meetingId, USER_ID, "HAND_LOWER", "hand-lower-001"))
                .thenReturn(Optional.of(new StoredCommand(
                        hash, lowered.requestId(), lowered.lastSequence(), 1)));

        VideoMeetingCollaborationDtos.HandRequestResponse response = service().lowerHand(
                meetingId, lowered.requestId(), "hand-lower-001", null);

        assertThat(response.state()).isEqualTo("LOWERED");
        assertThat(response.canLower()).isFalse();
        verify(collaboration, never()).nextSequence(anyLong(), any());
        verify(collaboration, never()).transitionHand(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void hostAcknowledgesTheOldestRaisedHandWithANewSequence() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        Participant requester = participant(meetingId, 202L, ParticipantRole.ATTENDEE);
        HandRequest raised = hand(meetingId, requester, 12, HandRequestState.RAISED);
        HandRequest acknowledged = new HandRequest(
                raised.requestId(), raised.tenantId(), raised.meetingId(), raised.participantId(),
                raised.requesterUserId(), raised.requesterPersonPublicId(),
                raised.requesterDisplayName(), raised.requesterRole(), raised.raisedSequence(),
                13, HandRequestState.ACKNOWLEDGED, raised.raisedAt(), NOW, USER_ID, null, null);
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID)).thenReturn(Optional.of(host));
        when(collaboration.handRequest(TENANT_ID, meetingId, raised.requestId()))
                .thenReturn(Optional.of(raised));
        when(collaboration.command(anyLong(), any(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(collaboration.nextSequence(TENANT_ID, meetingId)).thenReturn(13L);
        when(collaboration.transitionHand(
                raised, HandRequestState.ACKNOWLEDGED, 13, USER_ID, NOW))
                .thenReturn(acknowledged);

        VideoMeetingCollaborationDtos.HandRequestResponse response = service().acknowledgeHand(
                meetingId, raised.requestId(), "hand-ack-0001", "corr-ack");

        assertThat(response.state()).isEqualTo("ACKNOWLEDGED");
        assertThat(response.sequence()).isEqualTo(13);
        assertThat(response.canDismiss()).isTrue();
        verify(audit).collaboration(
                eq(subject(USER_ID)), eq(meeting), eq("meeting.hand.acknowledged"),
                eq("MEETING_HAND_REQUEST"), eq(raised.requestId().toString()),
                eq("corr-ack"), eq(true), anyMap());
    }

    @Test
    void hostClearTransitionsEveryActiveRequestInQueueOrder() {
        UUID meetingId = UUID.randomUUID();
        Meeting meeting = meeting(meetingId, LifecycleState.LIVE);
        Participant host = participant(meetingId, USER_ID, ParticipantRole.ORGANIZER);
        HandRequest first = hand(meetingId,
                participant(meetingId, 202L, ParticipantRole.ATTENDEE),
                2, HandRequestState.RAISED);
        HandRequest second = hand(meetingId,
                participant(meetingId, 303L, ParticipantRole.ATTENDEE),
                5, HandRequestState.ACKNOWLEDGED);
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting);
        when(meetings.participant(TENANT_ID, meetingId, USER_ID)).thenReturn(Optional.of(host));
        when(collaboration.command(anyLong(), any(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(collaboration.activeHands(TENANT_ID, meetingId)).thenReturn(List.of(first, second));
        when(collaboration.currentSequence(TENANT_ID, meetingId)).thenReturn(5L);
        when(collaboration.nextSequence(TENANT_ID, meetingId)).thenReturn(6L, 7L);

        VideoMeetingCollaborationDtos.ClearHandRequestsResponse response = service().clearHands(
                meetingId, "hand-clear-001", "corr-clear");

        assertThat(response.clearedCount()).isEqualTo(2);
        assertThat(response.sequence()).isEqualTo(7);
        verify(collaboration).transitionHand(
                first, HandRequestState.CLEARED, 6, USER_ID, NOW);
        verify(collaboration).transitionHand(
                second, HandRequestState.CLEARED, 7, USER_ID, NOW);
    }

    private VideoMeetingCollaborationService service() {
        return new VideoMeetingCollaborationService(
                meetings, collaboration, audit,
                Clock.fixed(Instant.parse("2026-08-27T08:00:00Z"), ZoneOffset.UTC));
    }

    private MeetingRequestContext.Subject subject(long userId) {
        return new MeetingRequestContext.Subject(
                userId, TENANT_ID, UUID.nameUUIDFromBytes(("person-" + userId).getBytes()),
                "User " + userId, Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private TenantPolicy policy(int chatRetentionDays) {
        return new TenantPolicy(
                TENANT_ID, true, true, false, true, true, true,
                "REQUEST_ONLY", "NEVER", false, true, 100,
                1095, 365, chatRetentionDays, 0);
    }

    private Meeting meeting(UUID meetingId, LifecycleState state) {
        return new Meeting(
                meetingId, TENANT_ID, "Quarterly review", null, null, state,
                AccessScope.INTERNAL, "7K9M4Q2X8R6T", NOW.minusHours(1), NOW.plusHours(1),
                "Asia/Seoul", true, false, false, false, false,
                state == LifecycleState.LIVE || state == LifecycleState.ENDED
                        ? "LIVEKIT" : null,
                state == LifecycleState.LIVE || state == LifecycleState.ENDED
                        ? "room" : null,
                USER_ID, subject(USER_ID).personPublicId(), "Organizer",
                state == LifecycleState.LIVE || state == LifecycleState.ENDED ? NOW : null,
                state == LifecycleState.ENDED ? NOW.plusHours(1) : null,
                state == LifecycleState.ENDED ? USER_ID : null,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                1, NOW.minusDays(1), NOW);
    }

    private Participant participant(
            UUID meetingId, long userId, ParticipantRole role) {
        return new Participant(
                UUID.nameUUIDFromBytes((meetingId + ":" + userId).getBytes()),
                TENANT_ID, meetingId, userId, subject(userId).personPublicId(),
                "user" + userId + "@sk.com", "User " + userId, null, null,
                role, AttendanceState.JOINED, true, NOW.minusMinutes(5),
                NOW.minusMinutes(4), USER_ID, NOW.minusMinutes(3), null,
                null, null, 1);
    }

    private ChatMessage chat(
            UUID meetingId,
            Participant sender,
            long sequence,
            OffsetDateTime retentionUntil) {
        return new ChatMessage(
                UUID.randomUUID(), TENANT_ID, meetingId, sender.participantId(),
                sender.userId(), sender.personPublicId(), sender.displayName(),
                sender.participantRole(), sequence, sequence, ChatMessageState.ACTIVE,
                "분기 목표를 확정합니다.", retentionUntil, NOW, null);
    }

    private HandRequest hand(
            UUID meetingId,
            Participant requester,
            long sequence,
            HandRequestState state) {
        boolean acknowledged = state == HandRequestState.ACKNOWLEDGED;
        return new HandRequest(
                UUID.randomUUID(), TENANT_ID, meetingId, requester.participantId(),
                requester.userId(), requester.personPublicId(), requester.displayName(),
                requester.participantRole(), sequence, sequence, state, NOW.minusMinutes(1),
                acknowledged ? NOW : null, acknowledged ? USER_ID : null,
                state.active() ? null : NOW, state.active() ? null : USER_ID);
    }
}
