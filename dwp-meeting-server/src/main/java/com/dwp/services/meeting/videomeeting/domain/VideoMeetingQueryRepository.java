package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.HomeMetrics;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.HomeProjection;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingCard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

final class VideoMeetingQueryRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final VideoMeetingJdbcCodec codec;

    VideoMeetingQueryRepository(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            VideoMeetingJdbcCodec codec) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.codec = codec;
    }

    HomeProjection home(long tenantId, long userId, OffsetDateTime now) {
        MapSqlParameterSource parameters = accessParameters(tenantId, userId)
                .addValue("now", now)
                .addValue("dayStart", now.toLocalDate().atStartOfDay().atOffset(now.getOffset()))
                .addValue("dayEnd", now.toLocalDate().plusDays(1)
                        .atStartOfDay().atOffset(now.getOffset()));
        List<MeetingCard> live = meetingCards("""
                meeting.lifecycle_state = 'LIVE'
                ORDER BY meeting.started_at DESC LIMIT 8
                """, parameters);
        List<MeetingCard> upcoming = meetingCards("""
                meeting.lifecycle_state IN ('SCHEDULED', 'LOBBY')
                AND (meeting.scheduled_start_at IS NULL OR meeting.scheduled_start_at >= :now)
                ORDER BY COALESCE(meeting.scheduled_start_at, meeting.created_at), meeting.meeting_id
                LIMIT 12
                """, parameters);
        List<MeetingCard> recent = meetingCards("""
                meeting.lifecycle_state = 'ENDED'
                ORDER BY meeting.ended_at DESC, meeting.meeting_id DESC LIMIT 8
                """, parameters);
        HomeMetrics metrics = namedJdbc.query("""
                SELECT COUNT(*) FILTER (
                           WHERE meeting.lifecycle_state <> 'CANCELLED'
                             AND (meeting.lifecycle_state = 'LIVE'
                                  OR meeting.scheduled_start_at >= :dayStart
                                 AND meeting.scheduled_start_at < :dayEnd)) AS meetings_today,
                       COALESCE(SUM(CASE
                           WHEN meeting.started_at >= :dayStart
                            AND meeting.started_at < :dayEnd
                            AND meeting.ended_at IS NOT NULL
                           THEN EXTRACT(EPOCH FROM meeting.ended_at - meeting.started_at) / 60
                           ELSE 0 END), 0)::BIGINT AS meeting_minutes_today
                  FROM vm_meetings meeting
                 WHERE meeting.tenant_id = :tenantId AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE,
                parameters, resultSet -> {
                    if (!resultSet.next()) return new HomeMetrics(0, 0, 0, null, null);
                    Integer waiting = namedJdbc.queryForObject("""
                            SELECT COUNT(*)
                              FROM vm_meeting_participants waiting
                              JOIN vm_meetings meeting
                                ON meeting.tenant_id = waiting.tenant_id
                               AND meeting.meeting_id = waiting.meeting_id
                              JOIN vm_meeting_participants host
                                ON host.tenant_id = meeting.tenant_id
                               AND host.meeting_id = meeting.meeting_id
                               AND host.user_id = :userId
                               AND host.participant_role IN ('ORGANIZER', 'CO_HOST')
                             WHERE waiting.tenant_id = :tenantId
                               AND waiting.attendance_state = 'REQUESTED'
                            """, parameters, Integer.class);
                    return new HomeMetrics(
                            resultSet.getInt("meetings_today"),
                            resultSet.getLong("meeting_minutes_today"),
                            waiting == null ? 0 : waiting, null, null);
                });
        return new HomeProjection(live, upcoming, recent, metrics);
    }

    VideoMeetingRepository.PagedMeetings meetings(
            long tenantId, long userId, int page, int pageSize) {
        MapSqlParameterSource parameters = pageParameters(tenantId, userId, page, pageSize);
        List<MeetingCard> items = namedJdbc.query("""
                SELECT meeting.*,
                       (SELECT COUNT(*) FROM vm_meeting_participants participant
                         WHERE participant.tenant_id = meeting.tenant_id
                           AND participant.meeting_id = meeting.meeting_id) participant_count,
                       (SELECT participant.participant_role
                          FROM vm_meeting_participants participant
                         WHERE participant.tenant_id = meeting.tenant_id
                           AND participant.meeting_id = meeting.meeting_id
                           AND participant.user_id = :userId) viewer_role
                  FROM vm_meetings meeting
                 WHERE meeting.tenant_id = :tenantId
                   AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE + """
                 ORDER BY CASE meeting.lifecycle_state
                            WHEN 'LIVE' THEN 1 WHEN 'LOBBY' THEN 2
                            WHEN 'SCHEDULED' THEN 3 WHEN 'DRAFT' THEN 4
                            WHEN 'ENDED' THEN 5 ELSE 6 END,
                          COALESCE(meeting.scheduled_start_at, meeting.updated_at) DESC,
                          meeting.meeting_id DESC
                 LIMIT :limit OFFSET :offset
                """, parameters, codec::meetingCard);
        Long total = namedJdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meetings meeting
                 WHERE meeting.tenant_id = :tenantId AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE,
                parameters, Long.class);
        return new VideoMeetingRepository.PagedMeetings(items, total == null ? 0 : total);
    }

    VideoMeetingRepository.PagedMeetings history(
            long tenantId, long userId, int page, int pageSize) {
        MapSqlParameterSource parameters = pageParameters(tenantId, userId, page, pageSize);
        List<MeetingCard> items = namedJdbc.query("""
                SELECT meeting.*,
                       (SELECT COUNT(*) FROM vm_meeting_participants participant
                         WHERE participant.tenant_id = meeting.tenant_id
                           AND participant.meeting_id = meeting.meeting_id) participant_count,
                       (SELECT participant.participant_role
                          FROM vm_meeting_participants participant
                         WHERE participant.tenant_id = meeting.tenant_id
                           AND participant.meeting_id = meeting.meeting_id
                           AND participant.user_id = :userId) viewer_role
                  FROM vm_meetings meeting
                 WHERE meeting.tenant_id = :tenantId
                   AND meeting.lifecycle_state IN ('ENDED', 'CANCELLED')
                   AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE + """
                 ORDER BY COALESCE(meeting.ended_at, meeting.updated_at) DESC,
                          meeting.meeting_id DESC
                 LIMIT :limit OFFSET :offset
                """, parameters, codec::meetingCard);
        Long total = namedJdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meetings meeting
                 WHERE meeting.tenant_id = :tenantId
                   AND meeting.lifecycle_state IN ('ENDED', 'CANCELLED')
                   AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE,
                parameters, Long.class);
        return new VideoMeetingRepository.PagedMeetings(items, total == null ? 0 : total);
    }

    VideoMeetingRepository.AdminOverviewData adminOverview(
            long tenantId,
            OffsetDateTime dayStart,
            OffsetDateTime dayEnd,
            OffsetDateTime sevenDaysAgo) {
        return jdbc.query("""
                SELECT COUNT(*) FILTER (
                           WHERE lifecycle_state = 'LIVE') AS live_meetings,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state IN ('SCHEDULED', 'LOBBY')
                             AND scheduled_start_at >= ? AND scheduled_start_at < ?)
                           AS scheduled_today,
                       COUNT(*) FILTER (
                           WHERE created_at >= ?) AS meetings_last_seven_days,
                       (SELECT COUNT(*) FROM vm_meeting_participants participant
                         WHERE participant.tenant_id = ?
                           AND participant.attendance_state = 'REQUESTED') waiting_participants,
                       (SELECT COUNT(*) FROM vm_meeting_events event
                         WHERE event.tenant_id = ? AND event.event_type = 'DENIED'
                           AND event.occurred_at >= ?) failed_join_attempts
                  FROM vm_meetings
                 WHERE tenant_id = ?
                """, resultSet -> resultSet.next()
                        ? new VideoMeetingRepository.AdminOverviewData(
                                resultSet.getInt("live_meetings"),
                                resultSet.getInt("scheduled_today"),
                                resultSet.getInt("waiting_participants"),
                                resultSet.getInt("meetings_last_seven_days"),
                                resultSet.getInt("failed_join_attempts"))
                        : new VideoMeetingRepository.AdminOverviewData(0, 0, 0, 0, 0),
                dayStart, dayEnd, sevenDaysAgo, tenantId, tenantId,
                sevenDaysAgo, tenantId);
    }

    private List<MeetingCard> meetingCards(
            String conditionAndOrder, MapSqlParameterSource parameters) {
        return namedJdbc.query("""
                SELECT meeting.*,
                       (SELECT COUNT(*) FROM vm_meeting_participants participant
                         WHERE participant.tenant_id = meeting.tenant_id
                           AND participant.meeting_id = meeting.meeting_id) participant_count,
                       (SELECT participant.participant_role
                          FROM vm_meeting_participants participant
                         WHERE participant.tenant_id = meeting.tenant_id
                           AND participant.meeting_id = meeting.meeting_id
                           AND participant.user_id = :userId) viewer_role
                  FROM vm_meetings meeting
                 WHERE meeting.tenant_id = :tenantId
                   AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE + " AND " + conditionAndOrder,
                parameters, codec::meetingCard);
    }

    private MapSqlParameterSource pageParameters(
            long tenantId, long userId, int page, int pageSize) {
        return accessParameters(tenantId, userId)
                .addValue("limit", pageSize)
                .addValue("offset", page * pageSize);
    }

    private MapSqlParameterSource accessParameters(long tenantId, long userId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId);
    }
}
