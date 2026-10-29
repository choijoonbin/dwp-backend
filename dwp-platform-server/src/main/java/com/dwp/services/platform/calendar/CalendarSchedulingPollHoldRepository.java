package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.PollHoldKind;
import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.PollHoldState;

/** Owns scheduling-poll hold fencing, conflicts, and lifecycle persistence. */
final class CalendarSchedulingPollHoldRepository {

    private final JdbcTemplate jdbc;

    CalendarSchedulingPollHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertPeopleHold(
            UUID holdId,
            long tenantId,
            UUID pollId,
            CalendarSchedulingPollRows.CandidateRow candidate,
            CalendarSchedulingPollRows.ParticipantRow participant,
            OffsetDateTime expiresAt,
            long actorUserId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_scheduling_poll_holds (
                    hold_id, tenant_id, poll_id, candidate_id, participant_id,
                    kind, state, person_public_id, starts_at, ends_at, expires_at,
                    version, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'PEOPLE_SOFT', 'ACTIVE', ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (tenant_id, poll_id, candidate_id, participant_id)
                    WHERE kind = 'PEOPLE_SOFT' AND state = 'ACTIVE'
                DO NOTHING
                """, holdId, tenantId, pollId, candidate.candidateId(),
                participant.participantId(), participant.personPublicId(),
                candidate.startsAt(), candidate.endsAt(), expiresAt,
                now, now, actorUserId);
    }

    CalendarSchedulingPollRows.HoldRow insertRoomHold(
            UUID holdId,
            long tenantId,
            UUID pollId,
            CalendarSchedulingPollRows.CandidateRow candidate,
            OffsetDateTime expiresAt,
            long actorUserId,
            OffsetDateTime now) {
        return jdbc.queryForObject("""
                INSERT INTO cal_scheduling_poll_holds (
                    hold_id, tenant_id, poll_id, candidate_id, kind, state,
                    resource_id, starts_at, ends_at, expires_at,
                    version, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'ROOM_HARD', 'ACTIVE', ?, ?, ?, ?, 0, ?, ?, ?)
                RETURNING *
                """, this::hold, holdId, tenantId, pollId, candidate.candidateId(),
                candidate.resourceId(), candidate.startsAt(), candidate.endsAt(),
                expiresAt, now, now, actorUserId);
    }

    void lockRoom(long tenantId, UUID resourceId) {
        jdbc.queryForObject(
                "SELECT cal_scheduling_poll_lock_room(?, ?) IS NULL",
                Boolean.class,
                tenantId,
                resourceId);
    }

    int expireRoomHolds(
            long tenantId,
            UUID resourceId,
            OffsetDateTime now,
            long actorUserId) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = 'EXPIRED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND resource_id = ?
                   AND kind = 'ROOM_HARD' AND state = 'ACTIVE'
                   AND expires_at <= ?
                """, now, actorUserId, tenantId, resourceId, now);
    }

