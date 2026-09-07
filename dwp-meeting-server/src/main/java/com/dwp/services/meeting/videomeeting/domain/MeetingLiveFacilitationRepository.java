package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.AgendaItem;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.ExpiredFacilitation;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.FacilitationState;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.Poll;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.PollOption;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.PollState;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.Question;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.QuestionState;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.TimerState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingLiveFacilitationRepository {

    private final JdbcTemplate jdbc;

    public MeetingLiveFacilitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureState(
            long tenantId, UUID meetingId, OffsetDateTime retentionUntil, long actorUserId) {
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_states (
                    tenant_id, meeting_id, retention_until, updated_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, meeting_id) DO UPDATE
                   SET retention_until = GREATEST(
                           vm_meeting_facilitation_states.retention_until,
                           EXCLUDED.retention_until)
                """, tenantId, meetingId, retentionUntil, actorUserId);
    }

    public Optional<FacilitationState> state(long tenantId, UUID meetingId, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF state" : "";
        return jdbc.query("""
                SELECT state.*, agenda.title AS agenda_item_title
                  FROM vm_meeting_facilitation_states state
                  LEFT JOIN vm_meeting_agenda_items agenda
                    ON agenda.tenant_id = state.tenant_id
                   AND agenda.meeting_id = state.meeting_id
                   AND agenda.item_id = state.agenda_item_id
                 WHERE state.tenant_id = ? AND state.meeting_id = ?
                """ + suffix, this::state, tenantId, meetingId).stream().findFirst();
    }

    public long nextSequence(
            long tenantId, UUID meetingId, OffsetDateTime retentionUntil, long actorUserId) {
        return jdbc.queryForObject("""
                UPDATE vm_meeting_facilitation_states
                   SET last_sequence = last_sequence + 1,
                       retention_until = GREATEST(retention_until, ?),
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ?
                RETURNING last_sequence
                """, Long.class, retentionUntil, actorUserId, tenantId, meetingId);
    }

    public Optional<AgendaItem> agendaItem(long tenantId, UUID meetingId, UUID itemId) {
        return jdbc.query("""
                SELECT item_id, title, position, COALESCE(planned_minutes, 15) * 60 planned_seconds
                  FROM vm_meeting_agenda_items
                 WHERE tenant_id = ? AND meeting_id = ? AND item_id = ?
                """, this::agendaItem, tenantId, meetingId, itemId).stream().findFirst();
    }

    public Optional<AgendaItem> nextAgendaItem(
            long tenantId, UUID meetingId, UUID currentItemId) {
        return jdbc.query("""
                SELECT next.item_id, next.title, next.position,
                       COALESCE(next.planned_minutes, 15) * 60 planned_seconds
                  FROM vm_meeting_agenda_items current
                  JOIN vm_meeting_agenda_items next
                    ON next.tenant_id = current.tenant_id
                   AND next.meeting_id = current.meeting_id
                   AND next.position > current.position
                 WHERE current.tenant_id = ? AND current.meeting_id = ?
                   AND current.item_id = ?
                 ORDER BY next.position
                 LIMIT 1
                """, this::agendaItem, tenantId, meetingId, currentItemId)
                .stream().findFirst();
    }

    public Optional<FacilitationState> startTimer(
            FacilitationState current, AgendaItem item, long expectedVersion,
            long sequence, OffsetDateTime now, OffsetDateTime retentionUntil, long actorUserId) {
        return updateTimer("""
                UPDATE vm_meeting_facilitation_states
                   SET timer_state = 'RUNNING', agenda_item_id = ?,
                       timer_planned_seconds = ?, timer_elapsed_seconds = 0,
                       timer_running_since = ?, timer_version = timer_version + 1,
                       last_sequence = ?, retention_until = GREATEST(retention_until, ?),
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND timer_version = ?
                   AND timer_state IN ('IDLE', 'COMPLETED')
                RETURNING *
                """, current.tenantId(), current.meetingId(),
                item.itemId(), item.plannedSeconds(), now, sequence, retentionUntil,
                now, actorUserId, current.tenantId(), current.meetingId(), expectedVersion);
    }

    public Optional<FacilitationState> pauseTimer(
            FacilitationState current, long expectedVersion, long sequence,
            OffsetDateTime now, OffsetDateTime retentionUntil, long actorUserId) {
        return updateTimer("""
                UPDATE vm_meeting_facilitation_states
                   SET timer_state = 'PAUSED',
                       timer_elapsed_seconds = timer_elapsed_seconds + GREATEST(
                           0, EXTRACT(EPOCH FROM (? - timer_running_since))::INTEGER),
                       timer_running_since = NULL, timer_version = timer_version + 1,
                       last_sequence = ?, retention_until = GREATEST(retention_until, ?),
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND timer_version = ?
                   AND timer_state = 'RUNNING'
                RETURNING *
                """, current.tenantId(), current.meetingId(),
                now, sequence, retentionUntil, now, actorUserId,
                current.tenantId(), current.meetingId(), expectedVersion);
    }

    public Optional<FacilitationState> resumeTimer(
            FacilitationState current, long expectedVersion, long sequence,
            OffsetDateTime now, OffsetDateTime retentionUntil, long actorUserId) {
        return updateTimer("""
                UPDATE vm_meeting_facilitation_states
                   SET timer_state = 'RUNNING', timer_running_since = ?,
                       timer_version = timer_version + 1, last_sequence = ?,
                       retention_until = GREATEST(retention_until, ?),
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND timer_version = ?
                   AND timer_state = 'PAUSED'
                   AND timer_elapsed_seconds < timer_planned_seconds
                RETURNING *
                """, current.tenantId(), current.meetingId(),
                now, sequence, retentionUntil, now, actorUserId,
                current.tenantId(), current.meetingId(), expectedVersion);
    }

    public Optional<FacilitationState> advanceTimer(
            FacilitationState current, AgendaItem next, long expectedVersion, long sequence,
            OffsetDateTime now, OffsetDateTime retentionUntil, long actorUserId) {
        return updateTimer("""
                UPDATE vm_meeting_facilitation_states
                   SET timer_state = 'RUNNING', agenda_item_id = ?,
                       timer_planned_seconds = ?, timer_elapsed_seconds = 0,
                       timer_running_since = ?, timer_version = timer_version + 1,
                       last_sequence = ?, retention_until = GREATEST(retention_until, ?),
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND timer_version = ?
                   AND timer_state <> 'IDLE'
                RETURNING *
                """, current.tenantId(), current.meetingId(),
                next.itemId(), next.plannedSeconds(), now, sequence, retentionUntil,
                now, actorUserId, current.tenantId(), current.meetingId(), expectedVersion);
    }

    public Optional<FacilitationState> completeTimer(
            FacilitationState current, long expectedVersion, long sequence,
            OffsetDateTime now, OffsetDateTime retentionUntil, long actorUserId) {
        return updateTimer("""
                UPDATE vm_meeting_facilitation_states
                   SET timer_state = 'COMPLETED', timer_running_since = NULL,
                       timer_elapsed_seconds = timer_planned_seconds,
                       timer_version = timer_version + 1, last_sequence = ?,
                       retention_until = GREATEST(retention_until, ?),
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND timer_version = ?
                   AND timer_state <> 'IDLE'
                RETURNING *
                """, current.tenantId(), current.meetingId(),
                sequence, retentionUntil, now, actorUserId,
                current.tenantId(), current.meetingId(), expectedVersion);
    }

    private Optional<FacilitationState> updateTimer(
            String sql, long tenantId, UUID meetingId, Object... arguments) {
        Object[] withIdentity = new Object[arguments.length];
        System.arraycopy(arguments, 0, withIdentity, 0, arguments.length);
        return jdbc.query(sql, (row, number) -> state(row, number, null), withIdentity)
                .stream().findFirst()
                .map(value -> new FacilitationState(
                        value.tenantId(), value.meetingId(), value.lastSequence(),
                        value.timerState(), value.agendaItemId(),
                        agendaItem(tenantId, meetingId, value.agendaItemId())
                                .map(AgendaItem::title).orElse(null),
                        value.plannedSeconds(), value.elapsedSeconds(), value.runningSince(),
                        value.timerVersion(), value.retentionUntil()));
    }

    public Question createQuestion(
            long tenantId, UUID meetingId, Participant author, String text,
            long sequence, OffsetDateTime now, OffsetDateTime retentionUntil) {
        UUID questionId = UUID.randomUUID();
        return jdbc.query("""
                INSERT INTO vm_meeting_facilitation_questions (
                    question_id, tenant_id, meeting_id, author_participant_id,
                    author_user_id, question_text, created_sequence, last_sequence,
                    retention_until, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *, ? AS author_display_name, 0 AS upvote_count,
                          FALSE AS upvoted_by_viewer
                """, this::question,
                questionId, tenantId, meetingId, author.participantId(), author.userId(),
                text, sequence, sequence, retentionUntil, now, now,
                author.displayName()).stream().findFirst().orElseThrow();
    }

    public Optional<Question> question(
            long tenantId, UUID meetingId, UUID questionId, long viewerUserId) {
        return jdbc.query("""
                SELECT question.*, author.display_name AS author_display_name,
                       COUNT(upvote.voter_user_id)::INTEGER AS upvote_count,
                       BOOL_OR(upvote.voter_user_id = ?) AS upvoted_by_viewer
                  FROM vm_meeting_facilitation_questions question
                  JOIN vm_meeting_participants author
                    ON author.tenant_id = question.tenant_id
                   AND author.meeting_id = question.meeting_id
                   AND author.participant_id = question.author_participant_id
                  JOIN vm_meetings meeting
                    ON meeting.tenant_id = question.tenant_id
                   AND meeting.meeting_id = question.meeting_id
                  LEFT JOIN vm_meeting_facilitation_question_upvotes upvote
                    ON upvote.tenant_id = question.tenant_id
                   AND upvote.meeting_id = question.meeting_id
                   AND upvote.question_id = question.question_id
                 WHERE question.tenant_id = ? AND question.meeting_id = ?
                   AND question.question_id = ?
                   AND (meeting.lifecycle_state = 'LIVE'
                     OR question.retention_until > CURRENT_TIMESTAMP)
                 GROUP BY question.question_id, author.display_name, meeting.meeting_id
                """, this::question, viewerUserId, tenantId, meetingId, questionId)
                .stream().findFirst();
    }

    public List<Question> questions(long tenantId, UUID meetingId, long viewerUserId) {
        return jdbc.query("""
                SELECT question.*, author.display_name AS author_display_name,
                       COUNT(upvote.voter_user_id)::INTEGER AS upvote_count,
                       BOOL_OR(upvote.voter_user_id = ?) AS upvoted_by_viewer
                  FROM vm_meeting_facilitation_questions question
                  JOIN vm_meeting_participants author
                    ON author.tenant_id = question.tenant_id
                   AND author.meeting_id = question.meeting_id
                   AND author.participant_id = question.author_participant_id
                  JOIN vm_meetings meeting
                    ON meeting.tenant_id = question.tenant_id
                   AND meeting.meeting_id = question.meeting_id
                  LEFT JOIN vm_meeting_facilitation_question_upvotes upvote
                    ON upvote.tenant_id = question.tenant_id
                   AND upvote.meeting_id = question.meeting_id
                   AND upvote.question_id = question.question_id
                 WHERE question.tenant_id = ? AND question.meeting_id = ?
                   AND (meeting.lifecycle_state = 'LIVE'
                     OR question.retention_until > CURRENT_TIMESTAMP)
                 GROUP BY question.question_id, author.display_name, meeting.meeting_id
                 ORDER BY CASE question.question_state WHEN 'OPEN' THEN 0 ELSE 1 END,
                          COUNT(upvote.voter_user_id) DESC, question.created_at
                 LIMIT 100
                """, this::question, viewerUserId, tenantId, meetingId);
    }

    public boolean addQuestionUpvote(
            Question question, Participant voter, long sequence, OffsetDateTime now) {
        int inserted = jdbc.update("""
                INSERT INTO vm_meeting_facilitation_question_upvotes (
                    tenant_id, meeting_id, question_id, voter_participant_id,
                    voter_user_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, meeting_id, question_id, voter_user_id) DO NOTHING
                """, question.tenantId(), question.meetingId(), question.questionId(),
                voter.participantId(), voter.userId(), now);
        if (inserted > 0) {
            jdbc.update("""
                    UPDATE vm_meeting_facilitation_questions
                       SET last_sequence = ?, updated_at = ?
                     WHERE tenant_id = ? AND meeting_id = ? AND question_id = ?
                    """, sequence, now, question.tenantId(), question.meetingId(),
                    question.questionId());
        }
        return inserted > 0;
    }

    public Optional<Question> moderateQuestion(
            Question current, QuestionState target, String answer, long expectedVersion,
            long sequence, long actorUserId, OffsetDateTime now, long viewerUserId) {
        jdbc.update("""
                UPDATE vm_meeting_facilitation_questions
                   SET question_state = ?, answer_text = ?,
                       answered_at = CASE WHEN ? = 'ANSWERED' THEN ? ELSE NULL END,
                       answered_by = CASE WHEN ? = 'ANSWERED' THEN ? ELSE NULL END,
                       dismissed_at = CASE WHEN ? = 'DISMISSED' THEN ? ELSE NULL END,
                       dismissed_by = CASE WHEN ? = 'DISMISSED' THEN ? ELSE NULL END,
                       version = version + 1, last_sequence = ?, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND question_id = ?
                   AND version = ? AND question_state = 'OPEN'
                """, target.name(), answer,
                target.name(), now, target.name(), actorUserId,
                target.name(), now, target.name(), actorUserId,
                sequence, now, current.tenantId(), current.meetingId(),
                current.questionId(), expectedVersion);
        return question(current.tenantId(), current.meetingId(), current.questionId(), viewerUserId)
                .filter(updated -> updated.version() == expectedVersion + 1);
    }

    public Poll createPoll(
            long tenantId, UUID meetingId, Participant creator, String question,
            List<String> options, boolean anonymous, long sequence,
            OffsetDateTime now, OffsetDateTime retentionUntil) {
        UUID pollId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_polls (
                    poll_id, tenant_id, meeting_id, creator_participant_id,
                    creator_user_id, poll_question, anonymous,
                    created_sequence, last_sequence, retention_until,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, pollId, tenantId, meetingId, creator.participantId(), creator.userId(),
                question, anonymous, sequence, sequence, retentionUntil, now, now);
        for (int position = 0; position < options.size(); position++) {
            jdbc.update("""
                    INSERT INTO vm_meeting_facilitation_poll_options (
                        option_id, tenant_id, meeting_id, poll_id, position, option_label)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, meetingId, pollId, position,
                    options.get(position));
        }
        return poll(tenantId, meetingId, pollId, creator.userId()).orElseThrow();
    }

    public Optional<Poll> poll(
            long tenantId, UUID meetingId, UUID pollId, long viewerUserId) {
        return jdbc.query("""
                SELECT poll.*,
                       vote.option_id AS viewer_option_id,
                       COALESCE(vote.ballot_version, 0) AS viewer_ballot_version
                  FROM vm_meeting_facilitation_polls poll
                  JOIN vm_meetings meeting
                    ON meeting.tenant_id = poll.tenant_id
                   AND meeting.meeting_id = poll.meeting_id
                  LEFT JOIN vm_meeting_facilitation_poll_votes vote
                    ON vote.tenant_id = poll.tenant_id
                   AND vote.meeting_id = poll.meeting_id
                   AND vote.poll_id = poll.poll_id
                   AND vote.voter_user_id = ?
                 WHERE poll.tenant_id = ? AND poll.meeting_id = ? AND poll.poll_id = ?
                   AND (meeting.lifecycle_state = 'LIVE'
                     OR poll.retention_until > CURRENT_TIMESTAMP)
                """, (row, number) -> poll(row, tenantId, meetingId),
                viewerUserId, tenantId, meetingId, pollId).stream().findFirst();
    }

    public List<Poll> polls(long tenantId, UUID meetingId, long viewerUserId) {
        return jdbc.query("""
                SELECT poll.*,
                       vote.option_id AS viewer_option_id,
                       COALESCE(vote.ballot_version, 0) AS viewer_ballot_version
                  FROM vm_meeting_facilitation_polls poll
                  JOIN vm_meetings meeting
                    ON meeting.tenant_id = poll.tenant_id
                   AND meeting.meeting_id = poll.meeting_id
                  LEFT JOIN vm_meeting_facilitation_poll_votes vote
                    ON vote.tenant_id = poll.tenant_id
                   AND vote.meeting_id = poll.meeting_id
                   AND vote.poll_id = poll.poll_id
                   AND vote.voter_user_id = ?
                 WHERE poll.tenant_id = ? AND poll.meeting_id = ?
                   AND (meeting.lifecycle_state = 'LIVE'
                     OR poll.retention_until > CURRENT_TIMESTAMP)
                 ORDER BY CASE poll.poll_state WHEN 'OPEN' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                          poll.created_at DESC
                 LIMIT 20
                """, (row, number) -> poll(row, tenantId, meetingId),
                viewerUserId, tenantId, meetingId);
    }

    public Optional<Poll> transitionPoll(
            Poll current, PollState target, long expectedVersion, long sequence,
            OffsetDateTime now, long viewerUserId) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_facilitation_polls
                   SET poll_state = ?,
                       opened_at = CASE WHEN ? = 'OPEN' THEN ? ELSE opened_at END,
                       closed_at = CASE WHEN ? = 'CLOSED' THEN ? ELSE NULL END,
                       version = version + 1, last_sequence = ?, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND poll_id = ?
                   AND version = ?
                   AND ((? = 'OPEN' AND poll_state = 'DRAFT')
                     OR (? = 'CLOSED' AND poll_state = 'OPEN'))
                """, target.name(), target.name(), now, target.name(), now,
                sequence, now, current.tenantId(), current.meetingId(), current.pollId(),
                expectedVersion, target.name(), target.name());
        if (updated == 0) return Optional.empty();
        return poll(current.tenantId(), current.meetingId(), current.pollId(), viewerUserId);
    }

    public Optional<Long> castVote(
            Poll poll, UUID optionId, Participant voter, long expectedBallotVersion,
            long sequence, OffsetDateTime now) {
        List<Long> version = jdbc.query("""
                INSERT INTO vm_meeting_facilitation_poll_votes (
                    tenant_id, meeting_id, poll_id, option_id,
                    voter_participant_id, voter_user_id, ballot_version,
                    created_at, updated_at)
                SELECT ?, ?, ?, ?, ?, ?, 1, ?, ?
                 WHERE ? = 0 OR EXISTS (
                     SELECT 1
                       FROM vm_meeting_facilitation_poll_votes current_ballot
                      WHERE current_ballot.tenant_id = ?
                        AND current_ballot.meeting_id = ?
                        AND current_ballot.poll_id = ?
                        AND current_ballot.voter_user_id = ?
                        AND current_ballot.ballot_version = ?)
                ON CONFLICT (tenant_id, meeting_id, poll_id, voter_user_id) DO UPDATE
                   SET option_id = EXCLUDED.option_id,
                       voter_participant_id = EXCLUDED.voter_participant_id,
                       ballot_version = vm_meeting_facilitation_poll_votes.ballot_version + 1,
                       updated_at = EXCLUDED.updated_at
                 WHERE vm_meeting_facilitation_poll_votes.ballot_version = ?
                RETURNING ballot_version
                """, (row, number) -> row.getLong("ballot_version"),
                poll.tenantId(), poll.meetingId(), poll.pollId(), optionId,
                voter.participantId(), voter.userId(), now, now,
                expectedBallotVersion,
                poll.tenantId(), poll.meetingId(), poll.pollId(), voter.userId(),
                expectedBallotVersion, expectedBallotVersion);
        if (version.isEmpty()) return Optional.empty();
        jdbc.update("""
                UPDATE vm_meeting_facilitation_polls
                   SET last_sequence = ?, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND poll_id = ?
                """, sequence, now, poll.tenantId(), poll.meetingId(), poll.pollId());
        return Optional.of(version.getFirst());
    }

    public boolean pollOptionExists(
            long tenantId, UUID meetingId, UUID pollId, UUID optionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM vm_meeting_facilitation_poll_options
                     WHERE tenant_id = ? AND meeting_id = ?
                       AND poll_id = ? AND option_id = ?)
                """, Boolean.class, tenantId, meetingId, pollId, optionId));
    }

    public Optional<StoredCommand> command(
            long tenantId, UUID meetingId, long actorUserId,
            String operation, UUID idempotencyKey) {
        return jdbc.query("""
                SELECT request_sha256, result_resource_id, result_version
                  FROM vm_meeting_facilitation_commands
                 WHERE tenant_id = ? AND meeting_id = ? AND actor_user_id = ?
                   AND operation = ? AND idempotency_key = ?
                """, (row, number) -> new StoredCommand(
                        row.getString("request_sha256"),
                        row.getObject("result_resource_id", UUID.class),
                        row.getLong("result_version")),
                tenantId, meetingId, actorUserId, operation, idempotencyKey)
                .stream().findFirst();
    }

    public void saveCommand(
            long tenantId, UUID meetingId, long actorUserId, String operation,
            UUID idempotencyKey, String requestSha256, UUID resultResourceId,
            long resultVersion, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_commands (
                    tenant_id, meeting_id, actor_user_id, operation, idempotency_key,
                    request_sha256, result_resource_id, result_version, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, meetingId, actorUserId, operation, idempotencyKey,
                requestSha256, resultResourceId, resultVersion, now);
    }

    public List<ExpiredFacilitation> expired(int limit) {
        return jdbc.query("""
                SELECT state.tenant_id, state.meeting_id
                  FROM vm_meeting_facilitation_states state
                  JOIN vm_meetings meeting
                    ON meeting.tenant_id = state.tenant_id
                   AND meeting.meeting_id = state.meeting_id
                 WHERE state.retention_until <= CURRENT_TIMESTAMP
                   AND meeting.lifecycle_state <> 'LIVE'
                 ORDER BY state.retention_until
                 LIMIT ?
                 FOR UPDATE OF state SKIP LOCKED
                """, (row, number) -> new ExpiredFacilitation(
                        row.getLong("tenant_id"), row.getObject("meeting_id", UUID.class)),
                Math.max(1, Math.min(100, limit)));
    }

    public int delete(ExpiredFacilitation expired) {
        return jdbc.update("""
                DELETE FROM vm_meeting_facilitation_states
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND retention_until <= CURRENT_TIMESTAMP
                """, expired.tenantId(), expired.meetingId());
    }

    public void saveRetentionEvidence(UUID executionId, int deletedCount, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO vm_meeting_facilitation_retention_evidence (
                    execution_id, deleted_meeting_count, completed_at)
                VALUES (?, ?, ?)
                """, executionId, deletedCount, now);
    }

    private FacilitationState state(ResultSet row, int rowNumber) throws SQLException {
        return state(row, rowNumber, row.getString("agenda_item_title"));
    }

    private FacilitationState state(
            ResultSet row, int rowNumber, String agendaItemTitle) throws SQLException {
        return new FacilitationState(
                row.getLong("tenant_id"), row.getObject("meeting_id", UUID.class),
                row.getLong("last_sequence"),
                TimerState.valueOf(row.getString("timer_state")),
                row.getObject("agenda_item_id", UUID.class), agendaItemTitle,
                nullableInteger(row, "timer_planned_seconds"),
                row.getInt("timer_elapsed_seconds"),
                row.getObject("timer_running_since", OffsetDateTime.class),
                row.getLong("timer_version"),
                row.getObject("retention_until", OffsetDateTime.class));
    }

    private AgendaItem agendaItem(ResultSet row, int rowNumber) throws SQLException {
        return new AgendaItem(
                row.getObject("item_id", UUID.class), row.getString("title"),
                row.getInt("position"), row.getInt("planned_seconds"));
    }

    private Question question(ResultSet row, int rowNumber) throws SQLException {
        return new Question(
                row.getObject("question_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class),
                row.getObject("author_participant_id", UUID.class),
                row.getLong("author_user_id"), row.getString("author_display_name"),
                QuestionState.valueOf(row.getString("question_state")),
                row.getString("question_text"), row.getString("answer_text"),
                row.getInt("upvote_count"), row.getBoolean("upvoted_by_viewer"),
                row.getLong("version"), row.getLong("last_sequence"),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("answered_at", OffsetDateTime.class),
                row.getObject("retention_until", OffsetDateTime.class));
    }

    private Poll poll(ResultSet row, long tenantId, UUID meetingId) throws SQLException {
        UUID pollId = row.getObject("poll_id", UUID.class);
        List<PollOption> options = jdbc.query("""
                SELECT option.option_id, option.position, option.option_label,
                       COUNT(vote.voter_user_id)::INTEGER vote_count
                  FROM vm_meeting_facilitation_poll_options option
                  LEFT JOIN vm_meeting_facilitation_poll_votes vote
                    ON vote.tenant_id = option.tenant_id
                   AND vote.meeting_id = option.meeting_id
                   AND vote.poll_id = option.poll_id
                   AND vote.option_id = option.option_id
                 WHERE option.tenant_id = ? AND option.meeting_id = ? AND option.poll_id = ?
                 GROUP BY option.option_id, option.position, option.option_label
                 ORDER BY option.position
                """, (optionRow, number) -> new PollOption(
                        optionRow.getObject("option_id", UUID.class),
                        optionRow.getInt("position"), optionRow.getString("option_label"),
                        optionRow.getInt("vote_count")),
                tenantId, meetingId, pollId);
        int totalVotes = options.stream().mapToInt(PollOption::voteCount).sum();
        return new Poll(
                pollId, tenantId, meetingId,
                PollState.valueOf(row.getString("poll_state")),
                row.getString("poll_question"), row.getBoolean("anonymous"),
                options, totalVotes, row.getObject("viewer_option_id", UUID.class),
                row.getLong("viewer_ballot_version"), row.getLong("version"),
                row.getLong("last_sequence"),
                row.getObject("opened_at", OffsetDateTime.class),
                row.getObject("closed_at", OffsetDateTime.class),
                row.getObject("retention_until", OffsetDateTime.class));
    }

    private Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
}
