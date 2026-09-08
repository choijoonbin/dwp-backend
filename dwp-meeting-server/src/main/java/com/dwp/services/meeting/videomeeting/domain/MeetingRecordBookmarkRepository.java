package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MeetingRecordBookmarkRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public MeetingRecordBookmarkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** One statement gives access checks and personal state the same DB snapshot. */
    public List<BookmarkState> accessiblePage(long tenant, long user, List<UUID> meetingIds) {
        return namedJdbc.query("""
                SELECT meeting.meeting_id, COALESCE(bookmark.favorite, FALSE) favorite,
                       COALESCE(bookmark.version, 0) version, bookmark.updated_at
                  FROM vm_meetings meeting
                  LEFT JOIN vm_meeting_record_bookmarks bookmark
                    ON bookmark.tenant_id = meeting.tenant_id
                   AND bookmark.meeting_id = meeting.meeting_id AND bookmark.user_id = :userId
                 WHERE meeting.tenant_id = :tenantId AND meeting.meeting_id IN (:meetingIds)
                   AND meeting.lifecycle_state IN ('ENDED', 'CANCELLED') AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE,
                new MapSqlParameterSource("tenantId", tenant).addValue("userId", user)
                        .addValue("meetingIds", meetingIds),
                (row, index) -> new BookmarkState(row.getObject("meeting_id", UUID.class),
                        row.getBoolean("favorite"), row.getLong("version"),
                        row.getObject("updated_at", OffsetDateTime.class)));
    }

    public BookmarkState lock(long tenant, long user, UUID meetingId) {
        jdbc.update("""
                INSERT INTO vm_meeting_record_bookmarks (tenant_id, user_id, meeting_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, tenant, user, meetingId);
        return jdbc.query("""
                SELECT * FROM vm_meeting_record_bookmarks
                 WHERE tenant_id = ? AND user_id = ? AND meeting_id = ? FOR UPDATE
                """, (row, index) -> new BookmarkState(meetingId, row.getBoolean("favorite"),
                        row.getLong("version"), row.getObject("updated_at", OffsetDateTime.class)),
                tenant, user, meetingId).stream().findFirst().orElseThrow();
    }

    public BookmarkState update(long tenant, long user, UUID meetingId, boolean favorite, long version) {
        return jdbc.query("""
                UPDATE vm_meeting_record_bookmarks
                   SET favorite = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND meeting_id = ? AND version = ?
                 RETURNING *
                """, (row, index) -> new BookmarkState(meetingId, row.getBoolean("favorite"),
                        row.getLong("version"), row.getObject("updated_at", OffsetDateTime.class)),
                favorite, tenant, user, meetingId, version).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The record bookmark changed. Reload it before retrying."));
    }
}