    boolean activeResourceBookingConflict(
            long tenantId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        Boolean conflict = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM cal_resource_bookings booking
                     WHERE booking.tenant_id = ? AND booking.resource_id = ?
                       AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                       AND tstzrange(booking.starts_at, booking.ends_at, '[)')
                           && tstzrange(?::timestamptz, ?::timestamptz, '[)'))
                """, Boolean.class, tenantId, resourceId, startsAt, endsAt);
        return Boolean.TRUE.equals(conflict);
    }

    boolean activeRoomHoldConflict(
            long tenantId,
            UUID pollId,
            UUID candidateId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime now) {
        Boolean conflict = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM cal_scheduling_poll_holds hold_row
                     WHERE hold_row.tenant_id = ? AND hold_row.resource_id = ?
                       AND hold_row.kind = 'ROOM_HARD'
                       AND hold_row.state = 'ACTIVE' AND hold_row.expires_at > ?
                       AND NOT (hold_row.poll_id = ? AND hold_row.candidate_id = ?)
                       AND tstzrange(hold_row.starts_at, hold_row.ends_at, '[)')
                           && tstzrange(?::timestamptz, ?::timestamptz, '[)'))
                """, Boolean.class, tenantId, resourceId, now, pollId, candidateId,
                startsAt, endsAt);
        return Boolean.TRUE.equals(conflict);
    }

    List<CalendarSchedulingPollRows.HoldRow> holds(long tenantId, UUID pollId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_holds
                 WHERE tenant_id = ? AND poll_id = ?
                 ORDER BY candidate_id, kind, hold_id
                """, this::hold, tenantId, pollId);
    }

    int expireHolds(long tenantId, UUID pollId, OffsetDateTime now, long actorUserId) {
        return updateState(
                "EXPIRED", "AND expires_at <= ?", now, actorUserId,
                tenantId, pollId, now);
    }

    int releaseHolds(long tenantId, UUID pollId, long actorUserId, OffsetDateTime now) {
        return updateState(
                "RELEASED", "", now, actorUserId, tenantId, pollId);
    }

    int releaseRoomHold(
            long tenantId,
            UUID pollId,
            UUID candidateId,
            long actorUserId,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = 'RELEASED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                   AND kind = 'ROOM_HARD' AND state = 'ACTIVE'
                """, now, actorUserId, tenantId, pollId, candidateId);
    }

    int releaseRoomHolds(
            long tenantId,
            UUID pollId,
            long actorUserId,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = 'RELEASED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ?
                   AND kind = 'ROOM_HARD' AND state = 'ACTIVE'
                """, now, actorUserId, tenantId, pollId);
    }

    int consumeCandidateHolds(
            long tenantId,
            UUID pollId,
            UUID candidateId,
            long actorUserId,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = 'CONSUMED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                   AND state = 'ACTIVE' AND expires_at > ?
                """, now, actorUserId, tenantId, pollId, candidateId, now);
    }

    int releaseOtherHolds(
            long tenantId,
            UUID pollId,
            UUID candidateId,
            long actorUserId,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = 'RELEASED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id <> ?
                   AND state = 'ACTIVE'
                """, now, actorUserId, tenantId, pollId, candidateId);
    }

    boolean activeRoomHold(
            long tenantId,
            UUID pollId,
            UUID candidateId,
            OffsetDateTime now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM cal_scheduling_poll_holds
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                   AND kind = 'ROOM_HARD' AND state = 'ACTIVE' AND expires_at > ?
                """, Integer.class, tenantId, pollId, candidateId, now);
        return count != null && count == 1;
    }

    int activePeopleHoldCount(
            long tenantId,
            UUID pollId,
            UUID candidateId,
            OffsetDateTime now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM cal_scheduling_poll_holds
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                   AND kind = 'PEOPLE_SOFT' AND state = 'ACTIVE' AND expires_at > ?
                """, Integer.class, tenantId, pollId, candidateId, now);
        return count == null ? 0 : count;
    }

    private int updateState(
            String state,
            String extraPredicate,
            OffsetDateTime now,
            long actorUserId,
            long tenantId,
            UUID pollId,
            Object... extraParameters) {
        Object[] values = new Object[4 + extraParameters.length];
        values[0] = now;
        values[1] = actorUserId;
        values[2] = tenantId;
        values[3] = pollId;
        System.arraycopy(extraParameters, 0, values, 4, extraParameters.length);
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = '%s', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ? AND state = 'ACTIVE' %s
                """.formatted(state, extraPredicate), values);
    }

    private CalendarSchedulingPollRows.HoldRow hold(
            ResultSet result,
            int ignored) throws SQLException {
        return new CalendarSchedulingPollRows.HoldRow(
                result.getObject("hold_id", UUID.class),
                result.getObject("candidate_id", UUID.class),
                result.getObject("participant_id", UUID.class),
                PollHoldKind.valueOf(result.getString("kind")),
                PollHoldState.valueOf(result.getString("state")),
                result.getObject("person_public_id", UUID.class),
                result.getObject("resource_id", UUID.class),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getObject("expires_at", OffsetDateTime.class),
                result.getLong("fencing_token"),
                result.getLong("version"));
    }
}
