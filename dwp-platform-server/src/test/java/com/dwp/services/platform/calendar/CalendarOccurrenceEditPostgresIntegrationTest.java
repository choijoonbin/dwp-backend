package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CalendarOccurrenceEditPostgresIntegrationTest {

    private static final long TENANT_ID = 9_296_001L;
    private static final long ACTOR_ID = 9_296_101L;
    private static final UUID ACTOR_PERSON_ID =
            UUID.fromString("92960000-0000-4000-8000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private CalendarOccurrenceCommandService commands;
    private CalendarOccurrenceProjector projector;

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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        for (String table : new String[] {
                "cal_occurrence_command_receipts",
                "cal_event_occurrence_overrides",
                "cal_audit_events",
                "cal_event_attendees",
                "cal_events",
                "cal_calendars",
                "cal_identity_links"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT_ID);
        }
        CalendarRepository repository = new CalendarRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        CalendarOccurrenceRepository occurrenceRepository =
                new CalendarOccurrenceRepository(jdbc);
        projector = new CalendarOccurrenceProjector(repository, occurrenceRepository);
        CalendarSchedulingHorizon horizon = new CalendarSchedulingHorizon(Clock.fixed(
                Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
        commands = new CalendarOccurrenceCommandService(
                repository, occurrenceRepository, projector, horizon);
    }

    @Test
    void exactRetryMutatesOnceAndListProjectionAppliesOnlyTheSelectedOccurrence() {
        UUID eventId = fixture();
        OffsetDateTime original = OffsetDateTime.parse("2026-09-22T09:00:00+09:00");
        UUID key = UUID.randomUUID();
        CalendarDtos.UpdateEventRequest request = request(original, key, "Only this occurrence");

        CalendarDtos.EventSummary first = tx(() -> commands.updateOccurrence(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, eventId,
                "en", "corr-occurrence", null, request));
        CalendarDtos.EventSummary replay = tx(() -> commands.updateOccurrence(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, eventId,
                "en", "corr-occurrence", null, request));

        assertThat(replay).isEqualTo(first);
        assertThat(first.version()).isEqualTo(1L);
        assertThat(first.recurrenceId()).isEqualTo(original);
        assertThat(receiptCount(key)).isOne();
        assertThat(auditCount(eventId)).isOne();

        List<CalendarDtos.EventSummary> projected = projector.summaries(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID,
                OffsetDateTime.parse("2026-09-21T00:00:00+09:00"),
                OffsetDateTime.parse("2026-09-24T00:00:00+09:00"), "en");
        assertThat(projected).hasSize(3);
        assertThat(projected).filteredOn(value -> value.recurrenceId().isEqual(original))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.title()).isEqualTo("Only this occurrence");
                    assertThat(value.startsAt()).isEqualTo(original.plusHours(2));
                    assertThat(value.importance()).isEqualTo(EventImportance.HIGH);
                });
        assertThat(projected).filteredOn(value -> !value.recurrenceId().isEqual(original))
                .allMatch(value -> value.title().equals("Daily series"));

        assertThatThrownBy(() -> tx(() -> commands.updateOccurrence(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, eventId, "en", null, null,
                request(original, key, "Different payload"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void seriesScheduleChangeCleanupRemovesOnlyThatSeriesOverrides() {
        UUID eventId = fixture();
        OffsetDateTime original = OffsetDateTime.parse("2026-09-22T09:00:00+09:00");
        tx(() -> commands.updateOccurrence(
                TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, eventId,
                "en", "corr-occurrence", null,
                request(original, UUID.randomUUID(), "Only this occurrence")));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_event_occurrence_overrides
                 WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, TENANT_ID, eventId)).isOne();

        assertThat(tx(() -> commands.discardOverridesAfterSeriesScheduleChange(
                TENANT_ID, eventId))).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_event_occurrence_overrides
                 WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, TENANT_ID, eventId)).isZero();
    }

    private UUID fixture() {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-09-21T09:00:00+09:00");
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, 'occurrence-owner', ?, ?, 'Owner', '내 캘린더', 'My calendar',
                        'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                """, calendarId, TENANT_ID, ACTOR_ID, ACTOR_PERSON_ID, ACTOR_ID, ACTOR_ID);
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, organizer_email,
                    title, description, event_type, starts_at, ends_at, time_zone,
                    status, visibility, importance, recurrence_pattern,
                    recurrence_interval, recurrence_until, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'owner@example.com',
                        'Daily series', 'Agenda', 'MEETING', ?, ?, 'Asia/Seoul',
                        'CONFIRMED', 'DEFAULT', 'NORMAL', 'DAILY', 1, ?, TRUE,
                        'NATIVE', ?, ?)
                """, eventId, TENANT_ID, calendarId, ACTOR_ID, ACTOR_PERSON_ID,
                startsAt, startsAt.plusHours(1), LocalDate.of(2026, 9, 30),
                ACTOR_ID, ACTOR_ID);
        return eventId;
    }

    private CalendarDtos.UpdateEventRequest request(
            OffsetDateTime original,
            UUID key,
            String title) {
        return new CalendarDtos.UpdateEventRequest(
                title, "Changed agenda", EventType.MEETING,
                original.plusHours(2), original.plusHours(3), "Asia/Seoul", false,
                "Focus room", null, EventVisibility.PRIVATE,
                RecurrencePattern.DAILY, 1, LocalDate.of(2026, 9, 30),
                true, List.of(), null, 0L, EventImportance.HIGH,
                CalendarDtos.RecurrenceEditScope.THIS_OCCURRENCE, original, key);
    }

    private int receiptCount(UUID key) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_occurrence_command_receipts
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, Integer.class, TENANT_ID, ACTOR_ID, key);
    }

    private int auditCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_audit_events
                 WHERE tenant_id = ? AND event_id = ?
                   AND action = 'calendar.event.occurrence.updated'
                """, Integer.class, TENANT_ID, eventId);
    }

    private <T> T tx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }
}
