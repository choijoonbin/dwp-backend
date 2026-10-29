package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CalendarSchedulingPollPostgresIntegrationTest {

    private static final long TENANT_ID = 9_381_001L;
    private static final long ORGANIZER_USER_ID = 9_381_101L;
    private static final long PARTICIPANT_USER_ID = 9_381_102L;
    private static final UUID ORGANIZER_PERSON_ID =
            UUID.fromString("93810000-0000-4000-8000-000000000001");
    private static final UUID PARTICIPANT_PERSON_ID =
            UUID.fromString("93810000-0000-4000-8000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private CalendarSchedulingPollRepository repository;

    @BeforeAll
    static void migrateLatest() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("""
                UPDATE cal_scheduling_polls
                   SET legal_hold = FALSE
                 WHERE tenant_id = ?
                """, TENANT_ID);
        for (String table : new String[] {
                "cal_scheduling_poll_audit_events",
                "cal_scheduling_poll_holds",
                "cal_scheduling_poll_command_receipts",
                "cal_scheduling_poll_votes",
                "cal_scheduling_poll_participants",
                "cal_scheduling_poll_candidates",
                "cal_scheduling_polls",
                "cal_resource_bookings",
                "cal_events",
                "cal_resources",
                "cal_calendars",
                "cal_identity_links"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT_ID);
        }
        repository = new CalendarSchedulingPollRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        identity(ORGANIZER_USER_ID, ORGANIZER_PERSON_ID);
        identity(PARTICIPANT_USER_ID, PARTICIPANT_PERSON_ID);
    }

    @Test
    void tenantIdentityIdempotencyAndVoteKeysAreEnforcedByPostgres() {
        Fixture fixture = pollFixture(false);

        assertThat(repository.pollByCreateIdempotency(
                TENANT_ID, ORGANIZER_USER_ID, fixture.request().idempotencyKey()))
                .isPresent();
        assertThatThrownBy(() -> repository.insertPoll(
                UUID.randomUUID(), TENANT_ID, ORGANIZER_USER_ID, ORGANIZER_PERSON_ID,
                fixture.request(), "b".repeat(64), now()))
                .isInstanceOf(DataIntegrityViolationException.class);

        CalendarSchedulingPollRepository.ParticipantRow participant = repository.participant(
                TENANT_ID, fixture.pollId(), PARTICIPANT_USER_ID, PARTICIPANT_PERSON_ID)
                .orElseThrow();
        CalendarSchedulingPollRepository.CandidateRow candidate = repository.candidates(
                TENANT_ID, fixture.pollId()).getFirst();
        repository.upsertVote(
                TENANT_ID, fixture.pollId(), participant.participantId(),
                new VoteInput(candidate.candidateId(), VoteChoice.YES),
                "Works", PARTICIPANT_USER_ID, now());
        repository.upsertVote(
                TENANT_ID, fixture.pollId(), participant.participantId(),
                new VoteInput(candidate.candidateId(), VoteChoice.IF_NEEDED),
                "If needed", PARTICIPANT_USER_ID, now());

        assertThat(repository.votes(TENANT_ID, fixture.pollId()))
                .singleElement()
                .satisfies(vote -> {
                    assertThat(vote.choice()).isEqualTo(VoteChoice.IF_NEEDED);
                    assertThat(vote.version()).isOne();
                });
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cal_scheduling_poll_votes (
                    tenant_id, poll_id, participant_id, candidate_id,
                    choice, updated_by)
                VALUES (?, ?, ?, ?, 'YES', ?)
                """, TENANT_ID, fixture.pollId(), UUID.randomUUID(),
                candidate.candidateId(), PARTICIPANT_USER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roomHardHoldsAndResourceBookingsRejectEachOtherInBothDirections() {
        Fixture first = pollFixture(true);
        CalendarSchedulingPollRepository.CandidateRow firstCandidate =
                repository.candidates(TENANT_ID, first.pollId()).getFirst();
        repository.insertRoomHold(
                UUID.randomUUID(), TENANT_ID, first.pollId(), firstCandidate,
                first.request().expiresAt(), ORGANIZER_USER_ID, now());

        UUID eventId = event(firstCandidate.startsAt(), firstCandidate.endsAt());
        assertThatThrownBy(() -> booking(
                eventId, first.resourceId(), firstCandidate.startsAt(), firstCandidate.endsAt()))
                .isInstanceOf(DataIntegrityViolationException.class);

        repository.releaseHolds(TENANT_ID, first.pollId(), ORGANIZER_USER_ID, now());
        booking(eventId, first.resourceId(), firstCandidate.startsAt(), firstCandidate.endsAt());

        Fixture second = pollFixture(first.resourceId());
        CalendarSchedulingPollRepository.CandidateRow secondCandidate =
                repository.candidates(TENANT_ID, second.pollId()).getFirst();
        assertThatThrownBy(() -> repository.insertRoomHold(
                UUID.randomUUID(), TENANT_ID, second.pollId(), secondCandidate,
                second.request().expiresAt(), ORGANIZER_USER_ID, now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void logicalExpiryReleasesTheRoomWithoutDeletingHoldEvidence() {
        Fixture fixture = pollFixture(true);
        CalendarSchedulingPollRepository.CandidateRow candidate =
                repository.candidates(TENANT_ID, fixture.pollId()).getFirst();
        OffsetDateTime expiresAt = now().plusMinutes(2);
        repository.insertRoomHold(
                UUID.randomUUID(), TENANT_ID, fixture.pollId(), candidate,
                expiresAt, ORGANIZER_USER_ID, now());

        assertThat(repository.expireHolds(
                TENANT_ID, fixture.pollId(), expiresAt.plusSeconds(1), ORGANIZER_USER_ID))
                .isOne();
        UUID eventId = event(candidate.startsAt(), candidate.endsAt());
        booking(eventId, fixture.resourceId(), candidate.startsAt(), candidate.endsAt());

        assertThat(repository.holds(TENANT_ID, fixture.pollId()))
                .singleElement()
                .extracting(CalendarSchedulingPollRepository.HoldRow::state)
                .isEqualTo(PollHoldState.EXPIRED);
    }

    @Test
    void legalHoldPreventsRetentionDeletion() {
        Fixture fixture = pollFixture(false);
        jdbc.update("""
                UPDATE cal_scheduling_polls
                   SET state = 'CANCELLED', legal_hold = TRUE, purge_after = NULL
                 WHERE tenant_id = ? AND poll_id = ?
                """, TENANT_ID, fixture.pollId());

        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM cal_scheduling_polls
                 WHERE tenant_id = ? AND poll_id = ?
                """, TENANT_ID, fixture.pollId()))
                .isInstanceOf(DataAccessException.class);
        assertThat(repository.poll(TENANT_ID, fixture.pollId())).isPresent();
    }

    private Fixture pollFixture(boolean withResource) {
        return pollFixture(withResource ? resource() : null);
    }

    private Fixture pollFixture(UUID resourceId) {
        OffsetDateTime start = now().plusDays(2).withHour(1).withMinute(0);
        CreatePollRequest request = new CreatePollRequest(
                "Planning session", "Choose a time", "Asia/Seoul",
                List.of(new PollCandidateInput(
                        UUID.randomUUID(), start, start.plusMinutes(30), resourceId)),
                List.of(new PollParticipantInput(
                        PARTICIPANT_PERSON_ID, null, "Participant", true)),
                now().plusDays(1), UUID.randomUUID());
        UUID pollId = UUID.randomUUID();
        repository.insertPoll(
                pollId, TENANT_ID, ORGANIZER_USER_ID, ORGANIZER_PERSON_ID,
                request, "a".repeat(64), now());
        repository.insertCandidates(TENANT_ID, pollId, request.candidates());
        repository.insertParticipant(
                TENANT_ID, pollId, UUID.randomUUID(),
                new CalendarSchedulingPollRepository.IdentityRow(
                        PARTICIPANT_USER_ID, PARTICIPANT_PERSON_ID),
                request.participants().getFirst(), false);
        return new Fixture(pollId, resourceId, request);
    }

    private UUID resource() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_resources (
                    resource_id, tenant_id, resource_code, name_ko, name_en,
                    resource_type, site_name, capacity, features, time_zone,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, '회의실', 'Room', 'ROOM', 'HQ', 8, '[]'::jsonb,
                        'Asia/Seoul', 'AVAILABLE', ?, ?)
                """, id, TENANT_ID, "POLL-" + id, ORGANIZER_USER_ID, ORGANIZER_USER_ID);
        return id;
    }

    private UUID event(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID calendarId = jdbc.query("""
                SELECT calendar_id FROM cal_calendars
                 WHERE tenant_id = ? AND owner_person_public_id = ?
                """, (result, ignored) -> result.getObject(1, UUID.class),
                TENANT_ID, ORGANIZER_PERSON_ID).stream().findFirst().orElseGet(() -> {
                    UUID value = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO cal_calendars (
                                calendar_id, tenant_id, calendar_key, owner_user_id,
                                owner_person_public_id, owner_display_name, name_ko, name_en,
                                calendar_type, visibility, lifecycle_state, created_by, updated_by)
                            VALUES (?, ?, ?, ?, ?, 'Owner', '내 캘린더', 'My calendar',
                                    'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                            """, value, TENANT_ID, "poll-owner-" + value,
                            ORGANIZER_USER_ID, ORGANIZER_PERSON_ID,
                            ORGANIZER_USER_ID, ORGANIZER_USER_ID);
                    return value;
                });
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, event_type, starts_at, ends_at, time_zone,
                    status, visibility, importance, recurrence_pattern,
                    recurrence_interval, response_required, source_type,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'owner@example.com',
                        'Confirmed booking', 'MEETING', ?, ?, 'Asia/Seoul',
                        'CONFIRMED', 'DEFAULT', 'NORMAL', 'NONE', 1, FALSE,
                        'NATIVE', ?, ?)
                """, eventId, TENANT_ID, calendarId, ORGANIZER_USER_ID,
                ORGANIZER_PERSON_ID, startsAt, endsAt,
                ORGANIZER_USER_ID, ORGANIZER_USER_ID);
        return eventId;
    }

    private void booking(
            UUID eventId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    booking_id, tenant_id, event_id, resource_id,
                    starts_at, ends_at, booking_status, requested_by,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)
                """, UUID.randomUUID(), TENANT_ID, eventId, resourceId,
                startsAt, endsAt, ORGANIZER_USER_ID, ORGANIZER_USER_ID, ORGANIZER_USER_ID);
    }

    private void identity(long userId, UUID personId) {
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, TENANT_ID, userId, personId);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
    }

    private record Fixture(UUID pollId, UUID resourceId, CreatePollRequest request) { }
}
