package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos.HistoryPublicationFilter;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos.HistoryRetentionFilter;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingCard;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/** Actor-safe history facets. Report and retention state never bypass their owner projections. */
final class MeetingHistoryProjectionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final VideoMeetingJdbcCodec codec;

    MeetingHistoryProjectionRepository(
            NamedParameterJdbcTemplate jdbc,
            VideoMeetingJdbcCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    PagedHistory history(
            long tenantId,
            long userId,
            int page,
            int pageSize,
            boolean favoriteOnly,
            HistoryPublicationFilter publication,
            HistoryRetentionFilter retention,
            OffsetDateTime now) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("now", now)
                .addValue("expiringAt", now.plusDays(30))
                .addValue("publication", publication.name())
                .addValue("retention", retention.name())
                .addValue("limit", pageSize)
                .addValue("offset", (long) page * pageSize);
        String projection = projection(favoriteOnly);
        List<HistoryItem> items = jdbc.query(projection + """
                SELECT * FROM history_projection
                 WHERE (:publication = 'ALL' OR publication_state = :publication)
                   AND (:retention = 'ALL' OR retention_state = :retention)
                 ORDER BY COALESCE(ended_at, updated_at) DESC, meeting_id DESC
                 LIMIT :limit OFFSET :offset
                """, parameters, (resultSet, rowNumber) -> new HistoryItem(
                codec.meetingCard(resultSet, rowNumber),
                resultSet.getString("publication_state"),
                resultSet.getString("retention_state"),
                resultSet.getObject("record_retention_until", OffsetDateTime.class)));
        Long total = jdbc.queryForObject(projection + """
                SELECT COUNT(*) FROM history_projection
                 WHERE (:publication = 'ALL' OR publication_state = :publication)
                   AND (:retention = 'ALL' OR retention_state = :retention)
                """, parameters, Long.class);
        return new PagedHistory(items, total == null ? 0 : total);
    }

    private String projection(boolean favoriteOnly) {
        String favoritePredicate = favoriteOnly ? """
                 AND EXISTS (SELECT 1 FROM vm_meeting_record_bookmarks bookmark
                      WHERE bookmark.tenant_id = meeting.tenant_id
                        AND bookmark.meeting_id = meeting.meeting_id
                        AND bookmark.user_id = :userId AND bookmark.favorite)
                """ : "";
        return """
                WITH history_projection AS (
                    SELECT meeting.*,
                           (SELECT COUNT(*) FROM vm_meeting_participants participant
                             WHERE participant.tenant_id = meeting.tenant_id
                               AND participant.meeting_id = meeting.meeting_id) participant_count,
                           (SELECT participant.participant_role
                              FROM vm_meeting_participants participant
                             WHERE participant.tenant_id = meeting.tenant_id
                               AND participant.meeting_id = meeting.meeting_id
                               AND participant.user_id = :userId) viewer_role,
                           COALESCE(report.report_state, 'NONE') publication_state,
                           CASE
                             WHEN disposition.tenant_id IS NULL THEN 'UNCONFIGURED'
                             WHEN disposition.legal_hold THEN 'LEGAL_HOLD'
                             WHEN disposition.retention_until <= :now THEN 'EXPIRED'
                             WHEN disposition.retention_until <= :expiringAt THEN 'EXPIRING_SOON'
                             ELSE 'ACTIVE'
                           END retention_state,
                           disposition.retention_until record_retention_until
                      FROM vm_meetings meeting
                      LEFT JOIN LATERAL (
                           SELECT candidate.report_state
                             FROM vm_meeting_intelligence_reports candidate
                            WHERE candidate.tenant_id = meeting.tenant_id
                              AND candidate.meeting_id = meeting.meeting_id
                              AND candidate.report_state <> 'DELETED'
                              AND (candidate.legal_hold OR candidate.retention_until > :now)
                              AND EXISTS (
                                  SELECT 1 FROM vm_meeting_participants viewer
                                   WHERE viewer.tenant_id = meeting.tenant_id
                                     AND viewer.meeting_id = meeting.meeting_id
                                     AND viewer.user_id = :userId
                                     AND viewer.attendance_state <> 'DENIED'
                                     AND (
                                         viewer.participant_role IN ('ORGANIZER', 'CO_HOST')
                                         OR candidate.report_state = 'PUBLISHED'
                                            AND candidate.audience = 'MEETING_PARTICIPANTS'
                                            AND viewer.attendance_state IN ('ADMITTED', 'JOINED', 'LEFT')
                                         OR EXISTS (
                                             SELECT 1 FROM vm_meeting_content_acl acl
                                              WHERE acl.tenant_id = candidate.tenant_id
                                                AND acl.meeting_id = candidate.meeting_id
                                                AND acl.content_type = 'INTELLIGENCE_REPORT'
                                                AND acl.content_id = candidate.report_id
                                                AND acl.principal_user_id = :userId
                                                AND acl.permission IN ('VIEW', 'REVIEW', 'MANAGE')
                                                AND acl.revoked_at IS NULL
                                                AND (acl.expires_at IS NULL OR acl.expires_at > :now))))
                            ORDER BY candidate.created_at DESC, candidate.report_id DESC
                            LIMIT 1
                      ) report ON TRUE
                      LEFT JOIN vm_meeting_record_dispositions disposition
                        ON disposition.tenant_id = meeting.tenant_id
                       AND disposition.meeting_id = meeting.meeting_id
                       AND disposition.purged_at IS NULL
                     WHERE meeting.tenant_id = :tenantId
                       AND meeting.lifecycle_state IN ('ENDED', 'CANCELLED')
                       AND
                """ + VideoMeetingRepository.ACCESS_PREDICATE + favoritePredicate + """
                )
                """;
    }

    record HistoryItem(
            MeetingCard card,
            String publicationState,
            String retentionState,
            OffsetDateTime retentionUntil) {
    }

    record PagedHistory(List<HistoryItem> items, long total) {
    }
}
