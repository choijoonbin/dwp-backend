package com.dwp.services.messaging.meeting;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class MeetingService {

    private final MeetingSessionRepository repository;
    private final MeetingProvider provider;
    private final Clock clock;

    @Autowired
    public MeetingService(MeetingSessionRepository repository, MeetingProvider provider) {
        this(repository, provider, Clock.systemUTC());
    }

    MeetingService(MeetingSessionRepository repository, MeetingProvider provider, Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeetingDtos.CapabilityResponse capabilities(UUID conversationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireAccess(subject, conversationId);
        return MeetingDtos.CapabilityResponse.from(provider.capability());
    }

    @Transactional(readOnly = true)
    public MeetingDtos.CurrentMeetingResponse current(UUID conversationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireAccess(subject, conversationId);
        return new MeetingDtos.CurrentMeetingResponse(
                repository.current(subject.tenantId(), conversationId)
                        .map(MeetingDtos.SessionResponse::from)
                        .orElse(null));
    }

    @Transactional
    public MeetingDtos.SessionResponse start(UUID conversationId, String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireAccess(subject, conversationId);
        requireProvider();
        repository.lockConversation(subject.tenantId(), conversationId);

        MeetingSession existing = repository.current(subject.tenantId(), conversationId)
                .orElse(null);
        if (existing != null) return MeetingDtos.SessionResponse.from(existing);

        UUID sessionId = UUID.randomUUID();
        MeetingProvider.PreparedRoom room = provider.prepareRoom(
                sessionId, subject.tenantId(), conversationId);
        MeetingSession created = repository.create(
                sessionId,
                subject.tenantId(),
                conversationId,
                room.provider(),
                room.roomName(),
                subject.userId(),
                correlationId);
        Map<String, Object> metadata = Map.of(
                "conversationId", conversationId.toString(),
                "provider", created.provider());
        repository.recordEvent(created, subject.userId(), "STARTED", metadata);
        repository.audit(
                created, subject.userId(), "messaging.meeting.started", correlationId, metadata);
        return MeetingDtos.SessionResponse.from(created);
    }

    @Transactional
    public MeetingDtos.JoinTokenResponse token(UUID conversationId, String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireAccess(subject, conversationId);
        requireProvider();
        MeetingSession session = activeSession(subject, conversationId);
        OffsetDateTime issuedAt = OffsetDateTime.now(clock);
        MeetingProvider.ParticipantToken participantToken =
                provider.issueParticipantToken(session, subject, issuedAt);
        repository.recordEvent(
                session,
                subject.userId(),
                "TOKEN_ISSUED",
                Map.of("expiresAt", participantToken.expiresAt().toString()));
        return new MeetingDtos.JoinTokenResponse(
                session.sessionId(),
                session.provider(),
                participantToken.serverUrl(),
                participantToken.token(),
                participantToken.expiresAt());
    }

    @Transactional
    public MeetingDtos.SessionResponse end(UUID conversationId, String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        MeetingSessionRepository.ConversationAccess access = requireAccess(subject, conversationId);
        requireProvider();
        repository.lockConversation(subject.tenantId(), conversationId);
        MeetingSession active = activeSession(subject, conversationId);
        if (active.startedBy() != subject.userId() && !access.canEndAnyMeeting()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Only the meeting starter or a conversation moderator can end the meeting for everyone.");
        }
        provider.endRoom(active);
        MeetingSession ended = repository.end(
                subject.tenantId(), conversationId, active.sessionId(), subject.userId());
        Map<String, Object> metadata = Map.of(
                "conversationId", conversationId.toString(),
                "provider", ended.provider());
        repository.recordEvent(ended, subject.userId(), "ENDED", metadata);
        repository.audit(
                ended, subject.userId(), "messaging.meeting.ended", correlationId, metadata);
        return MeetingDtos.SessionResponse.from(ended);
    }

    private MeetingSessionRepository.ConversationAccess requireAccess(
            MessagingRequestContext.Subject subject,
            UUID conversationId) {
        return repository.access(subject.tenantId(), conversationId, subject.userId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The conversation was not found."));
    }

    private MeetingSession activeSession(
            MessagingRequestContext.Subject subject,
            UUID conversationId) {
        return repository.current(subject.tenantId(), conversationId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "There is no active meeting in this conversation."));
    }

    private void requireProvider() {
        if (!provider.capability().available()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Realtime meeting provider is unavailable. Inspect meeting capabilities first.");
        }
    }
}
