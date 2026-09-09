package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingParticipantDisconnectController.DisconnectCommand;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingParticipantDisconnectRepository.Command;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;

@Service
class MeetingParticipantDisconnectTransactions {
    private final VideoMeetingRepository meetings;
    private final MeetingParticipantDisconnectRepository commands;
    private final VideoMeetingAuditRecorder audit;
    MeetingParticipantDisconnectTransactions(VideoMeetingRepository meetings,
            MeetingParticipantDisconnectRepository commands, VideoMeetingAuditRecorder audit) {
        this.meetings = meetings; this.commands = commands; this.audit = audit;
    }
    @Transactional
    public Command request(UUID meetingId, UUID participantId, DisconnectCommand input,
                           String idempotencyKey, String correlationId) {
        var subject = MeetingRequestContext.get();
        if (input == null) throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE, "A participant version is required.");
        if (subject.roles().contains("PROVIDER_SUPPORT")
                || !subject.has("APP.MEETINGS", "UPDATE", "MANAGE")) throw forbidden();
        var meeting = meetings.lockAccessibleMeeting(subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The meeting was not found."));
        var actor = meetings.participant(subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(MeetingParticipantDisconnectTransactions::forbidden);
        if (!actor.canHost() || !actor.admitted()) throw forbidden();
        if (!meeting.live()) throw conflict();
        var session = meetings.mediaSession(subject.tenantId(), meetingId)
                .filter(VideoMeetingRepository.MediaSession::active)
                .orElseThrow(MeetingParticipantDisconnectTransactions::conflict);
        var target = meetings.participant(subject.tenantId(), meetingId, participantId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The participant was not found."));
        if (target.userId() == null || target.userId() == subject.userId()
                || target.participantRole() == ParticipantRole.ORGANIZER
                || target.canHost() && actor.participantRole() != ParticipantRole.ORGANIZER)
            throw forbidden();
        String key = commandKey(idempotencyKey);
        var previous = commands.find(subject.tenantId(), meetingId, participantId, session.incarnation());
        if (previous.isPresent()) {
            var command = previous.orElseThrow();
            if (!command.idempotencyKey().equals(key)
                    || command.actorUserId() != subject.userId()
                    || command.expectedVersion() != input.expectedVersion()) throw conflict();
            return command;
        }
        if (commands.byKey(subject.tenantId(), key).isPresent()
                || target.version() != input.expectedVersion() || !target.admitted()) throw conflict();
        var command = new Command(UUID.randomUUID(), subject.tenantId(), meetingId, participantId,
                session.incarnation(), meeting.roomName(), target.userId(), subject.userId(),
                input.expectedVersion(), key, "PENDING", null);
        try { commands.insert(command); }
        catch (DuplicateKeyException duplicate) { throw conflict(); }
        if (!commands.deny(command)) throw conflict();
        var denied = meetings.participant(subject.tenantId(), meetingId, participantId).orElseThrow();
        var evidence = Map.<String, Object>of("commandId", command.commandId().toString(),
                "roomIncarnation", command.incarnation().toString(), "state", "PENDING",
                "expectedParticipantVersion", command.expectedVersion());
        String eventCorrelation = correlation(correlationId);
        meetings.recordEvent(meeting, denied, subject.userId(), "DENIED", eventCorrelation, key, evidence);
        audit.participantAccess(subject, meeting, denied, "meeting.participant.disconnect.requested",
                eventCorrelation, "SUCCESS", evidence);
        return command;
    }
    @Transactional
    public Optional<Command> claim(UUID commandId, int maximumAttempts) {
        return commands.claim(commandId, maximumAttempts);
    }
    @Transactional
    public Optional<Command> claimNext(int maximumAttempts) {
        return commands.claimNext(maximumAttempts);
    }
    @Transactional
    public void complete(Command command) {
        var meeting = meetings.lockMeeting(command.tenantId(), command.meetingId());
        if (!commands.complete(command)) throw conflict();
        var participant = meetings.participant(command.tenantId(), command.meetingId(), command.participantId()).orElseThrow();
        audit.providerParticipant(command.tenantId(), meeting, participant,
                "meeting.participant.disconnect.completed", command.commandId().toString(),
                Map.of("commandId", command.commandId().toString(), "roomIncarnation", command.incarnation().toString()));
    }
    @Transactional
    public void failed(Command command, Duration retryDelay) {
        commands.failed(command, retryDelay);
    }
    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "An admitted meeting host role is required for this participant operation.");
    }
    private static BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The meeting or participant changed. Refresh before retrying.");
    }
}
