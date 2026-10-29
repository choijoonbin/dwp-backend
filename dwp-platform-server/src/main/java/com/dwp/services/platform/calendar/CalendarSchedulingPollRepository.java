package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.*;

@Repository
class CalendarSchedulingPollRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    CalendarSchedulingPollRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean identityMatches(long tenantId, long userId, UUID personPublicId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM cal_identity_links
                 WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?
                """, Integer.class, tenantId, userId, personPublicId);
        return count != null && count == 1;
    }

    Optional<IdentityRow> identityByPerson(long tenantId, UUID personPublicId) {
        return jdbc.query("""
                SELECT user_id, person_public_id
                  FROM cal_identity_links
                 WHERE tenant_id = ? AND person_public_id = ?
                """, (result, ignored) -> new IdentityRow(
                        result.getLong("user_id"),
                        result.getObject("person_public_id", UUID.class)),
                tenantId, personPublicId).stream().findFirst();
    }

    Optional<IdentityProfile> identityProfile(
            long tenantId, long userId, UUID personPublicId) {
        return jdbc.query("""
                SELECT identity.user_id, identity.person_public_id,
                       account.email_address, account.display_name
                  FROM cal_identity_links identity
                  JOIN mail_accounts account
                    ON account.tenant_id = identity.tenant_id
                   AND account.owner_user_id = identity.user_id
                   AND account.owner_person_public_id = identity.person_public_id
                   AND account.account_kind = 'PERSONAL'
                   AND account.is_default = TRUE
                   AND account.connection_state = 'ACTIVE'
                 WHERE identity.tenant_id = ?
                   AND identity.user_id = ?
                   AND identity.person_public_id = ?
                 ORDER BY account.account_id
                 LIMIT 1
                """, (result, ignored) -> new IdentityProfile(
                        result.getLong("user_id"),
                        result.getObject("person_public_id", UUID.class),
                        result.getString("email_address"),
                        result.getString("display_name")),
                tenantId, userId, personPublicId).stream().findFirst();
    }

    void lockIdempotency(long tenantId, long actorUserId, UUID idempotencyKey) {
        jdbc.queryForObject("""
                SELECT 1 FROM (
                    SELECT pg_advisory_xact_lock(hashtextextended(?, 0))
                ) locked
                """, Integer.class,
                tenantId + ":calendar-poll:" + actorUserId + ":" + idempotencyKey);
    }

    Optional<PollRow> pollByCreateIdempotency(
            long tenantId, long organizerUserId, UUID idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_polls
                 WHERE tenant_id = ? AND organizer_user_id = ?
                   AND create_idempotency_key = ?
                """, this::poll, tenantId, organizerUserId, idempotencyKey)
                .stream().findFirst();
    }

    void insertPoll(
            UUID pollId,
            long tenantId,
            long organizerUserId,
            UUID organizerPersonPublicId,
            CreatePollRequest value,
            String fingerprint,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_scheduling_polls (
                    poll_id, tenant_id, organizer_user_id, organizer_person_public_id,
                    state, title, description, time_zone, expires_at,
                    create_idempotency_key, create_fingerprint,
                    version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """, pollId, tenantId, organizerUserId, organizerPersonPublicId,
                value.title(), blankToNull(value.description()), value.timeZone(),
                value.expiresAt(), value.idempotencyKey(), fingerprint,
                now, organizerUserId, now, organizerUserId);
    }

    void insertCandidates(long tenantId, UUID pollId, List<PollCandidateInput> values) {
        for (int index = 0; index < values.size(); index++) {
            PollCandidateInput value = values.get(index);
            jdbc.update("""
                    INSERT INTO cal_scheduling_poll_candidates (
                        tenant_id, poll_id, candidate_id, ordinal,
                        starts_at, ends_at, resource_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, pollId, value.candidateId(), index + 1,
                    value.startsAt(), value.endsAt(), value.resourceId());
        }
    }

    void insertParticipant(
            long tenantId,
            UUID pollId,
            UUID participantId,
            IdentityRow identity,
            PollParticipantInput value,
            boolean externalVotingEnabled) {
        jdbc.update("""
                INSERT INTO cal_scheduling_poll_participants (
                    tenant_id, poll_id, participant_id,
                    participant_user_id, participant_person_public_id,
                    external_email, display_name, required, external_voting_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, pollId, participantId,
                identity == null ? null : identity.userId(),
                identity == null ? null : identity.personPublicId(),
                identity == null ? normalizeEmail(value.externalEmail()) : null,
                value.displayName(), value.required(), externalVotingEnabled);
    }

    List<PollRow> polls(
            long tenantId,
            long userId,
            UUID personPublicId,
            OffsetDateTime afterUpdatedAt,
            UUID afterPollId,
            int limit) {
        return jdbc.query("""
                SELECT poll.*
                  FROM cal_scheduling_polls poll
                 WHERE poll.tenant_id = ?
                   AND (poll.organizer_person_public_id = ?
                        OR EXISTS (
                            SELECT 1 FROM cal_scheduling_poll_participants participant
                             WHERE participant.tenant_id = poll.tenant_id
                               AND participant.poll_id = poll.poll_id
                               AND participant.participant_user_id = ?
                               AND participant.participant_person_public_id = ?))
                   AND (?::timestamptz IS NULL
                        OR poll.updated_at < ?::timestamptz
                        OR (poll.updated_at = ?::timestamptz AND poll.poll_id > ?::uuid))
                 ORDER BY poll.updated_at DESC, poll.poll_id
                 LIMIT ?
                """, this::poll, tenantId, personPublicId, userId, personPublicId,
                afterUpdatedAt, afterUpdatedAt, afterUpdatedAt, afterPollId, limit);
    }

    Optional<PollRow> poll(long tenantId, UUID pollId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_polls
                 WHERE tenant_id = ? AND poll_id = ?
                """, this::poll, tenantId, pollId).stream().findFirst();
    }

    Optional<PollRow> lockPoll(long tenantId, UUID pollId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_polls
                 WHERE tenant_id = ? AND poll_id = ?
                 FOR UPDATE
                """, this::poll, tenantId, pollId).stream().findFirst();
    }

    List<CandidateRow> candidates(long tenantId, UUID pollId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_candidates
                 WHERE tenant_id = ? AND poll_id = ?
                 ORDER BY ordinal
                """, this::candidate, tenantId, pollId);
    }

    Optional<CandidateRow> candidate(long tenantId, UUID pollId, UUID candidateId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_candidates
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                """, this::candidate, tenantId, pollId, candidateId)
                .stream().findFirst();
    }

    List<ParticipantRow> participants(long tenantId, UUID pollId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_participants
                 WHERE tenant_id = ? AND poll_id = ?
                 ORDER BY required DESC, display_name, participant_id
                """, this::participant, tenantId, pollId);
    }

    Optional<ParticipantRow> participant(
            long tenantId, UUID pollId, long userId, UUID personPublicId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_participants
                 WHERE tenant_id = ? AND poll_id = ?
                   AND participant_user_id = ? AND participant_person_public_id = ?
                """, this::participant, tenantId, pollId, userId, personPublicId)
                .stream().findFirst();
    }

    List<VoteRow> votes(long tenantId, UUID pollId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_votes
                 WHERE tenant_id = ? AND poll_id = ?
                 ORDER BY participant_id, candidate_id
                """, this::vote, tenantId, pollId);
    }

    void upsertVote(
            long tenantId,
            UUID pollId,
            UUID participantId,
            VoteInput vote,
            String comment,
            long actorUserId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_scheduling_poll_votes (
                    tenant_id, poll_id, participant_id, candidate_id,
                    choice, comment, version, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (tenant_id, poll_id, participant_id, candidate_id)
                DO UPDATE SET choice = EXCLUDED.choice, comment = EXCLUDED.comment,
                              version = cal_scheduling_poll_votes.version + 1,
                              updated_at = EXCLUDED.updated_at,
                              updated_by = EXCLUDED.updated_by
                """, tenantId, pollId, participantId, vote.candidateId(),
                vote.choice().name(), blankToNull(comment), now, now, actorUserId);
    }

    Optional<CommandReceiptRow> receipt(
            long tenantId, long actorUserId, UUID idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_command_receipts
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, this::receipt, tenantId, actorUserId, idempotencyKey)
                .stream().findFirst();
    }

    void insertReceipt(
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            UUID idempotencyKey,
            UUID pollId,
            String commandType,
            String fingerprint,
            PollState resultingState,
            long resultingVersion,
            UUID resultingEventId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_scheduling_poll_command_receipts (
                    tenant_id, actor_user_id, actor_person_public_id,
                    idempotency_key, poll_id, command_type, request_fingerprint,
                    resulting_state, resulting_version, resulting_event_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, actorUserId, actorPersonPublicId, idempotencyKey,
                pollId, commandType, fingerprint, resultingState.name(),
                resultingVersion, resultingEventId, now);
    }

    int transition(
            long tenantId,
            UUID pollId,
            PollState from,
            PollState to,
            long expectedVersion,
            long actorUserId,
            OffsetDateTime purgeAfter,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_polls
                   SET state = ?, version = version + 1,
                       purge_after = CASE WHEN legal_hold THEN NULL ELSE ? END,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ?
                   AND state = ? AND version = ?
                """, to.name(), purgeAfter, now, actorUserId,
                tenantId, pollId, from.name(), expectedVersion);
    }

    int advanceVersion(
            long tenantId,
            UUID pollId,
            PollState state,
            long expectedVersion,
            long actorUserId,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_polls
                   SET version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ?
                   AND state = ? AND version = ?
                """, now, actorUserId, tenantId, pollId, state.name(), expectedVersion);
    }

    int confirm(
            long tenantId,
            UUID pollId,
            long expectedVersion,
            UUID candidateId,
            UUID eventId,
            long actorUserId,
            OffsetDateTime purgeAfter,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_polls
                   SET state = 'CONFIRMED', confirmed_candidate_id = ?,
                       confirmed_event_id = ?, version = version + 1,
                       purge_after = CASE WHEN legal_hold THEN NULL ELSE ? END,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ?
                   AND state = 'AGREEMENT_REACHED' AND version = ?
                """, candidateId, eventId, purgeAfter, now, actorUserId,
                tenantId, pollId, expectedVersion);
    }

    int markCandidateAvailability(
            long tenantId,
            UUID pollId,
            UUID candidateId,
            String state,
            String reason,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_candidates
                   SET availability_state = ?, availability_reason = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                """, state, reason, now, tenantId, pollId, candidateId);
    }

    void insertPeopleHold(
            UUID holdId,
            long tenantId,
            UUID pollId,
            CandidateRow candidate,
            ParticipantRow participant,
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

    HoldRow insertRoomHold(
            UUID holdId,
            long tenantId,
            UUID pollId,
            CandidateRow candidate,
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
                Boolean.class, tenantId, resourceId);
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

    List<HoldRow> holds(long tenantId, UUID pollId) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_poll_holds
                 WHERE tenant_id = ? AND poll_id = ?
                 ORDER BY candidate_id, kind, hold_id
                """, this::hold, tenantId, pollId);
    }

    int expireHolds(long tenantId, UUID pollId, OffsetDateTime now, long actorUserId) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = 'EXPIRED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ?
                   AND state = 'ACTIVE' AND expires_at <= ?
                """, now, actorUserId, tenantId, pollId, now);
    }

    int releaseHolds(long tenantId, UUID pollId, long actorUserId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_scheduling_poll_holds
                   SET state = 'RELEASED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND poll_id = ? AND state = 'ACTIVE'
                """, now, actorUserId, tenantId, pollId);
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
            long tenantId, UUID pollId, long actorUserId, OffsetDateTime now) {
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
            long tenantId, UUID pollId, UUID candidateId, OffsetDateTime now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM cal_scheduling_poll_holds
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                   AND kind = 'ROOM_HARD' AND state = 'ACTIVE' AND expires_at > ?
                """, Integer.class, tenantId, pollId, candidateId, now);
        return count != null && count == 1;
    }

    int activePeopleHoldCount(
            long tenantId, UUID pollId, UUID candidateId, OffsetDateTime now) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM cal_scheduling_poll_holds
                 WHERE tenant_id = ? AND poll_id = ? AND candidate_id = ?
                   AND kind = 'PEOPLE_SOFT' AND state = 'ACTIVE' AND expires_at > ?
                """, Integer.class, tenantId, pollId, candidateId, now);
        return count == null ? 0 : count;
    }

    void audit(
            long tenantId,
            UUID pollId,
            long actorUserId,
            String action,
            Map<String, ?> before,
            Map<String, ?> after,
            String correlationId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_scheduling_poll_audit_events (
                    tenant_id, poll_id, actor_user_id, action,
                    before_snapshot, after_snapshot, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, tenantId, pollId, actorUserId, action,
                write(before), write(after), correlationId, now);
    }

    int purge(long tenantId, OffsetDateTime now, int limit) {
        return jdbc.update("""
                DELETE FROM cal_scheduling_polls
                 WHERE (tenant_id, poll_id) IN (
                    SELECT tenant_id, poll_id
                      FROM cal_scheduling_polls
                     WHERE tenant_id = ? AND purge_after <= ? AND legal_hold = FALSE
                     ORDER BY purge_after, poll_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                """, tenantId, now, limit);
    }

    List<PollRow> duePolls(OffsetDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM cal_scheduling_polls
                 WHERE expires_at <= ?
                   AND state IN ('DRAFT', 'OPEN', 'AGREEMENT_REACHED')
                 ORDER BY expires_at, poll_id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, this::poll, now, limit);
    }

    int expireStaleHolds(OffsetDateTime now, int limit) {
        return jdbc.update("""
                WITH stale AS (
                    SELECT hold_row.hold_id, poll.organizer_user_id
                      FROM cal_scheduling_poll_holds hold_row
                      JOIN cal_scheduling_polls poll
                        ON poll.tenant_id = hold_row.tenant_id
                       AND poll.poll_id = hold_row.poll_id
                     WHERE hold_row.state = 'ACTIVE' AND hold_row.expires_at <= ?
                     ORDER BY hold_row.expires_at, hold_row.hold_id
                     LIMIT ?
                     FOR UPDATE OF hold_row SKIP LOCKED)
                UPDATE cal_scheduling_poll_holds hold_row
                   SET state = 'EXPIRED', version = hold_row.version + 1,
                       updated_at = ?, updated_by = stale.organizer_user_id
                  FROM stale
                 WHERE hold_row.hold_id = stale.hold_id
                """, now, limit, now);
    }

    int purgeGlobal(OffsetDateTime now, int limit) {
        return jdbc.update("""
                DELETE FROM cal_scheduling_polls
                 WHERE (tenant_id, poll_id) IN (
                    SELECT tenant_id, poll_id
                      FROM cal_scheduling_polls
                     WHERE purge_after <= ? AND legal_hold = FALSE
                     ORDER BY purge_after, poll_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                """, now, limit);
    }

    private PollRow poll(ResultSet result, int ignored) throws SQLException {
        return new PollRow(
                result.getLong("tenant_id"),
                result.getObject("poll_id", UUID.class),
                result.getLong("organizer_user_id"),
                result.getObject("organizer_person_public_id", UUID.class),
                PollState.valueOf(result.getString("state")),
                result.getString("title"), result.getString("description"),
                result.getString("time_zone"),
                result.getObject("expires_at", OffsetDateTime.class),
                result.getObject("confirmed_candidate_id", UUID.class),
                result.getObject("confirmed_event_id", UUID.class),
                result.getObject("create_idempotency_key", UUID.class),
                result.getString("create_fingerprint"),
                result.getObject("purge_after", OffsetDateTime.class),
                result.getBoolean("legal_hold"), result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private CandidateRow candidate(ResultSet result, int ignored) throws SQLException {
        return new CandidateRow(
                result.getObject("candidate_id", UUID.class), result.getInt("ordinal"),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getObject("resource_id", UUID.class),
                result.getString("availability_state"),
                result.getString("availability_reason"), result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private ParticipantRow participant(ResultSet result, int ignored) throws SQLException {
        return new ParticipantRow(
                result.getObject("participant_id", UUID.class),
                nullableLong(result, "participant_user_id"),
                result.getObject("participant_person_public_id", UUID.class),
                result.getString("external_email"), result.getString("display_name"),
                result.getBoolean("required"), result.getBoolean("external_voting_enabled"));
    }

    private VoteRow vote(ResultSet result, int ignored) throws SQLException {
        return new VoteRow(
                result.getObject("participant_id", UUID.class),
                result.getObject("candidate_id", UUID.class),
                VoteChoice.valueOf(result.getString("choice")),
                result.getString("comment"), result.getLong("version"));
    }

    private CommandReceiptRow receipt(ResultSet result, int ignored) throws SQLException {
        return new CommandReceiptRow(
                result.getObject("poll_id", UUID.class), result.getString("command_type"),
                result.getString("request_fingerprint"),
                PollState.valueOf(result.getString("resulting_state")),
                result.getLong("resulting_version"),
                result.getObject("resulting_event_id", UUID.class));
    }

    private HoldRow hold(ResultSet result, int ignored) throws SQLException {
        return new HoldRow(
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
                result.getLong("fencing_token"), result.getLong("version"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Calendar scheduling poll audit is invalid.", exception);
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    record IdentityRow(long userId, UUID personPublicId) { }

    record IdentityProfile(
            long userId,
            UUID personPublicId,
            String email,
            String displayName) { }

    record PollRow(
            long tenantId,
            UUID pollId,
            long organizerUserId,
            UUID organizerPersonPublicId,
            PollState state,
            String title,
            String description,
            String timeZone,
            OffsetDateTime expiresAt,
            UUID confirmedCandidateId,
            UUID confirmedEventId,
            UUID createIdempotencyKey,
            String createFingerprint,
            OffsetDateTime purgeAfter,
            boolean legalHold,
            long version,
            OffsetDateTime updatedAt) { }

    record CandidateRow(
            UUID candidateId,
            int ordinal,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID resourceId,
            String availabilityState,
            String availabilityReason,
            long version,
            OffsetDateTime updatedAt) { }

    record ParticipantRow(
            UUID participantId,
            Long userId,
            UUID personPublicId,
            String externalEmail,
            String displayName,
            boolean required,
            boolean externalVotingEnabled) { }

    record VoteRow(
            UUID participantId,
            UUID candidateId,
            VoteChoice choice,
            String comment,
            long version) { }

    record CommandReceiptRow(
            UUID pollId,
            String commandType,
            String fingerprint,
            PollState resultingState,
            long resultingVersion,
            UUID resultingEventId) { }

    record HoldRow(
            UUID holdId,
            UUID candidateId,
            UUID participantId,
            PollHoldKind kind,
            PollHoldState state,
            UUID personPublicId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            long fencingToken,
            long version) { }
}
