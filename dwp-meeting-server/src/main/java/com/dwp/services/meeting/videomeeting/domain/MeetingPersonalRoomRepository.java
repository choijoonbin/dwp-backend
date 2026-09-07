package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.RoomSessionResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingPersonalRoomRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Room> ROOM = (row, index) -> new Room(
            row.getObject("room_id", UUID.class), row.getLong("tenant_id"),
            row.getLong("owner_user_id"), row.getString("name"), row.getString("opaque_alias"),
            row.getLong("invitation_revision"), row.getLong("version"),
            row.getObject("updated_at", OffsetDateTime.class));
    private static final RowMapper<RoomSessionResponse> SESSION = (row, index) -> new RoomSessionResponse(
            row.getObject("meeting_id", UUID.class), row.getString("title"),
            row.getString("lifecycle_state"), row.getLong("invitation_revision"),
            row.getObject("created_at", OffsetDateTime.class), row.getObject("ended_at", OffsetDateTime.class));

    public MeetingPersonalRoomRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Room> own(long tenant, long user, boolean lock) {
        return jdbc.query("SELECT * FROM vm_personal_meeting_rooms WHERE tenant_id = ? AND owner_user_id = ?"
                + (lock ? " FOR UPDATE" : ""), ROOM, tenant, user).stream().findFirst();
    }

    public Optional<Room> invitation(long tenant, String alias, long revision) {
        return jdbc.query("""
                SELECT * FROM vm_personal_meeting_rooms
                 WHERE tenant_id = ? AND opaque_alias = ? AND invitation_revision = ? FOR SHARE
                """, ROOM, tenant, alias, revision).stream().findFirst();
    }

    public Room create(long tenant, long user, String name) {
        int created = jdbc.update("""
                INSERT INTO vm_personal_meeting_rooms (room_id, tenant_id, owner_user_id, name, opaque_alias)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, owner_user_id) DO NOTHING
                """, UUID.randomUUID(), tenant, user, name, UUID.randomUUID().toString().replace("-", ""));
        if (created != 1) throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "A personal room already exists.");
        return own(tenant, user, false).orElseThrow();
    }

    public Room update(Room current, String name, boolean rotate) {
        jdbc.update("""
                UPDATE vm_personal_meeting_rooms
                   SET name = ?, invitation_revision = invitation_revision + ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND room_id = ? AND version = ?
                """, name, rotate ? 1 : 0, current.tenant(), current.id(), current.version());
        return own(current.tenant(), current.owner(), false).orElseThrow();
    }

    public Optional<RoomSessionResponse> current(Room room, boolean lock) {
        return jdbc.query("""
                SELECT m.meeting_id, m.title, m.lifecycle_state, s.invitation_revision, s.created_at, m.ended_at
                  FROM vm_personal_meeting_room_sessions s
                  JOIN vm_meetings m ON m.tenant_id = s.tenant_id AND m.meeting_id = s.meeting_id
                 WHERE s.tenant_id = ? AND s.room_id = ?
                   AND m.lifecycle_state IN ('DRAFT', 'SCHEDULED', 'LOBBY', 'LIVE')
                 ORDER BY s.created_at DESC, s.meeting_id LIMIT 1
                """ + (lock ? " FOR UPDATE OF m" : ""), SESSION, room.tenant(), room.id())
                .stream().findFirst();
    }

    public Optional<RoomSessionResponse> session(Room room, UUID meetingId) {
        return jdbc.query("""
                SELECT m.meeting_id, m.title, m.lifecycle_state, s.invitation_revision, s.created_at, m.ended_at
                  FROM vm_personal_meeting_room_sessions s
                  JOIN vm_meetings m ON m.tenant_id = s.tenant_id AND m.meeting_id = s.meeting_id
                 WHERE s.tenant_id = ? AND s.room_id = ? AND s.meeting_id = ?
                """, SESSION, room.tenant(), room.id(), meetingId).stream().findFirst();
    }

    public List<RoomSessionResponse> history(Room room, int page, int size) {
        return jdbc.query("""
                SELECT m.meeting_id, m.title, m.lifecycle_state, s.invitation_revision, s.created_at, m.ended_at
                  FROM vm_personal_meeting_room_sessions s
                  JOIN vm_meetings m ON m.tenant_id = s.tenant_id AND m.meeting_id = s.meeting_id
                 WHERE s.tenant_id = ? AND s.room_id = ?
                 ORDER BY s.created_at DESC, s.meeting_id LIMIT ? OFFSET ?
                """, SESSION, room.tenant(), room.id(), size, (long) page * size);
    }

    public long count(Room room) {
        return jdbc.queryForObject("SELECT count(*) FROM vm_personal_meeting_room_sessions WHERE tenant_id = ? AND room_id = ?",
                Long.class, room.tenant(), room.id());
    }

    public void attach(Room room, UUID meetingId) {
        jdbc.update("""
                INSERT INTO vm_personal_meeting_room_sessions (tenant_id, room_id, meeting_id, invitation_revision)
                VALUES (?, ?, ?, ?)
                """, room.tenant(), room.id(), meetingId, room.invitationRevision());
        update(room, room.name(), false);
    }

    public record Room(UUID id, long tenant, long owner, String name, String alias,
                       long invitationRevision, long version, OffsetDateTime updatedAt) { }
}
