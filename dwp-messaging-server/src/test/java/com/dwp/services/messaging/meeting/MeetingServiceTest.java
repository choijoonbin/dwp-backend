package com.dwp.services.messaging.meeting;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 19, 10, 30, 0, 0, ZoneOffset.UTC);

    @Mock
    private MeetingSessionRepository repository;
    @Mock
    private MeetingProvider provider;

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void conversationMembershipIsRequiredBeforeCapabilitiesAreDisclosed() {
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject(101L));
        when(repository.access(1L, conversationId, 101L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().capabilities(conversationId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(provider, never()).capability();
    }

    @Test
    void startIsIdempotentForAnExistingActiveSession() {
        UUID conversationId = UUID.randomUUID();
        MeetingSession existing = session(conversationId, 101L, "ACTIVE");
        MessagingRequestContext.set(subject(101L));
        allow(conversationId, "MEMBER");
        when(provider.capability()).thenReturn(availableCapability());
        when(repository.current(1L, conversationId)).thenReturn(Optional.of(existing));

        MeetingDtos.SessionResponse response = service().start(conversationId, "corr-1");

        assertThat(response.sessionId()).isEqualTo(existing.sessionId());
        verify(repository).lockConversation(1L, conversationId);
        verify(provider, never()).prepareRoom(any(), anyLong(), any());
        verify(repository, never()).create(any(), anyLong(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void startPersistsProviderRoomAndLifecycleEvents() {
        UUID conversationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        MessagingRequestContext.set(subject(101L));
        allow(conversationId, "MEMBER");
        when(provider.capability()).thenReturn(availableCapability());
        when(repository.current(1L, conversationId)).thenReturn(Optional.empty());
        when(provider.prepareRoom(any(), eq(1L), eq(conversationId)))
                .thenReturn(new MeetingProvider.PreparedRoom("LIVEKIT", "dwp-room"));
        when(repository.create(
                any(), eq(1L), eq(conversationId), eq("LIVEKIT"),
                eq("dwp-room"), eq(101L), eq("corr-2")))
                .thenAnswer(invocation -> new MeetingSession(
                        invocation.getArgument(0, UUID.class),
                        1L,
                        conversationId,
                        "LIVEKIT",
                        "dwp-room",
                        "ACTIVE",
                        101L,
                        NOW,
                        null,
                        null,
                        0L));

        MeetingDtos.SessionResponse response = service().start(conversationId, "corr-2");

        assertThat(response.provider()).isEqualTo("LIVEKIT");
        assertThat(response.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(response.sessionId()).isNotEqualTo(sessionId);
        verify(repository).recordEvent(any(MeetingSession.class), eq(101L), eq("STARTED"), any());
        verify(repository).audit(
                any(MeetingSession.class), eq(101L), eq("messaging.meeting.started"),
                eq("corr-2"), any());
    }

    @Test
    void disabledProviderReturnsExplicitCapabilityAndRejectsStart() {
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject(101L));
        allow(conversationId, "MEMBER");
        when(provider.capability()).thenReturn(new MeetingProviderCapability(
                false, "NONE", "MEETING_PROVIDER_DISABLED",
                false, false, false, false, 300));

        MeetingDtos.CapabilityResponse capability = service().capabilities(conversationId);

        assertThat(capability.available()).isFalse();
        assertThat(capability.unavailableReason()).isEqualTo("MEETING_PROVIDER_DISABLED");
        assertThatThrownBy(() -> service().start(conversationId, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        verify(repository, never()).lockConversation(anyLong(), any());
    }

    @Test
    void participantTokenIsShortLivedAndAuditMetadataNeverContainsTheSecret() {
        UUID conversationId = UUID.randomUUID();
        MeetingSession active = session(conversationId, 101L, "ACTIVE");
        MessagingRequestContext.set(subject(101L));
        allow(conversationId, "MEMBER");
        when(provider.capability()).thenReturn(availableCapability());
        when(repository.current(1L, conversationId)).thenReturn(Optional.of(active));
        when(provider.issueParticipantToken(eq(active), any(), eq(NOW)))
                .thenReturn(new MeetingProvider.ParticipantToken(
                        "ws://localhost:7880", "signed-participant-token", NOW.plusMinutes(5)));

        MeetingDtos.JoinTokenResponse response = service().token(conversationId, "corr-token");

        assertThat(response.participantToken()).isEqualTo("signed-participant-token");
        assertThat(response.expiresAt()).isEqualTo(NOW.plusMinutes(5));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(repository).recordEvent(eq(active), eq(101L), eq("TOKEN_ISSUED"), metadata.capture());
        assertThat(metadata.getValue()).containsOnlyKeys("expiresAt");
        assertThat(metadata.getValue().toString()).doesNotContain("signed-participant-token");
    }

    @Test
    void ordinaryMemberCannotEndAnotherUsersMeeting() {
        UUID conversationId = UUID.randomUUID();
        MeetingSession active = session(conversationId, 202L, "ACTIVE");
        MessagingRequestContext.set(subject(101L));
        allow(conversationId, "MEMBER");
        when(provider.capability()).thenReturn(availableCapability());
        when(repository.current(1L, conversationId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service().end(conversationId, "corr-end"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(provider, never()).endRoom(any());
        verify(repository, never()).end(anyLong(), any(), any(), anyLong());
    }

    @Test
    void meetingHistoryIsConversationScopedAndBounded() {
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject(101L));
        allow(conversationId, "MEMBER");
        MeetingHistoryItem item = new MeetingHistoryItem(
                UUID.randomUUID(), conversationId, "LIVEKIT", "ENDED",
                101L, "Test User", NOW, 202L, "Moderator", NOW.plusMinutes(20), 1L);
        when(repository.history(1L, conversationId, 20)).thenReturn(List.of(item));

        MeetingDtos.HistoryResponse response = service().history(conversationId, 100);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().durationSeconds()).isEqualTo(1_200);
        assertThat(response.items().getFirst().startedByName()).isEqualTo("Test User");
        verify(repository).history(1L, conversationId, 20);
    }

    private MeetingService service() {
        return new MeetingService(
                repository,
                provider,
                Clock.fixed(Instant.parse("2026-08-19T10:30:00Z"), ZoneOffset.UTC));
    }

    private void allow(UUID conversationId, String role) {
        when(repository.access(1L, conversationId, 101L))
                .thenReturn(Optional.of(new MeetingSessionRepository.ConversationAccess(role)));
    }

    private MessagingRequestContext.Subject subject(long userId) {
        return new MessagingRequestContext.Subject(
                userId,
                1L,
                UUID.randomUUID(),
                "Test User",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MESSAGING:VIEW", "APP.MESSAGING:CREATE"),
                Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private MeetingProviderCapability availableCapability() {
        return new MeetingProviderCapability(
                true, "LIVEKIT", null, true, true, true, true, 300);
    }

    private MeetingSession session(UUID conversationId, long startedBy, String state) {
        return new MeetingSession(
                UUID.randomUUID(),
                1L,
                conversationId,
                "LIVEKIT",
                "dwp-room",
                state,
                startedBy,
                NOW,
                "ENDED".equals(state) ? 101L : null,
                "ENDED".equals(state) ? NOW.plusMinutes(20) : null,
                "ENDED".equals(state) ? 1L : 0L);
    }
}
