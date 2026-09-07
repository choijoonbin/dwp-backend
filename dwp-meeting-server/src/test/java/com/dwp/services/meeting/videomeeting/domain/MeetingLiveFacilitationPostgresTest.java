package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.AnswerQuestionCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.AskQuestionCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.CreatePollCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.StartTimerCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.VersionCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.VotePollCommand;
import com.dwp.services.meeting.videomeeting.audit.MeetingLiveFacilitationAuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class MeetingLiveFacilitationPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private MeetingLiveFacilitationRepository repository;
    private MeetingLiveFacilitationAuditRecorder facilitationAudit;
    private MeetingLiveFacilitationService service;
    private MeetingLiveFacilitationRetentionService retention;
    private UUID meetingId;

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void prepareLiveFacilitation() {
        repository = new MeetingLiveFacilitationRepository(jdbc);
        facilitationAudit = spy(new MeetingLiveFacilitationAuditRecorder(
                new AuditOutboxRecorder(new NamedParameterJdbcTemplate(dataSource), mapper,
                        "dwp-meeting-server", "test", "test")));
        service = serviceAt("2026-09-04T05:00:00Z");
        retention = new MeetingLiveFacilitationRetentionService(
                repository, facilitationAudit, Clock.systemUTC());
        meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND room_name = 'dwp-meeting-seed-live-operations'
                """, UUID.class);
        jdbc.update("""
                UPDATE vm_meeting_participants
                   SET participant_role = CASE WHEN user_id = 18 THEN 'CO_HOST'
                                               ELSE participant_role END,
                       attendance_state = 'JOINED',
                       admitted_at = COALESCE(admitted_at, CURRENT_TIMESTAMP),
                       joined_at = COALESCE(joined_at, CURRENT_TIMESTAMP), left_at = NULL
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id IN (18, 20)
                """, meetingId);
    }

    @Test
    void bindsMembershipModeratorAuthorityIdempotencyAndPayloadFreeAudit() {
        String sensitiveQuestion = "Confidential launch codename Saturn?";
        UUID commandId = UUID.randomUUID();
        var asked = user(20, () -> service.askQuestion(
                meetingId, new AskQuestionCommand(sensitiveQuestion),
                commandId.toString(), "facilitation-test"));

        assertThatThrownBy(() -> user(20, () -> service.answerQuestion(
                meetingId, asked.resource().questionId(),
                new AnswerQuestionCommand("Unverified answer", 0L),
                UUID.randomUUID().toString(), "facilitation-test")))
                .isInstanceOf(BaseException.class);
        var answered = user(18, () -> service.answerQuestion(
                meetingId, asked.resource().questionId(),
                new AnswerQuestionCommand("Approved answer", 0L),
                UUID.randomUUID().toString(), "facilitation-test"));
        var replay = user(20, () -> service.askQuestion(
                meetingId, new AskQuestionCommand(sensitiveQuestion),
                commandId.toString(), "facilitation-test"));

        assertThat(replay.resource().questionId()).isEqualTo(asked.resource().questionId());
        assertThat(answered.resource().state()).isEqualTo("ANSWERED");
        assertThat(answered.resource().answer()).isEqualTo("Approved answer");
        assertThatThrownBy(() -> user(20, () -> service.askQuestion(
                meetingId, new AskQuestionCommand("Changed payload"),
                commandId.toString(), "facilitation-test")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(2, 20, all(), () -> service.snapshot(meetingId)))
                .isInstanceOf(BaseException.class);

        String commandRows = jdbc.queryForObject("""
                SELECT string_agg(row_to_json(command_row)::text, ' ')
                  FROM vm_meeting_facilitation_commands command_row
                """, String.class);
        String auditRows = jdbc.queryForObject("""
                SELECT string_agg(payload::text, ' ') FROM sys_audit_outbox
                 WHERE payload->>'action' LIKE 'meeting.facilitation.%'
                """, String.class);
        assertThat(commandRows).doesNotContain(sensitiveQuestion, "Approved answer");
        assertThat(auditRows).doesNotContain(sensitiveQuestion, "Approved answer");
    }

    @Test
    void enforcesOneMutableBallotWithVersionFencing() {
        var draft = user(18, () -> service.createPoll(
                meetingId, new CreatePollCommand(
                        "Choose the release window", List.of("Morning", "Evening"), true),
                UUID.randomUUID().toString(), "facilitation-test"));
        var open = user(18, () -> service.openPoll(
                meetingId, draft.resource().pollId(), new VersionCommand(0L),
                UUID.randomUUID().toString(), "facilitation-test"));
        UUID morning = open.resource().options().get(0).optionId();
        UUID evening = open.resource().options().get(1).optionId();

        var first = user(20, () -> service.vote(
                meetingId, open.resource().pollId(), new VotePollCommand(morning, 0L),
                UUID.randomUUID().toString(), "facilitation-test"));
        var changed = user(20, () -> service.vote(
                meetingId, open.resource().pollId(), new VotePollCommand(evening, 1L),
                UUID.randomUUID().toString(), "facilitation-test"));

        assertThat(first.resource().myBallotVersion()).isOne();
        assertThat(changed.resource().myBallotVersion()).isEqualTo(2);
        assertThat(changed.resource().myOptionId()).isEqualTo(evening);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_facilitation_poll_votes
                 WHERE tenant_id = 1 AND meeting_id = ? AND poll_id = ? AND voter_user_id = 20
                """, Long.class, meetingId, open.resource().pollId())).isOne();
        assertThatThrownBy(() -> user(20, () -> service.vote(
                meetingId, open.resource().pollId(), new VotePollCommand(morning, 1L),
                UUID.randomUUID().toString(), "facilitation-test")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> user(20, () -> service.closePoll(
                meetingId, open.resource().pollId(), new VersionCommand(0L),
                UUID.randomUUID().toString(), "facilitation-test")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void derivesAgendaTimerFromServerClockAndRejectsStaleState() {
        UUID agendaItemId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_agenda_items (
                    item_id, tenant_id, meeting_id, position, title, objective,
                    owner_user_id, planned_minutes, created_by, updated_by)
                VALUES (?, 1, ?, 0, 'Release decision', 'Choose a release window',
                        18, 1, 4, 4)
                """, agendaItemId, meetingId);
        var started = user(18, () -> service.startTimer(
                meetingId, new StartTimerCommand(agendaItemId, 0L),
                UUID.randomUUID().toString(), "facilitation-test"));
        MeetingLiveFacilitationService later = serviceAt("2026-09-04T05:00:20Z");
        var snapshot = user(20, () -> later.snapshot(meetingId));
        var paused = user(18, () -> later.pauseTimer(
                meetingId, new VersionCommand(1L), UUID.randomUUID().toString(),
                "facilitation-test"));

        assertThat(started.resource().remainingSeconds()).isEqualTo(60);
        assertThat(snapshot.timer().elapsedSeconds()).isEqualTo(20);
        assertThat(snapshot.timer().remainingSeconds()).isEqualTo(40);
        assertThat(paused.resource().state()).isEqualTo("PAUSED");
        assertThat(paused.resource().elapsedSeconds()).isEqualTo(20);
        assertThatThrownBy(() -> user(18, () -> later.resumeTimer(
                meetingId, new VersionCommand(1L), UUID.randomUUID().toString(),
                "facilitation-test")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void purgesExpiredContentAndAuditAtomicallyAfterMeetingEnds() {
        String sensitiveQuestion = "Delete this private discussion";
        user(20, () -> service.askQuestion(
                meetingId, new AskQuestionCommand(sensitiveQuestion),
                UUID.randomUUID().toString(), "facilitation-test"));
        jdbc.update("""
                UPDATE vm_meetings
                   SET lifecycle_state = 'ENDED', ended_at = CURRENT_TIMESTAMP, ended_by = 4
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, meetingId);
        jdbc.update("""
                UPDATE vm_meeting_facilitation_states
                   SET retention_until = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, meetingId);
        doThrow(new IllegalStateException("audit unavailable"))
                .doCallRealMethod()
                .when(facilitationAudit).retention(anyLong(), any(), any());

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> retention.purgeExpired()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_facilitation_states")).isOne();
        assertThat(count("vm_meeting_facilitation_retention_evidence")).isZero();

        transaction.executeWithoutResult(status -> retention.purgeExpired());
        assertThat(count("vm_meeting_facilitation_states")).isZero();
        assertThat(count("vm_meeting_facilitation_questions")).isZero();
        assertThat(count("vm_meeting_facilitation_commands")).isZero();
        assertThat(count("vm_meeting_facilitation_retention_evidence")).isOne();
        String retentionAudit = jdbc.queryForObject("""
                SELECT payload::text FROM sys_audit_outbox
                 WHERE payload->>'action' = 'meeting.facilitation.retention.purged'
                """, String.class);
        assertThat(retentionAudit).doesNotContain(sensitiveQuestion);
    }

    private MeetingLiveFacilitationService serviceAt(String instant) {
        return new MeetingLiveFacilitationService(
                meetings, repository, facilitationAudit,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private <T> T user(long userId, Supplier<T> command) {
        return as(1, userId, all(), command);
    }
}
