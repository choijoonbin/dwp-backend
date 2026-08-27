package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingCollaborationDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessage;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.HandRequest;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.HandRequestState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
public class VideoMeetingCollaborationService {

    private static final String CHAT_SEND = "CHAT_SEND";
    private static final String CHAT_DELETE = "CHAT_DELETE";
    private static final String HAND_RAISE = "HAND_RAISE";
    private static final String HAND_LOWER = "HAND_LOWER";
    private static final String HAND_ACKNOWLEDGE = "HAND_ACKNOWLEDGE";
    private static final String HAND_DISMISS = "HAND_DISMISS";
    private static final String HAND_CLEAR = "HAND_CLEAR";

    private final VideoMeetingRepository meetings;
    private final VideoMeetingCollaborationRepository collaboration;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public VideoMeetingCollaborationService(
            VideoMeetingRepository meetings,
            VideoMeetingCollaborationRepository collaboration,
            VideoMeetingAuditRecorder audit) {
        this(meetings, collaboration, audit, Clock.systemUTC());
    }

    VideoMeetingCollaborationService(
            VideoMeetingRepository meetings,
            VideoMeetingCollaborationRepository collaboration,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.collaboration = collaboration;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VideoMeetingCollaborationDtos.ChatMessagePageResponse chatMessages(
            UUID meetingId, long afterSequence, int limit) {
        Access access = requireMembership(meetingId);
        int boundedLimit = boundedLimit(limit);
        return VideoMeetingCollaborationDtos.ChatMessagePageResponse.from(
                collaboration.chatMessages(
                        access.subject().tenantId(), meetingId,
                        nonnegative(afterSequence), boundedLimit),
                access.viewer());
    }

    @Transactional
    public VideoMeetingCollaborationDtos.ChatMessageResponse sendChatMessage(
            UUID meetingId,
            VideoMeetingCollaborationDtos.SendChatMessageCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant sender = requireMember(subject, meeting);
        requireJoinedLive(meeting, sender);
        TenantPolicy policy = requireEnabledPolicy(subject);
        if (!policy.participantChatAllowed()) {
            throw forbidden("Meeting chat is disabled by tenant policy.");
        }
        String text = normalizedMessage(request == null ? null : request.text());
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, text);
        Optional<StoredCommand> prior = priorCommand(
                subject, meetingId, CHAT_SEND, key, hash);
        if (prior.isPresent()) {
            return VideoMeetingCollaborationDtos.ChatMessageResponse.from(
                    requireChatMessage(subject, meetingId, prior.get().resultResourceId()), sender);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        long sequence = collaboration.nextSequence(subject.tenantId(), meetingId);
        ChatMessage message = collaboration.createChatMessage(
                subject.tenantId(), meetingId, sender, sequence, text, now,
                now.plusDays(policy.chatRetentionDays()));
        collaboration.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), CHAT_SEND, key, hash,
                message.messageId(), sequence, 1);
        String eventCorrelation = correlation(correlationId);
        audit.collaboration(subject, meeting, "meeting.chat.sent", "MEETING_CHAT_MESSAGE",
                message.messageId().toString(), eventCorrelation, false,
                Map.of("sequence", sequence, "state", message.state().name()));
        return VideoMeetingCollaborationDtos.ChatMessageResponse.from(message, sender);
    }

    @Transactional
    public VideoMeetingCollaborationDtos.ChatMessageResponse deleteChatMessage(
            UUID meetingId,
            UUID messageId,
            VideoMeetingCollaborationDtos.DeleteChatMessageCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant viewer = requireMember(subject, meeting);
        ChatMessage current = requireChatMessage(subject, meetingId, messageId);
        requireUnexpired(meeting, current);
        if (current.senderUserId() != subject.userId() && !viewer.canHost()) {
            throw forbidden("Only the sender or a meeting host can delete this message.");
        }
        String reason = optionalReason(request == null ? null : request.reason());
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, messageId, reason);
        Optional<StoredCommand> prior = priorCommand(
                subject, meetingId, CHAT_DELETE, key, hash);
        if (prior.isPresent() || current.state()
                == VideoMeetingCollaborationModels.ChatMessageState.DELETED) {
            return VideoMeetingCollaborationDtos.ChatMessageResponse.from(current, viewer);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        long sequence = collaboration.nextSequence(subject.tenantId(), meetingId);
        ChatMessage deleted = collaboration.deleteChatMessage(
                current, sequence, subject.userId(), reason, now);
        collaboration.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), CHAT_DELETE, key, hash,
                messageId, sequence, 1);
        audit.collaboration(subject, meeting, "meeting.chat.deleted", "MEETING_CHAT_MESSAGE",
                messageId.toString(), correlation(correlationId), true,
                Map.of("sequence", sequence, "state", deleted.state().name(),
                        "deletedOwnMessage", current.senderUserId() == subject.userId()));
        return VideoMeetingCollaborationDtos.ChatMessageResponse.from(deleted, viewer);
    }

    @Transactional(readOnly = true)
    public VideoMeetingCollaborationDtos.HandRequestPageResponse handRequests(
            UUID meetingId, long afterSequence, int limit) {
        Access access = requireMembership(meetingId);
        return VideoMeetingCollaborationDtos.HandRequestPageResponse.from(
                collaboration.handRequests(
                        access.subject().tenantId(), meetingId,
                        nonnegative(afterSequence), boundedLimit(limit)),
                access.viewer(), access.meeting().lifecycleState() == LifecycleState.LIVE);
    }

    @Transactional
    public VideoMeetingCollaborationDtos.HandRequestResponse raiseHand(
            UUID meetingId,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant requester = requireMember(subject, meeting);
        requireJoinedLive(meeting, requester);
        TenantPolicy policy = requireEnabledPolicy(subject);
        if (!policy.reactionsAllowed()) {
            throw forbidden("Hand raise is disabled by tenant policy.");
        }
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, requester.participantId());
        Optional<StoredCommand> prior = priorCommand(
                subject, meetingId, HAND_RAISE, key, hash);
        if (prior.isPresent()) {
            return VideoMeetingCollaborationDtos.HandRequestResponse.from(
                    requireHand(subject, meetingId, prior.get().resultResourceId()),
                    requester, true);
        }
        Optional<HandRequest> active = collaboration.activeHand(
                subject.tenantId(), meetingId, requester.participantId());
        if (active.isPresent()) {
            HandRequest existing = active.get();
            collaboration.saveCommand(
                    subject.tenantId(), meetingId, subject.userId(), HAND_RAISE, key, hash,
                    existing.requestId(), existing.lastSequence(), 1);
            return VideoMeetingCollaborationDtos.HandRequestResponse.from(
                    existing, requester, true);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        long sequence = collaboration.nextSequence(subject.tenantId(), meetingId);
        HandRequest raised = collaboration.createHandRequest(
                subject.tenantId(), meetingId, requester, sequence, subject.userId(), now);
        collaboration.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), HAND_RAISE, key, hash,
                raised.requestId(), sequence, 1);
        audit.collaboration(subject, meeting, "meeting.hand.raised", "MEETING_HAND_REQUEST",
                raised.requestId().toString(), correlation(correlationId), false,
                Map.of("sequence", sequence, "state", raised.state().name()));
        return VideoMeetingCollaborationDtos.HandRequestResponse.from(raised, requester, true);
    }

    @Transactional
    public VideoMeetingCollaborationDtos.HandRequestResponse lowerHand(
            UUID meetingId,
            UUID requestId,
            String idempotencyKey,
            String correlationId) {
        return transitionOwnHand(
                meetingId, requestId, HandRequestState.LOWERED,
                HAND_LOWER, "meeting.hand.lowered", idempotencyKey, correlationId);
    }

    @Transactional
    public VideoMeetingCollaborationDtos.HandRequestResponse acknowledgeHand(
            UUID meetingId,
            UUID requestId,
            String idempotencyKey,
            String correlationId) {
        return moderateHand(
                meetingId, requestId, HandRequestState.ACKNOWLEDGED,
                HAND_ACKNOWLEDGE, "meeting.hand.acknowledged", idempotencyKey, correlationId);
    }

    @Transactional
    public VideoMeetingCollaborationDtos.HandRequestResponse dismissHand(
            UUID meetingId,
            UUID requestId,
            String idempotencyKey,
            String correlationId) {
        return moderateHand(
                meetingId, requestId, HandRequestState.DISMISSED,
                HAND_DISMISS, "meeting.hand.dismissed", idempotencyKey, correlationId);
    }

    @Transactional
    public VideoMeetingCollaborationDtos.ClearHandRequestsResponse clearHands(
            UUID meetingId,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        requireHost(subject, meeting);
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId);
        Optional<StoredCommand> prior = priorCommand(
                subject, meetingId, HAND_CLEAR, key, hash);
        if (prior.isPresent()) {
            StoredCommand stored = prior.get();
            return new VideoMeetingCollaborationDtos.ClearHandRequestsResponse(
                    stored.resultCount(), stored.resultSequence());
        }

        List<HandRequest> active = collaboration.activeHands(subject.tenantId(), meetingId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        long sequence = collaboration.currentSequence(subject.tenantId(), meetingId);
        for (HandRequest request : active) {
            sequence = collaboration.nextSequence(subject.tenantId(), meetingId);
            collaboration.transitionHand(
                    request, HandRequestState.CLEARED, sequence, subject.userId(), now);
        }
        collaboration.saveCommand(
                subject.tenantId(), meetingId, subject.userId(), HAND_CLEAR, key, hash,
                null, sequence, active.size());
        audit.collaboration(subject, meeting, "meeting.hand.cleared", "VIDEO_MEETING",
                meetingId.toString(), correlation(correlationId), true,
                Map.of("sequence", sequence, "clearedCount", active.size()));
        return new VideoMeetingCollaborationDtos.ClearHandRequestsResponse(
                active.size(), sequence);
    }

    private VideoMeetingCollaborationDtos.HandRequestResponse transitionOwnHand(
            UUID meetingId,
            UUID requestId,
            HandRequestState target,
            String commandType,
            String auditAction,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant viewer = requireMember(subject, meeting);
        HandRequest current = requireHand(subject, meetingId, requestId);
        if (current.requesterUserId() != subject.userId()) {
            throw forbidden("Only the requester can lower this hand request.");
        }
        return transitionHand(
                subject, meeting, viewer, current, target, commandType,
                auditAction, false, idempotencyKey, correlationId);
    }

    private VideoMeetingCollaborationDtos.HandRequestResponse moderateHand(
            UUID meetingId,
            UUID requestId,
            HandRequestState target,
            String commandType,
            String auditAction,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant host = requireHost(subject, meeting);
        if (target == HandRequestState.ACKNOWLEDGED
                && meeting.lifecycleState() != LifecycleState.LIVE) {
            throw invalidState("Hand requests can only be acknowledged during a live meeting.");
        }
        HandRequest current = requireHand(subject, meetingId, requestId);
        return transitionHand(
                subject, meeting, host, current, target, commandType,
                auditAction, true, idempotencyKey, correlationId);
    }

    private VideoMeetingCollaborationDtos.HandRequestResponse transitionHand(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            Participant viewer,
            HandRequest current,
            HandRequestState target,
            String commandType,
            String auditAction,
            boolean moderation,
            String idempotencyKey,
            String correlationId) {
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meeting.meetingId(), current.requestId(), target);
        Optional<StoredCommand> prior = priorCommand(
                subject, meeting.meetingId(), commandType, key, hash);
        if (prior.isPresent() || current.state() == target) {
            return VideoMeetingCollaborationDtos.HandRequestResponse.from(
                    current, viewer, meeting.lifecycleState() == LifecycleState.LIVE);
        }
        if (!current.state().active()
                || target == HandRequestState.ACKNOWLEDGED
                && current.state() != HandRequestState.RAISED) {
            throw invalidState("The hand request is no longer eligible for this action.");
        }

        long sequence = collaboration.nextSequence(subject.tenantId(), meeting.meetingId());
        HandRequest updated = collaboration.transitionHand(
                current, target, sequence, subject.userId(), OffsetDateTime.now(clock));
        collaboration.saveCommand(
                subject.tenantId(), meeting.meetingId(), subject.userId(), commandType, key, hash,
                updated.requestId(), sequence, 1);
        audit.collaboration(subject, meeting, auditAction, "MEETING_HAND_REQUEST",
                updated.requestId().toString(), correlation(correlationId), moderation,
                Map.of("sequence", sequence, "state", updated.state().name()));
        return VideoMeetingCollaborationDtos.HandRequestResponse.from(
                updated, viewer, meeting.lifecycleState() == LifecycleState.LIVE);
    }

    private Access requireMembership(UUID meetingId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.accessibleMeeting(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> notFound("The meeting was not found."));
        return new Access(subject, meeting, requireMember(subject, meeting));
    }

    private Participant requireMember(
            MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = meetings.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .orElseThrow(() -> notFound("The meeting was not found."));
        if (!participant.admitted()) {
            throw forbidden("Meeting admission is required for collaboration.");
        }
        return participant;
    }

    private Participant requireHost(
            MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = requireMember(subject, meeting);
        if (!participant.canHost()) {
            throw forbidden("A meeting host role is required.");
        }
        return participant;
    }

    private void requireJoinedLive(Meeting meeting, Participant participant) {
        if (meeting.lifecycleState() != LifecycleState.LIVE
                || participant.attendanceState() != AttendanceState.JOINED) {
            throw invalidState("The participant must be connected to a live meeting.");
        }
    }

    private TenantPolicy requireEnabledPolicy(MeetingRequestContext.Subject subject) {
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        if (!policy.meetingsEnabled()) {
            throw forbidden("Meetings are disabled for this tenant.");
        }
        return policy;
    }

    private Optional<StoredCommand> priorCommand(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            String commandType,
            String idempotencyKey,
            String requestHash) {
        Optional<StoredCommand> stored = collaboration.command(
                subject.tenantId(), meetingId, subject.userId(), commandType, idempotencyKey);
        if (stored.isPresent()
                && !requestHashesMatch(stored.get().requestHash(), requestHash)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Idempotency-Key was already used with a different request.");
        }
        return stored;
    }

    private ChatMessage requireChatMessage(
            MeetingRequestContext.Subject subject, UUID meetingId, UUID messageId) {
        if (messageId == null) throw notFound("The chat message was not found.");
        return collaboration.chatMessage(subject.tenantId(), meetingId, messageId)
                .orElseThrow(() -> notFound("The chat message was not found."));
    }

    private HandRequest requireHand(
            MeetingRequestContext.Subject subject, UUID meetingId, UUID requestId) {
        if (requestId == null) throw notFound("The hand request was not found.");
        return collaboration.handRequest(subject.tenantId(), meetingId, requestId)
                .orElseThrow(() -> notFound("The hand request was not found."));
    }

    private void requireUnexpired(Meeting meeting, ChatMessage message) {
        if (meeting.lifecycleState() != LifecycleState.LIVE
                && !message.retentionUntil().isAfter(OffsetDateTime.now(clock))) {
            throw notFound("The chat message was not found.");
        }
    }

    private String normalizedMessage(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 4000) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Chat messages must contain 1 to 4000 characters.");
        }
        return normalized;
    }

    private String optionalReason(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().substring(0, Math.min(240, value.strip().length()));
    }

    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(200, limit));
    }

    private long nonnegative(long value) {
        return Math.max(0, value);
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private record Access(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            Participant viewer) {
    }
}
