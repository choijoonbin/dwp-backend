package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.*;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos.InstantMeetingRequest;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingPersonalRoomRepository.Room;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class MeetingPersonalRoomService {
    private final MeetingPersonalRoomRepository rooms;
    private final MeetingWorkspaceCommands commands;
    private final VideoMeetingService meetings;
    private final VideoMeetingRepository meetingRepository;
    private final VideoMeetingAuditRecorder audit;

    public MeetingPersonalRoomService(MeetingPersonalRoomRepository rooms,
            MeetingWorkspaceCommands commands, VideoMeetingService meetings,
            VideoMeetingRepository meetingRepository, VideoMeetingAuditRecorder audit) {
        this.rooms = rooms;
        this.commands = commands;
        this.meetings = meetings;
        this.meetingRepository = meetingRepository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PersonalRoomResponse get() {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "VIEW");
        return rooms.own(actor.tenantId(), actor.userId(), false).map(this::response).orElse(null);
    }

    @Transactional
    public PersonalRoomResponse create(RoomCreate input, String key, String correlation) {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "CREATE", "MANAGE");
        String name = MeetingWorkspacePolicy.text(input.name(), 160, true);
        var attempt = commands.begin("PERSONAL_ROOM_CREATE", key, input);
        if (attempt.replay() != null) return response(own(false));
        Room created = rooms.create(actor.tenantId(), actor.userId(), name);
        record(created, "created", correlation);
        commands.complete(attempt, created.id(), created.version());
        return response(created);
    }

    @Transactional
    public PersonalRoomResponse update(RoomUpdate input, String key, String correlation) {
        MeetingWorkspacePolicy.require("APP.MEETINGS", "UPDATE", "MANAGE");
        String name = MeetingWorkspacePolicy.text(input.name(), 160, true);
        var attempt = commands.begin("PERSONAL_ROOM_UPDATE", key, input);
        Room current = own(true);
        if (attempt.replay() != null) return response(current);
        MeetingWorkspacePolicy.version(current.version(), input.expectedVersion());
        Room updated = rooms.update(current, name, false);
        record(updated, "updated", correlation);
        commands.complete(attempt, updated.id(), updated.version());
        return response(updated);
    }

    @Transactional
    public PersonalRoomResponse rotate(VersionCommand input, String key, String correlation) {
        MeetingWorkspacePolicy.require("APP.MEETINGS", "UPDATE", "MANAGE");
        var attempt = commands.begin("PERSONAL_ROOM_ROTATE", key, input);
        Room current = own(true);
        if (attempt.replay() != null) return response(current);
        MeetingWorkspacePolicy.version(current.version(), input.expectedVersion());
        Room updated = rooms.update(current, current.name(), true);
        record(updated, "invitation-rotated", correlation);
        commands.complete(attempt, updated.id(), updated.version());
        return response(updated);
    }

    @Transactional
    public RoomSessionResponse createSession(RoomSessionCommand input, String key, String correlation) {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "CREATE", "MANAGE");
        var attempt = commands.begin("PERSONAL_ROOM_SESSION", key, input);
        Room room = own(true);
        var policy = meetingRepository.ensurePolicy(actor.tenantId(), actor.userId());
        if (!policy.meetingsEnabled()) throw new BaseException(ErrorCode.FORBIDDEN, "Meetings are disabled by policy.");
        if (attempt.replay() != null) {
            return rooms.session(room, attempt.replay().resultId()).orElseThrow(MeetingWorkspacePolicy::missing);
        }
        MeetingWorkspacePolicy.version(room.version(), input.expectedVersion());
        if (room.invitationRevision() != input.invitationRevision()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The personal room invitation changed.");
        }
        var current = rooms.current(room, true);
        if (current.isPresent()) {
            commands.complete(attempt, current.get().meetingId(), room.version());
            return current.get();
        }
        // Room setup does not contact a provider, grant media access, or acknowledge consent.
        // The existing LOBBY -> prejoin -> host start lifecycle owns those boundaries.
        String meetingKey = "personal-session-" + VideoMeetingCommandPolicy.requestHash(
                actor.tenantId(), actor.userId(), room.id(), key);
        var created = meetings.instant(new InstantMeetingRequest(room.name(), null, null,
                AccessScope.INTERNAL, true, false, false, false, false, List.of(), List.of()),
                meetingKey, correlation);
        rooms.attach(room, created.meeting().meetingId());
        record(own(false), "session-created", correlation);
        commands.complete(attempt, created.meeting().meetingId(), room.version() + 1);
        return rooms.session(room, created.meeting().meetingId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public RoomSessionPage history(int page, int size) {
        MeetingWorkspacePolicy.require("APP.MEETINGS", "VIEW");
        if (page < 0 || size < 1 || size > 100) throw MeetingWorkspacePolicy.invalid();
        Room room = own(false);
        return new RoomSessionPage(rooms.history(room, page, size), rooms.count(room), page, size);
    }

    @Transactional
    public InvitationResponse resolve(String alias, long revision) {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "VIEW");
        if (alias == null || !alias.matches("[a-f0-9]{32}") || revision < 1) throw MeetingWorkspacePolicy.missing();
        Room room = rooms.invitation(actor.tenantId(), alias, revision).orElseThrow(MeetingWorkspacePolicy::missing);
        var policy = meetingRepository.ensurePolicy(actor.tenantId(), actor.userId());
        if (!policy.meetingsEnabled()) throw MeetingWorkspacePolicy.missing();
        var current = rooms.current(room, false);
        // An alias never resolves across tenants and never returns join credentials.
        return new InvitationResponse(room.name(), current.map(RoomSessionResponse::meetingId).orElse(null),
                current.isPresent());
    }

    private Room own(boolean lock) {
        var actor = MeetingRequestContext.get();
        return rooms.own(actor.tenantId(), actor.userId(), lock).orElseThrow(MeetingWorkspacePolicy::missing);
    }

    private PersonalRoomResponse response(Room room) {
        return new PersonalRoomResponse(room.id(), room.name(), room.alias(), room.invitationRevision(),
                room.version(), room.updatedAt(), rooms.current(room, false).map(RoomSessionResponse::meetingId).orElse(null));
    }

    private void record(Room room, String suffix, String correlation) {
        audit.workspaceChanged(MeetingRequestContext.get(), "meeting.personal-room." + suffix,
                "MEETING_PERSONAL_ROOM", room.id().toString(), correlation,
                Map.of("version", room.version(), "invitationRevision", room.invitationRevision()));
    }
}
