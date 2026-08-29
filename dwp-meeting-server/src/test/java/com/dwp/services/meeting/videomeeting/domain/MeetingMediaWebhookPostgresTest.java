package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.EventType;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ParticipantBinding;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ProviderEvent;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.RoomBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class MeetingMediaWebhookPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 8, 29, 2, 0, 0, 0, ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private MeetingMediaWebhookTransactions transactions;
    private UUID meetingId;
    private UUID participantId;
    private long userId;
    private UUID incarnation;
    private String roomName;

    @BeforeEach
    void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        VideoMeetingRepository meetings = new VideoMeetingRepository(jdbc, new ObjectMapper());
        MeetingMediaWebhookRepository repository = new MeetingMediaWebhookRepository(jdbc);
        transactions = new MeetingMediaWebhookTransactions(
                repository, meetings, mock(VideoMeetingAuditRecorder.class),
                new MeetingMediaProperties(), new MeetingLifecycleRecoveryProperties(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        bindSeedMeeting();
    }

    @Test
    void signedProviderSessionsAreIdempotentOrderedAndCannotResurrectAfterTerminalEvents() {
        ProviderEvent joinedOne = participantEvent(
                "evt-join-1", EventType.PARTICIPANT_JOINED, "PA_session_1",
                NOW, NOW.minusSeconds(1));
        assertThat(transactions.apply(joinedOne).duplicate()).isFalse();
        assertThat(attendance()).isEqualTo("JOINED");
        assertThat(transactions.apply(joinedOne).duplicate()).isTrue();
        assertThat(connectionCount("PA_session_1")).isOne();

        transactions.apply(participantEvent(
                "evt-left-1", EventType.PARTICIPANT_LEFT, "PA_session_1",
                NOW.plusSeconds(1), NOW.minusSeconds(1)));
        assertThat(attendance()).isEqualTo("LEFT");

        transactions.apply(participantEvent(
                "evt-join-2", EventType.PARTICIPANT_JOINED, "PA_session_2",
                NOW.plusSeconds(3), NOW.plusSeconds(3)));
        assertThat(attendance()).isEqualTo("JOINED");
        transactions.apply(participantEvent(
                "evt-old-left-2", EventType.PARTICIPANT_LEFT, "PA_session_2",
                NOW.plusSeconds(2), NOW.plusSeconds(3)));
        assertThat(attendance()).isEqualTo("JOINED");
        assertThat(connectionState("PA_session_2")).isEqualTo("JOINED");

        transactions.apply(participantEvent(
                "evt-left-2", EventType.PARTICIPANT_CONNECTION_ABORTED, "PA_session_2",
                NOW.plusSeconds(4), NOW.plusSeconds(3)));
        assertThat(attendance()).isEqualTo("LEFT");
        transactions.apply(participantEvent(
                "evt-late-join-2", EventType.PARTICIPANT_JOINED, "PA_session_2",
                NOW.plusSeconds(5), NOW.plusSeconds(3)));
        assertThat(attendance()).isEqualTo("LEFT");
        assertThat(connectionState("PA_session_2")).isEqualTo("ABORTED");

        transactions.apply(participantEvent(
                "evt-join-3", EventType.PARTICIPANT_JOINED, "PA_session_3",
                NOW.plusSeconds(6), NOW.plusSeconds(6)));
        assertThat(attendance()).isEqualTo("JOINED");
        transactions.apply(roomEvent(
                "evt-room-finished", EventType.ROOM_FINISHED, NOW.plusSeconds(7)));
        assertThat(meetingState()).isEqualTo("ENDED");
        assertThat(attendance()).isEqualTo("LEFT");

        var staleJoin = transactions.apply(participantEvent(
                "evt-stale-join", EventType.PARTICIPANT_JOINED, "PA_session_4",
                NOW.plusSeconds(8), NOW.plusSeconds(8)));
        assertThat(staleJoin.cleanup()).isTrue();
        assertThat(attendance()).isEqualTo("LEFT");
        assertThat(meetingState()).isEqualTo("ENDED");
        assertThat(receiptState("evt-stale-join")).isEqualTo("CLEANUP_REQUIRED");

        var staleRoom = transactions.apply(roomEvent(
                "evt-stale-room", EventType.ROOM_STARTED, NOW.plusSeconds(9)));
        assertThat(staleRoom.cleanup()).isTrue();
        assertThat(receiptState("evt-stale-room")).isEqualTo("CLEANUP_REQUIRED");
    }

    @Test
    void foreignRoomOrParticipantBindingIsDurablyIgnoredWithoutProjectionChanges() {
        ProviderEvent foreignRoom = new ProviderEvent(
                "LIVEKIT", "evt-foreign-room", EventType.PARTICIPANT_JOINED, NOW,
                new RoomBinding(
                        "RM_foreign", roomName, 1L, meetingId, UUID.randomUUID(),
                        NOW.minusMinutes(10)),
                participant("PA_foreign", NOW));

        transactions.apply(foreignRoom);

        assertThat(attendance()).isEqualTo("ADMITTED");
        assertThat(receiptState("evt-foreign-room")).isEqualTo("IGNORED");
        assertThat(connectionCount("PA_foreign")).isZero();
    }

    private void bindSeedMeeting() {
        meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'LIVE'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        participantId = jdbc.queryForObject("""
                SELECT participant_id FROM vm_meeting_participants
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id IS NOT NULL
                 ORDER BY CASE participant_role WHEN 'ORGANIZER' THEN 0 ELSE 1 END
                 LIMIT 1
                """, UUID.class, meetingId);
        userId = jdbc.queryForObject("""
                SELECT user_id FROM vm_meeting_participants WHERE participant_id = ?
                """, Long.class, participantId);
        incarnation = UUID.fromString("e239ba52-62c2-4d21-9068-7b2f758ab3e5");
        roomName = "dwp-meeting-t1-" + meetingId.toString().replace("-", "")
                + "-i" + incarnation.toString().replace("-", "");
        jdbc.update("""
                UPDATE vm_meetings
                   SET provider = 'LIVEKIT', room_name = ?, media_incarnation = ?,
                       media_access_state = 'ACTIVE', provider_room_closed_at = NULL
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, roomName, incarnation, meetingId);
        jdbc.update("""
                UPDATE vm_meeting_participants
                   SET attendance_state = 'ADMITTED', admitted_at = COALESCE(
                       admitted_at, CURRENT_TIMESTAMP), joined_at = NULL, left_at = NULL
                 WHERE participant_id = ?
                """, participantId);
    }

    private ProviderEvent participantEvent(
            String eventId,
            EventType type,
            String participantSid,
            OffsetDateTime createdAt,
            OffsetDateTime joinedAt) {
        return new ProviderEvent(
                "LIVEKIT", eventId, type, createdAt, room(),
                participant(participantSid, joinedAt));
    }

    private ProviderEvent roomEvent(
            String eventId, EventType type, OffsetDateTime createdAt) {
        return new ProviderEvent("LIVEKIT", eventId, type, createdAt, room(), null);
    }

    private RoomBinding room() {
        return new RoomBinding(
                "RM_current", roomName, 1L, meetingId, incarnation,
                NOW.minusMinutes(10));
    }

    private ParticipantBinding participant(String sid, OffsetDateTime joinedAt) {
        return new ParticipantBinding(
                sid, participantId, userId,
                "tenant:1:meeting:" + meetingId
                        + ":participant:" + participantId
                        + ":incarnation:" + incarnation
                        + ":user:" + userId,
                joinedAt);
    }

    private String attendance() {
        return jdbc.queryForObject("""
                SELECT attendance_state FROM vm_meeting_participants
                 WHERE participant_id = ?
                """, String.class, participantId);
    }

    private String meetingState() {
        return jdbc.queryForObject(
                "SELECT lifecycle_state FROM vm_meetings WHERE meeting_id = ?",
                String.class, meetingId);
    }

    private String connectionState(String sid) {
        return jdbc.queryForObject("""
                SELECT connection_state FROM vm_meeting_provider_connections
                 WHERE provider_code = 'LIVEKIT' AND provider_participant_sid = ?
                """, String.class, sid);
    }

    private int connectionCount(String sid) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_provider_connections
                 WHERE provider_code = 'LIVEKIT' AND provider_participant_sid = ?
                """, Integer.class, sid);
    }

    private String receiptState(String eventId) {
        return jdbc.queryForObject("""
                SELECT processing_state FROM vm_meeting_provider_events
                 WHERE provider_code = 'LIVEKIT' AND provider_event_id = ?
                """, String.class, eventId);
    }
}
