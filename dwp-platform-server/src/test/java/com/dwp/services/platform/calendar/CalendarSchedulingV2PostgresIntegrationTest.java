package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CalendarSchedulingV2PostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(9_973_000L);

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private long tenant;
    private long actor;
    private UUID actorPerson;
    private CalendarSchedulingV2Repository repository;

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
                .target("340")
                .load();
        flyway.clean();
        flyway.migrate();
        try (java.sql.Connection connection = dataSource.getConnection()) {
            for (String migration : List.of(
                    "V373__govern_calendar_scheduling_v2_evidence.sql",
                    "V376__snapshot_calendar_trash_recurrence.sql",
                    "V377__govern_calendar_recurrence_and_event_queries.sql")) {
                java.nio.file.Path path = java.nio.file.Path.of(
                        "src/main/resources/db/migration/" + migration);
                if (migration.startsWith("V377__")) {
                    try (java.sql.Statement statement = connection.createStatement()) {
                        statement.execute(java.nio.file.Files.readString(path));
                    }
                } else {
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
                }
            }
        } catch (java.sql.SQLException | java.io.IOException exception) {
            throw new IllegalStateException(
                    "Calendar scheduling schema could not be created.", exception);
        }
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        tenant = TENANTS.incrementAndGet();
        actor = tenant + 100;
        actorPerson = UUID.randomUUID();
        repository = new CalendarSchedulingV2Repository(
                jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void challengeIsTenantActorExpiryAndReplayBoundAndPlaintextIsNeverStored() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-02T14:00:00Z");
        UUID evaluationId = UUID.randomUUID();
        String challenge = "challenge-plain-text";
        insertEvaluation(
                tenant, actor, actorPerson, evaluationId, challenge,
                now.minusSeconds(1), now.plusSeconds(24));

        assertThat(repository.consumeChallenge(
                tenant + 1, evaluationId, actor, actorPerson,
                hex('a'), CalendarSchedulingV2Hashes.sha256(challenge), now)).isEmpty();
        assertThat(repository.consumeChallenge(
                tenant, evaluationId, actor + 1, actorPerson,
                hex('a'), CalendarSchedulingV2Hashes.sha256(challenge), now)).isEmpty();
        assertThat(repository.consumeChallenge(
                tenant, evaluationId, actor, UUID.randomUUID(),
                hex('a'), CalendarSchedulingV2Hashes.sha256(challenge), now)).isEmpty();
        assertThat(repository.consumeChallenge(
                tenant, evaluationId, actor, actorPerson,
                hex('b'), CalendarSchedulingV2Hashes.sha256(challenge), now)).isEmpty();

        assertThat(repository.consumeChallenge(
                tenant, evaluationId, actor, actorPerson,
                hex('a'), CalendarSchedulingV2Hashes.sha256(challenge), now)).isPresent();
        assertThat(repository.consumeChallenge(
                tenant, evaluationId, actor, actorPerson,
                hex('a'), CalendarSchedulingV2Hashes.sha256(challenge), now)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT challenge_token_hash FROM cal_scheduling_evaluations_v2
                 WHERE tenant_id = ? AND evaluation_id = ?
                """, String.class, tenant, evaluationId))
                .isEqualTo(CalendarSchedulingV2Hashes.sha256(challenge))
                .isNotEqualTo(challenge);

        UUID expired = UUID.randomUUID();
        insertEvaluation(
                tenant, actor, actorPerson, expired, "expired-challenge",
                now.minusMinutes(2), now.minusMinutes(1));
        assertThat(repository.consumeChallenge(
                tenant, expired, actor, actorPerson, hex('a'),
                CalendarSchedulingV2Hashes.sha256("expired-challenge"), now)).isEmpty();
    }

    @Test
    void hardTokenConsumesOnceAndFailsClosedForEveryStoredBinding() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-02T14:00:00Z");
        UUID evaluationId = UUID.randomUUID();
        insertEvaluation(
                tenant, actor, actorPerson, evaluationId, "challenge-" + tenant,
                now.minusSeconds(2), now.plusSeconds(23));
        UUID roomId = UUID.randomUUID();
        String token = "hard-token";
        insertRecheck(
                tenant, actor, actorPerson, evaluationId, token, hex('b'), hex('c'),
                "source-v1", roomId, 17L, 9L, now.minusSeconds(1), now.plusSeconds(24));

        assertThat(consume(
                tenant + 1, actor, actorPerson, token, hex('b'), hex('c'),
                "source-v1", roomId, 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor + 1, actorPerson, token, hex('b'), hex('c'),
                "source-v1", roomId, 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor, UUID.randomUUID(), token, hex('b'), hex('c'),
                "source-v1", roomId, 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('d'), hex('c'),
                "source-v1", roomId, 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('b'), hex('d'),
                "source-v1", roomId, 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('b'), hex('c'),
                "source-v2", roomId, 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('b'), hex('c'),
                "source-v1", UUID.randomUUID(), 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('b'), hex('c'),
                "source-v1", roomId, 18L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('b'), hex('c'),
                "source-v1", roomId, 17L, 10L, now, UUID.randomUUID())).isFalse();

        UUID eventId = UUID.randomUUID();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('b'), hex('c'),
                "source-v1", roomId, 17L, 9L, now, eventId)).isTrue();
        assertThat(consume(
                tenant, actor, actorPerson, token, hex('b'), hex('c'),
                "source-v1", roomId, 17L, 9L, now, UUID.randomUUID())).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT consumed_by_event_id FROM cal_scheduling_rechecks_v2
                 WHERE tenant_id = ? AND hard_recheck_token_hash = ?
                """, UUID.class, tenant, CalendarSchedulingV2Hashes.sha256(token)))
                .isEqualTo(eventId);
    }

    @Test
    void transactionRollbackLeavesTokenReusable() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-02T14:00:00Z");
        UUID evaluationId = UUID.randomUUID();
        insertEvaluation(
                tenant, actor, actorPerson, evaluationId, "challenge-" + tenant,
                now.minusSeconds(2), now.plusSeconds(23));
        insertRecheck(
                tenant, actor, actorPerson, evaluationId, "rollback-token",
                hex('b'), hex('c'), "source-v1", null, null, 9L,
                now.minusSeconds(1), now.plusSeconds(24));

        transactions.executeWithoutResult(status -> {
            assertThat(consume(
                    tenant, actor, actorPerson, "rollback-token", hex('b'), hex('c'),
                    "source-v1", null, null, 9L, now, UUID.randomUUID())).isTrue();
            status.setRollbackOnly();
        });

        assertThat(consume(
                tenant, actor, actorPerson, "rollback-token", hex('b'), hex('c'),
                "source-v1", null, null, 9L, now, UUID.randomUUID())).isTrue();
    }

    @Test
    void purgeIsBoundedTenantScopedAndRemovesParticipantCriteriaPayloads() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-02T14:00:00Z");
        long otherTenant = tenant + 1;
        for (int index = 0; index < 3; index++) {
            insertEvaluation(
                    tenant, actor, actorPerson, UUID.randomUUID(), "expired-" + index,
                    now.minusMinutes(10), now.minusMinutes(9));
        }
        insertEvaluation(
                otherTenant, actor, actorPerson, UUID.randomUUID(), "other-expired",
                now.minusMinutes(10), now.minusMinutes(9));
        insertEvaluation(
                tenant, actor, actorPerson, UUID.randomUUID(), "active",
                now.minusSeconds(1), now.plusSeconds(24));

        assertThat(repository.purgeExpired(tenant, now.minusMinutes(5), 2)).isEqualTo(2);
        assertThat(count(tenant, true)).isEqualTo(1);
        assertThat(count(otherTenant, true)).isEqualTo(1);
        assertThat(repository.purgeExpiredGlobal(now.minusMinutes(5), 1000)).isEqualTo(2);
        assertThat(count(tenant, true)).isZero();
        assertThat(count(otherTenant, true)).isZero();
        assertThat(count(tenant, false)).isEqualTo(1);
    }

    @Test
    void boundedOccurrenceExpansionFastForwardsPastFourThousandAndAppliesOverrides() {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, tenant, actor, actorPerson);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', '내 캘린더', 'My calendar',
                        'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                """, calendarId, tenant, "v2-" + tenant, actor, actorPerson, actor, actor);
        OffsetDateTime seriesStart = OffsetDateTime.parse("2010-01-01T09:00:00+09:00");
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, description, event_type, starts_at, ends_at, time_zone,
                    status, visibility, importance, recurrence_pattern,
                    recurrence_interval, recurrence_until, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'owner@example.com',
                        'Old daily series', 'Agenda', 'MEETING', ?, ?, 'Asia/Seoul',
                        'CONFIRMED', 'DEFAULT', 'NORMAL', 'DAILY', 1, ?, TRUE,
                        'NATIVE', ?, ?)
                """, eventId, tenant, calendarId, actor, actorPerson,
                seriesStart, seriesStart.plusHours(1), LocalDate.of(2026, 12, 31),
                actor, actor);
        OffsetDateTime movedOriginal = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");
        jdbc.update("""
                INSERT INTO cal_event_occurrence_overrides (
                    tenant_id, event_id, original_starts_at, override_kind,
                    starts_at, ends_at, created_by, updated_by)
                VALUES (?, ?, ?, 'MODIFIED', ?, ?, ?, ?)
                """, tenant, eventId, movedOriginal,
                movedOriginal.plusHours(2), movedOriginal.plusHours(3), actor, actor);
        OffsetDateTime cancelledOriginal = OffsetDateTime.parse("2026-11-03T09:00:00+09:00");
        jdbc.update("""
                INSERT INTO cal_event_occurrence_overrides (
                    tenant_id, event_id, original_starts_at, override_kind,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'CANCELLED', ?, ?)
                """, tenant, eventId, cancelledOriginal, actor, actor);

        List<CalendarSchedulingV2Repository.IntervalRow> intervals = repository.intervals(
                tenant, List.of(actorPerson),
                OffsetDateTime.parse("2026-11-02T08:00:00+09:00"),
                OffsetDateTime.parse("2026-11-03T13:00:00+09:00"));

        assertThat(intervals).singleElement().satisfies(interval -> {
            assertThat(interval.startsAt().toInstant())
                    .isEqualTo(movedOriginal.plusHours(2).toInstant());
            assertThat(interval.endsAt().toInstant())
                    .isEqualTo(movedOriginal.plusHours(3).toInstant());
        });
    }

    @Test
    void boundedRepositoryQueriesBindBothWindowEdgesOnTheCurrentSchema() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-02T08:00:00+09:00");
        OffsetDateTime to = from.plusHours(4);
        CalendarRepository calendar = new CalendarRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());

        assertThat(calendar.busySlots(tenant, List.of(actorPerson), from, to)).isEmpty();
        assertThat(calendar.resources(tenant, from, to, false, false)).isEmpty();
        assertThat(calendar.resourceConflict(
                tenant, UUID.randomUUID(), from, to, null)).isFalse();
        assertThat(new RoomRepository(jdbc).resourceOccupancy(tenant, from, to)).isEmpty();
        assertThat(calendar.adminStats(tenant, from, to)).satisfies(stats -> {
            assertThat(stats.activeResources()).isZero();
            assertThat(stats.eventsThisWeek()).isZero();
        });
    }

    @Test
    void boundedSqlProjectionFastForwardsMonthlySequentialClampPastFourThousand() {
        OffsetDateTime anchor = OffsetDateTime.parse("1600-01-31T09:00:00+09:00");
        Map<String, Object> projection = jdbc.queryForMap("""
                SELECT ordinal,
                       (starts_at AT TIME ZONE 'Asia/Seoul')::date AS local_date
                  FROM cal_project_recurrences(
                      ?, ?, 'Asia/Seoul', 'MONTHLY', 1, NULL, NULL, NULL,
                      ?, ?, 4000)
                """,
                anchor,
                anchor.plusHours(1),
                OffsetDateTime.parse("2026-10-01T00:00:00+09:00"),
                OffsetDateTime.parse("2026-11-01T00:00:00+09:00"));

        assertThat(((Number) projection.get("ordinal")).intValue()).isGreaterThan(4000);
        assertThat(((java.sql.Date) projection.get("local_date")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 10, 28));
    }

    @Test
    void monthlyExpansionMatchesSequentialEndOfMonthClamping() {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, tenant, actor, actorPerson);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', '내 캘린더', 'My calendar',
                        'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                """, calendarId, tenant, "monthly-" + tenant,
                actor, actorPerson, actor, actor);
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-01-31T09:00:00+09:00");
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, description, event_type, starts_at, ends_at, time_zone,
                    status, visibility, importance, recurrence_pattern,
                    recurrence_interval, recurrence_until, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'owner@example.com',
                        'Month end', 'Agenda', 'MEETING', ?, ?, 'Asia/Seoul',
                        'CONFIRMED', 'DEFAULT', 'NORMAL', 'MONTHLY', 1, ?, TRUE,
                        'NATIVE', ?, ?)
                """, eventId, tenant, calendarId, actor, actorPerson,
                startsAt, startsAt.plusHours(1), LocalDate.of(2026, 6, 30), actor, actor);

        List<CalendarSchedulingV2Repository.IntervalRow> intervals = repository.intervals(
                tenant, List.of(actorPerson),
                OffsetDateTime.parse("2026-03-27T00:00:00+09:00"),
                OffsetDateTime.parse("2026-04-01T00:00:00+09:00"));

        assertThat(intervals).singleElement().satisfies(interval ->
                assertThat(interval.startsAt().toInstant()).isEqualTo(
                        OffsetDateTime.parse("2026-03-28T09:00:00+09:00").toInstant()));
    }

    @Test
    void sourceVersionJoinAndSchemaRejectCrossTenantAttendeeRows() {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, tenant, actor, actorPerson);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', '내 캘린더', 'My calendar',
                        'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                """, calendarId, tenant, "source-" + tenant,
                actor, actorPerson, actor, actor);
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, description, event_type, starts_at, ends_at, time_zone,
                    status, visibility, importance, recurrence_pattern,
                    recurrence_interval, response_required, source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'owner@example.com',
                        'Source test', 'Agenda', 'MEETING', ?, ?, 'Asia/Seoul',
                        'CONFIRMED', 'DEFAULT', 'NORMAL', 'NONE', 1, TRUE,
                        'NATIVE', ?, ?)
                """, eventId, tenant, calendarId, actor, actorPerson,
                startsAt, startsAt.plusHours(1), actor, actor);
        String before = repository.sourceVersion(tenant);
        assertThat(CalendarSchedulingV2Sql.SOURCE_VERSION)
                .contains("event.tenant_id = attendee.tenant_id");
        assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO cal_event_attendees (
                        tenant_id, event_id, attendee_person_public_id,
                        attendee_email, attendee_name, attendee_type, response_status)
                    VALUES (?, ?, ?, 'cross@example.com', 'Cross tenant',
                            'OPTIONAL', 'ACCEPTED')
                    """, tenant + 1, eventId, UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(repository.sourceVersion(tenant)).isEqualTo(before);
    }

    @Test
    void recurrenceSchemaProjectsByDayCountYearlyAndRejectsAmbiguousBounds() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-05T09:00:00+09:00");
        List<LocalDate> weekly = jdbc.query("""
                SELECT starts_at::date AS local_date
                  FROM cal_project_recurrences(
                      ?, ?, 'Asia/Seoul', 'WEEKLY', 2, NULL, 5,
                      ARRAY[1,3]::smallint[], ?, 4000)
                """, (row, ignored) -> row.getObject("local_date", LocalDate.class),
                startsAt, startsAt.plusMinutes(30), startsAt.plusMonths(2));
        assertThat(weekly).containsExactly(
                LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 7),
                LocalDate.of(2026, 10, 19),
                LocalDate.of(2026, 10, 21),
                LocalDate.of(2026, 11, 2));

        OffsetDateTime leap = OffsetDateTime.parse("2024-02-29T09:00:00+09:00");
        List<LocalDate> yearly = jdbc.query("""
                SELECT starts_at::date AS local_date
                  FROM cal_project_recurrences(
                      ?, ?, 'Asia/Seoul', 'YEARLY', 1, NULL, 5,
                      NULL, ?, 4000)
                """, (row, ignored) -> row.getObject("local_date", LocalDate.class),
                leap, leap.plusMinutes(30), leap.plusYears(6));
        assertThat(yearly).containsExactly(
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2025, 2, 28),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2028, 2, 28));

        UUID calendarId = UUID.randomUUID();
        insertIdentityAndCalendar(calendarId, "rule-");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, event_type,
                    starts_at, ends_at, time_zone, status, visibility, importance,
                    recurrence_pattern, recurrence_interval, recurrence_until,
                    recurrence_count, response_required, source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'Invalid', 'MEETING', ?, ?, 'Asia/Seoul',
                        'CONFIRMED', 'DEFAULT', 'NORMAL', 'DAILY', 1, ?, 2, FALSE,
                        'NATIVE', ?, ?)
                """, UUID.randomUUID(), tenant, calendarId, actor, actorPerson,
                startsAt, startsAt.plusMinutes(30), startsAt.toLocalDate().plusDays(1),
                actor, actor)).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, event_type,
                    starts_at, ends_at, time_zone, status, visibility, importance,
                    recurrence_pattern, recurrence_interval, recurrence_days,
                    response_required, source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'Invalid BYDAY', 'MEETING', ?, ?,
                        'Asia/Seoul', 'CONFIRMED', 'DEFAULT', 'NORMAL',
                        'WEEKLY', 1, ARRAY[3]::smallint[], FALSE, 'NATIVE', ?, ?)
                """, UUID.randomUUID(), tenant, calendarId, actor, actorPerson,
                startsAt, startsAt.plusMinutes(30), actor, actor)).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void endedCountSeriesIsNotAWindowCandidateButDirectIdLookupStillWorks() {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertIdentityAndCalendar(calendarId, "count-");
        OffsetDateTime startsAt = OffsetDateTime.parse("2020-01-01T09:00:00+09:00");
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, event_type, starts_at, ends_at, time_zone,
                    status, visibility, importance, recurrence_pattern,
                    recurrence_interval, recurrence_count, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'owner@example.com',
                        'Ended count', 'MEETING', ?, ?, 'Asia/Seoul',
                        'CONFIRMED', 'DEFAULT', 'NORMAL', 'DAILY', 1, 2, FALSE,
                        'NATIVE', ?, ?)
                """, eventId, tenant, calendarId, actor, actorPerson,
                startsAt, startsAt.plusMinutes(30), actor, actor);
        CalendarRepository calendar = new CalendarRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
        OffsetDateTime to = from.plusDays(1);

        assertThat(calendar.visibleEventCandidates(
                tenant, actor, actorPerson, null, from, to,
                false, null, null, 200)).isEmpty();
        assertThat(calendar.event(
                tenant, actor, actorPerson, null, eventId, false)).isPresent();
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE tablename IN ('cal_events', 'cal_event_attendees',
                                     'cal_event_occurrence_overrides')
                """, String.class)).contains(
                "idx_cal_events_tenant_active_start_keyset",
                "idx_cal_events_tenant_active_recurrence_range",
                "idx_cal_attendees_tenant_event_projection",
                "idx_cal_occurrence_overrides_tenant_event_projection");
    }

    private void insertIdentityAndCalendar(UUID calendarId, String keyPrefix) {
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, tenant, actor, actorPerson);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', '내 캘린더', 'My calendar',
                        'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                """, calendarId, tenant, keyPrefix + tenant,
                actor, actorPerson, actor, actor);
    }

    private void insertEvaluation(
            long tenantId,
            long actorUserId,
            UUID personId,
            UUID evaluationId,
            String challenge,
            OffsetDateTime generatedAt,
            OffsetDateTime expiresAt) {
        repository.insertEvaluation(new CalendarSchedulingV2Repository.EvaluationEvidence(
                evaluationId, tenantId, actorUserId, personId, hex('a'), hex('b'),
                "{\"participants\":[{\"personPublicId\":\"" + personId + "\"}]}",
                "[]", "[]", "source-v1",
                CalendarSchedulingV2Hashes.sha256(challenge), generatedAt, expiresAt));
    }

    private void insertRecheck(
            long tenantId,
            long actorUserId,
            UUID personId,
            UUID evaluationId,
            String token,
            String participantHash,
            String selectionHash,
            String sourceVersion,
            UUID roomId,
            Long resourceVersion,
            long policyVersion,
            OffsetDateTime checkedAt,
            OffsetDateTime expiresAt) {
        repository.insertRecheck(new CalendarSchedulingV2Repository.RecheckEvidence(
                UUID.randomUUID(), tenantId, evaluationId, actorUserId, personId,
                hex('a'), participantHash, selectionHash, roomId, sourceVersion,
                resourceVersion, policyVersion, "PASS",
                CalendarSchedulingV2Hashes.sha256(token), "{}", checkedAt, expiresAt));
    }

    private boolean consume(
            long tenantId,
            long actorUserId,
            UUID personId,
            String token,
            String participantHash,
            String selectionHash,
            String sourceVersion,
            UUID roomId,
            Long resourceVersion,
            long policyVersion,
            OffsetDateTime now,
            UUID eventId) {
        return repository.consumeToken(
                tenantId, actorUserId, personId, CalendarSchedulingV2Hashes.sha256(token),
                participantHash, selectionHash, sourceVersion, roomId, resourceVersion,
                policyVersion, now, eventId);
    }

    private long count(long tenantId, boolean expired) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_scheduling_evaluations_v2
                 WHERE tenant_id = ?
                   AND (expires_at < TIMESTAMPTZ '2026-11-02T13:55:00Z') = ?
                """, Long.class, tenantId, expired);
    }

    private String hex(char value) {
        return String.valueOf(value).repeat(64);
    }
}
