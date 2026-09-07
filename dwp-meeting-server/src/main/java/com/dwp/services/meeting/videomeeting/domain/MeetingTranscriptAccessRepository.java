package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
class MeetingTranscriptAccessRepository {

    static final int REQUESTS_PER_MINUTE = 30;

    private final JdbcTemplate jdbc;

    MeetingTranscriptAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean consume(
            long tenantId,
            UUID meetingId,
            long userId,
            OffsetDateTime windowStartedAt) {
        jdbc.update("""
                DELETE FROM vm_meeting_transcript_access_windows
                 WHERE window_started_at < ?
                """, windowStartedAt.minusMinutes(5));
        return !jdbc.query("""
                INSERT INTO vm_meeting_transcript_access_windows (
                    tenant_id, meeting_id, user_id, window_started_at, request_count)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT (tenant_id, meeting_id, user_id, window_started_at)
                DO UPDATE SET request_count =
                    vm_meeting_transcript_access_windows.request_count + 1
                WHERE vm_meeting_transcript_access_windows.request_count < ?
                RETURNING request_count
                """, (row, index) -> row.getInt("request_count"),
                tenantId, meetingId, userId, windowStartedAt,
                REQUESTS_PER_MINUTE).isEmpty();
    }
}
